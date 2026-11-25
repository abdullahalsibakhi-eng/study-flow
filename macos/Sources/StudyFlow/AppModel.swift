import SwiftUI
import Security
import UserNotifications
import AppKit

struct AppFailure: LocalizedError { var message: String; var errorDescription: String? { message } }
enum Keychain {
    static func save(_ name: String, _ value: String) throws {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "app.studyflow.credentials", kSecAttrAccount as String: name]
        if value.isEmpty {
            let result = SecItemDelete(q as CFDictionary)
            guard result == errSecSuccess || result == errSecItemNotFound else { throw AppFailure(message: "Unable to remove credential from Keychain.") }
            return
        }
        let data = Data(value.utf8)
        let status = SecItemUpdate(q as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var item = q; item[kSecValueData as String] = data; item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else { throw AppFailure(message: "Unable to save credential in Keychain.") }
        } else if status != errSecSuccess { throw AppFailure(message: "Unable to update credential in Keychain. The previous value was retained.") }
    }
    static func read(_ name: String) -> String? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "app.studyflow.credentials", kSecAttrAccount as String: name, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var result: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
@MainActor final class AppModel: ObservableObject {
    @Published var state: AppState?
    @Published var error: String?
    @Published var loading = true
    @Published var busy = false
    @Published var startupFailed = false
    @Published var oauthStatus = "Not connected"
    private var process: Process?
    private var poll: Task<Void, Never>?
    private var recoveryAttempts = 0
    private var token = UUID().uuidString + UUID().uuidString
    private var port = Int.random(in: 30000...60000)
    private var notified = Set(UserDefaults.standard.stringArray(forKey: "deliveredNotices") ?? [])
    func start() async {
        guard process == nil else { return }
        loading = true; startupFailed = false
        token = UUID().uuidString + UUID().uuidString
        port = Int.random(in: 30000...60000)
        do {
            let jar = ProcessInfo.processInfo.environment["STUDYFLOW_JAR"] ?? Bundle.main.resourceURL!.appendingPathComponent("studyflow-backend.jar").path
            guard FileManager.default.fileExists(atPath: jar) else { throw AppFailure(message: "Backend not found. Build the app with scripts/build.sh, then open dist/StudyFlow.app.") }
            let task = Process()
            let bundledJava = Bundle.main.resourceURL!.appendingPathComponent("runtime/bin/java").path
            task.executableURL = URL(fileURLWithPath: FileManager.default.fileExists(atPath: bundledJava) ? bundledJava : "/usr/bin/java")
            task.arguments = ["--enable-native-access=ALL-UNNAMED", "-jar", jar]
            var env = ProcessInfo.processInfo.environment
            env["STUDYFLOW_KEYCHAIN_HELPER"] = Bundle.main.resourceURL!.appendingPathComponent("CredentialHelper").path
            env["STUDYFLOW_PARENT_PID"] = String(ProcessInfo.processInfo.processIdentifier)
            env["STUDYFLOW_LOCAL_TOKEN"] = token; env["STUDYFLOW_PORT"] = String(port)
            task.environment = env; task.standardOutput = FileHandle.nullDevice; task.standardError = FileHandle.nullDevice
            task.terminationHandler = { [weak self] exited in
                Task { @MainActor in
                    guard let self, self.process === exited, !self.loading else { return }
                    self.process = nil; self.poll?.cancel(); self.poll = nil
                    guard self.recoveryAttempts < 3 else {
                        self.error = "The backend repeatedly stopped. Your database is retained. Review Java and storage availability, then retry."
                        self.startupFailed = true; return
                    }
                    self.recoveryAttempts += 1
                    self.error = "The local backend stopped. Reconnecting to your saved workspace…"
                    try? await Task.sleep(for: .seconds(self.recoveryAttempts * 2))
                    await self.start()
                }
            }
            try task.run(); process = task
            var ready = false
            for _ in 0..<80 {
                if !task.isRunning { break }
                if let _ = try? await request("health") { ready = true; break }
                try await Task.sleep(for: .milliseconds(150))
            }
            guard ready else { throw AppFailure(message: "Java backend could not start. Check Java 21+ availability and whether the data folder is writable.") }
            error = nil
            await refresh()
            await restoreConnections()
            poll = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(15))
                    guard !Task.isCancelled else { return }
                    await self?.refresh()
                }
            }
        } catch { self.error = error.localizedDescription; startupFailed = true; stop() }
        loading = false
    }
    func stop() { poll?.cancel(); poll = nil; let child = process; process = nil; child?.terminationHandler = nil; child?.terminate() }
    func request(_ path: String, body: [String: Any]? = nil) async throws -> Data {
        var req = URLRequest(url: URL(string: "http://127.0.0.1:\(port)/api/\(path)")!); req.timeoutInterval = path == "health" ? 1 : 50
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if let body { req.httpMethod = "POST"; req.setValue("application/json", forHTTPHeaderField: "Content-Type"); req.httpBody = try JSONSerialization.data(withJSONObject: body) }
        let (data, response) = try await URLSession.shared.data(for: req)
        guard let response = response as? HTTPURLResponse, (200..<300).contains(response.statusCode) else {
            let payload = (try? JSONSerialization.jsonObject(with: data)) as? [String: String]
            throw AppFailure(message: payload?["error"] ?? "The backend is unavailable.")
        }
        return data
    }
    func refresh() async {
        do { state = try JSONDecoder().decode(AppState.self, from: await request("state")); await deliverNotices()
            if let payload = try? JSONSerialization.jsonObject(with: await request("oauth/status")) as? [String: String] { oauthStatus = payload["status"] ?? "" }
        }
        catch { self.error = error.localizedDescription }
    }
    @discardableResult func mutate(_ path: String, _ body: [String: Any] = [:]) async -> Bool {
        busy = true; defer { busy = false }
        do { _ = try await request(path, body: body); await refresh(); return true }
        catch { self.error = error.localizedDescription; return false }
    }
    func courseName(_ id: String) -> String { state?.courses.first(where: { $0.id == id })?.code ?? "Course" }
    private func restoreConnections() async {
        // Isolated UI tests must never restore a real account from the shared Keychain service.
        guard ProcessInfo.processInfo.environment["STUDYFLOW_ISOLATED_TEST"] != "1" else { return }
        if let data = Keychain.read("brightspace-oauth"), let payload = try? JSONSerialization.jsonObject(with: Data(data.utf8)) as? [String: Any] { _ = await mutate("oauth/restore", payload) }
        else if let data = Keychain.read("brightspace"), let payload = try? JSONSerialization.jsonObject(with: Data(data.utf8)) as? [String: Any] { _ = await mutate("brightspace/connect", payload) }
        if let data = Keychain.read("ai"), let payload = try? JSONSerialization.jsonObject(with: Data(data.utf8)) as? [String: Any] { _ = await mutate("assistant/configure", payload) }
    }
    func saveConnection(_ name: String, path: String, payload: [String: Any]) async -> Bool {
        do {
            let encoded = String(data: try JSONSerialization.data(withJSONObject: payload), encoding: .utf8)!
            guard await mutate(path, payload) else { return false }
            do { try Keychain.save(name, encoded) }
            catch {
                _ = await mutate(name == "brightspace" ? "brightspace/disconnect" : "assistant/configure", ["enabled": false])
                throw error
            }
            if name == "brightspace" { try Keychain.save("brightspace-oauth", "") }
            return true
        } catch { self.error = error.localizedDescription; return false }
    }
    func disconnect(_ name: String, path: String) async {
        do {
            // Stop refresh before deleting credentials, so an in-flight rotation cannot restore them.
            guard await mutate(path, ["enabled": false]) else { return }
            try Keychain.save(name, ""); if name == "brightspace" { try Keychain.save("brightspace-oauth", "") }
        }
        catch { self.error = error.localizedDescription }
    }
    private func deliverNotices() async {
        guard let state, state.preferences.notifications, Bundle.main.bundleIdentifier != nil else { return }
        var calendar = Calendar.current
        calendar.timeZone = TimeZone(identifier: state.preferences.zone) ?? .current
        let hour = calendar.component(.hour, from: Date())
        let start = state.preferences.quietStart, end = state.preferences.quietEnd
        if start != end && (start < end ? (hour >= start && hour < end) : (hour >= start || hour < end)) { return }
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        guard settings.authorizationStatus == .authorized else { return }
        for notice in state.notices.filter({ !$0.read && !notified.contains($0.id) && (date($0.created) ?? .distantPast) > Date().addingTimeInterval(-172800) }).prefix(3) {
            let content = UNMutableNotificationContent(); content.title = notice.title
            content.body = "Open StudyFlow to review your academic updates."; content.sound = .default
            do { try await center.add(UNNotificationRequest(identifier: notice.id, content: content, trigger: nil)); notified.insert(notice.id) } catch { break }
        }
        UserDefaults.standard.set(Array(notified.suffix(1000)), forKey: "deliveredNotices")
    }
}
final class AppDelegate: NSObject, NSApplicationDelegate {
    var model: AppModel?
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
    func applicationWillTerminate(_ notification: Notification) { MainActor.assumeIsolated { model?.stop() } }
}
@main struct StudyFlowApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var delegate
    @StateObject private var model = AppModel()
    @Environment(\.openWindow) private var openWindow
    var body: some Scene {
        WindowGroup("StudyFlow", id: "main") {
            ContentView().environmentObject(model).frame(minWidth: 1000, minHeight: 700)
                .task { delegate.model = model; await model.start() }
        }.defaultSize(width: 1280, height: 840)
        .commands { CommandGroup(after: .newItem) { Button("Refresh StudyFlow") { Task { await model.refresh() } }.keyboardShortcut("r") } }
        MenuBarExtra("StudyFlow", systemImage: "book.closed.fill") {
            Text("\(model.state?.stats.incomplete ?? 0) assessments to complete")
            Button("Show StudyFlow") { openWindow(id: "main"); NSApp.activate(ignoringOtherApps: true) }
            Button("Sync Now") { Task { await model.mutate("sync") } }
            Divider()
            Button("Quit StudyFlow") { NSApp.terminate(nil) }.keyboardShortcut("q")
        }
    }
}
