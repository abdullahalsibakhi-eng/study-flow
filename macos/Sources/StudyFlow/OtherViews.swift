import SwiftUI
import UserNotifications
import ServiceManagement
import AppKit

struct CalendarView: View {
    @EnvironmentObject var model: AppModel
    var state: AppState
    @Binding var selected: Assessment?
    @ViewState private var anchor = Date()
    @ViewState private var mode = "Month"
    var cal: Calendar { var c = Calendar.current; c.timeZone = TimeZone(identifier: state.preferences.zone) ?? .current; return c }
    var days: [Date] {
        let unit: Calendar.Component = mode == "Month" ? .month : .weekOfYear
        guard let interval = cal.dateInterval(of: unit, for: anchor) else { return [] }
        let count = mode == "Month" ? cal.range(of: .day, in: .month, for: anchor)!.count : 7
        return (0..<count).compactMap { cal.date(byAdding: .day, value: $0, to: interval.start) }
    }
    var body: some View {
        HStack {
            Text(anchor.formatted(.dateTime.month(.wide).year())).font(.title2.bold()); Spacer()
            Button { move(-1) } label: { Image(systemName: "chevron.left") }.accessibilityLabel("Previous period")
            Button("Today") { anchor = Date() }
            Button { move(1) } label: { Image(systemName: "chevron.right") }.accessibilityLabel("Next period")
            Picker("View", selection: $mode) { Text("Month").tag("Month"); Text("Week").tag("Week"); Text("Agenda").tag("Agenda") }.pickerStyle(.segmented).frame(width: 240)
        }
        if mode != "Agenda" {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 7), spacing: 8) {
                let labels = Array(cal.shortWeekdaySymbols[(cal.firstWeekday-1)...]) + Array(cal.shortWeekdaySymbols[..<(cal.firstWeekday-1)])
                ForEach(labels, id: \.self) { Text($0).font(.caption.bold()).foregroundStyle(.secondary) }
                if mode == "Month", let first = days.first {
                    let offset = (cal.component(.weekday, from: first)-cal.firstWeekday+7)%7
                    ForEach(0..<offset, id: \.self) { _ in Color.clear.frame(height: 115) }
                }
                ForEach(days, id: \.self) { day in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(day.formatted(.dateTime.day())).font(.caption.bold()).foregroundStyle(cal.isDateInToday(day) ? .teal : .primary)
                        ForEach(tasks(day).prefix(3)) { a in Button { selected = a } label: { Text(a.title).font(.system(size: 10, weight: .medium)).lineLimit(2).frame(maxWidth: .infinity, alignment: .leading).padding(4).background(courseColor(a.courseId).opacity(0.12), in: RoundedRectangle(cornerRadius: 4)) }.buttonStyle(.plain) }
                        let sessions = state.sessions.filter { date($0.start).map { cal.isDate($0, inSameDayAs: day) } ?? false }
                        if !sessions.isEmpty { Text("\(sessions.count) study sessions").font(.system(size: 9)).foregroundStyle(.secondary) }
                        if tasks(day).count > 3 { Text("+\(tasks(day).count-3) more").font(.caption2).foregroundStyle(.secondary) }
                        Spacer(minLength: 0)
                    }.padding(8).frame(maxWidth: .infinity, minHeight: 115, alignment: .topLeading).background(Color(nsColor: .controlBackgroundColor), in: RoundedRectangle(cornerRadius: 8)).onTapGesture { anchor = day; mode = "Agenda" }
                }
            }
        } else {
            DatePicker("Agenda date", selection: $anchor, displayedComponents: .date)
            Card {
                Text(anchor.formatted(date: .complete, time: .omitted)).font(.headline)
                if tasks(anchor).isEmpty { Text("No assessment deadlines on this day.").foregroundStyle(.secondary) }
                ForEach(tasks(anchor)) { a in Button { selected = a } label: { HStack { Text(a.title); Spacer(); Text(a.deadline!.formatted(date: .omitted, time: .shortened)).foregroundStyle(.secondary) } }.buttonStyle(.plain) }
                ForEach(state.sessions.filter { date($0.start).map { cal.isDate($0, inSameDayAs: anchor) } ?? false }) { session in Label("\(date(session.start)?.formatted(date: .omitted, time: .shortened) ?? "") · \(session.title)", systemImage: "book").foregroundStyle(.teal) }
            }
        }
    }
    func tasks(_ day: Date) -> [Assessment] { state.assessments.filter { a in !a.missing && a.deadline.map { cal.isDate($0, inSameDayAs: day) } ?? false }.sorted { $0.deadline! < $1.deadline! } }
    func move(_ value: Int) { anchor = cal.date(byAdding: mode == "Month" ? .month : mode == "Week" ? .weekOfYear : .day, value: value, to: anchor)! }
    func courseColor(_ id: String) -> Color { let colors: [Color] = [.teal,.blue,.purple,.orange,.pink]; return colors[(state.courses.firstIndex(where: { $0.id == id }) ?? 0)%colors.count] }
}
struct AssistantView: View {
    @EnvironmentObject var model: AppModel
    @ViewState private var question = ""
    @ViewState private var messages: [(id: UUID, user: Bool, text: String)] = []
    @ViewState private var thinking = false
    var body: some View {
        Card {
            HStack { Label("Your study companion", systemImage: "sparkles").font(.headline); Spacer(); Button("Clear conversation") { messages = [] }.disabled(thinking) }
            Text("Local answers include deadline counts, course workload and feasible plan previews. Optional cloud AI receives your question, calculated answer, up to 12 priority summaries and 12 upcoming sessions, including course codes and times. Notes, source descriptions and grades are excluded. Responses cannot change your data.").font(.caption).foregroundStyle(.secondary)
            Label(model.state?.assistantEnabled == true ? "Cloud AI enabled · local fallback available" : "Local planning assistant · no cloud requests", systemImage: model.state?.assistantEnabled == true ? "cloud" : "desktopcomputer").font(.caption).foregroundStyle(.teal)
            HStack {
                ForEach(["How many assessments are due this week?", "Make a plan for tomorrow", "I only have three hours today"], id: \.self) { prompt in
                    Button(prompt) { question = prompt; ask() }.disabled(thinking)
                }
            }
            if messages.isEmpty { empty("Where would you like to start?", "Ask what to focus on, or explore why an assessment ranks higher.") }
            ForEach(messages, id: \.id) { message in
                VStack(alignment: .leading, spacing: 6) { Text(message.user ? "YOU" : "STUDYFLOW").font(.caption2.bold()).foregroundStyle(.teal); Text(message.text).textSelection(.enabled).font(.body) }.padding(16).frame(maxWidth: .infinity, alignment: .leading).background(.primary.opacity(message.user ? 0.025 : 0.05), in: RoundedRectangle(cornerRadius: 10))
            }
            if thinking { ProgressView("Thinking about your workload…").controlSize(.small) }
            HStack { TextField("What should I study tonight?", text: $question).textFieldStyle(.roundedBorder).onSubmit { ask() }; Button("Ask", systemImage: "arrow.up") { ask() }.buttonStyle(.borderedProminent).disabled(question.isEmpty || thinking) }
            Text("No conversation is saved to disk. Each request is independent. Provider charges may apply; responses are limited to 700 tokens.").font(.caption2).foregroundStyle(.secondary)
        }
    }
    func ask() {
        guard !question.isEmpty && !thinking else { return }
        let q = question; question = ""; thinking = true; messages.append((UUID(),true,q))
        Task {
            defer { thinking = false }
            do { let answer = try JSONDecoder().decode(Answer.self, from: await model.request("assistant", body: ["question": q])); messages.append((UUID(),false,answer.text+"\n\n"+answer.notice)) }
            catch { messages.append((UUID(),false,"The assistant is unavailable. Your dashboard and planner continue to work.")) }
        }
    }
}
struct NotificationsView: View {
    @EnvironmentObject var model: AppModel
    var state: AppState
    var body: some View {
        Card {
            HStack { Text("Activity & reminders").font(.headline); Spacer(); Button("Mark all read") { Task { await model.mutate("notices/read") } } }
            if state.notices.isEmpty { empty("You’re all caught up", "New assessments, deadline changes and study reminders will appear here.") }
            ForEach(state.notices) { notice in
                HStack(alignment: .top, spacing: 14) { Circle().fill(notice.read ? .clear : .teal).frame(width: 7, height: 7).padding(.top, 5); VStack(alignment: .leading, spacing: 5) { Text(notice.title).font(.headline); Text(notice.body).foregroundStyle(.secondary); Text(date(notice.created)?.formatted(date: .abbreviated, time: .shortened) ?? "").font(.caption2).foregroundStyle(.tertiary) }; Spacer() }.padding(.vertical, 8)
                Divider()
            }
        }
    }
}
struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @AppStorage("appearance") private var appearance = "system"
    @ViewState var preferences: Preferences
    @ViewState private var host = "https://brightspace.carleton.ca"
    @ViewState private var lpVersion = "1.49"
    @ViewState private var leVersion = "1.82"
    @ViewState private var accessToken = ""
    @ViewState private var clientID = ""
    @ViewState private var clientSecret = ""
    @ViewState private var scopes = "enrollment:own_enrollment:read dropbox:folders:read quizzing:quizzes:read"
    @ViewState private var endpoint = ""
    @ViewState private var aiModel = ""
    @ViewState private var apiKey = ""
    @ViewState private var consent = false
    @ViewState private var message = ""
    @ViewState private var login = SMAppService.mainApp.status == .enabled
    var body: some View {
        Card {
            Label("Study rhythm", systemImage: "clock").font(.headline)
            Picker("Appearance", selection: $appearance) { Text("System").tag("system"); Text("Light").tag("light"); Text("Dark").tag("dark") }.pickerStyle(.segmented)
            Form {
                TextField("Time zone", text: $preferences.zone)
                Stepper("Start at \(preferences.startHour):00", value: $preferences.startHour, in: 0...23)
                Stepper("Finish at \(preferences.endHour):00", value: $preferences.endHour, in: 1...24)
                Stepper("Session: \(preferences.sessionMinutes) minutes", value: $preferences.sessionMinutes, in: 15...180, step: 5)
                Stepper("Break: \(preferences.breakMinutes) minutes", value: $preferences.breakMinutes, in: 0...60, step: 5)
                HStack { Text("Study days"); Spacer(); ForEach(1...7, id: \.self) { day in Button(["M","T","W","T","F","S","S"][day-1]) { if preferences.days.contains(day) { preferences.days.removeAll { $0 == day } } else { preferences.days.append(day) } }.tint(preferences.days.contains(day) ? .teal : .gray).buttonStyle(.bordered).accessibilityLabel(["Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday"][day-1]) } }
                Stepper("Sync every \(preferences.syncMinutes) minutes", value: $preferences.syncMinutes, in: 5...1440, step: 5)
                Toggle("Enable private macOS notifications", isOn: $preferences.notifications)
                Stepper("Quiet hours begin: \(preferences.quietStart):00", value: $preferences.quietStart, in: 0...23)
                Stepper("Quiet hours end: \(preferences.quietEnd):00", value: $preferences.quietEnd, in: 0...23)
            }.textFieldStyle(.roundedBorder)
            Button("Save Preferences") {
                Task {
                    do {
                        if preferences.notifications {
                            let allowed = try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert,.sound,.badge])
                            if !allowed { preferences.notifications = false; message = "Notifications were not authorized. Enable them in macOS System Settings." }
                        }
                        if await model.mutate("preferences", try object(preferences)) { message = "Preferences saved. Your weekly plan has been recalculated." }
                    } catch { model.error = error.localizedDescription }
                }
            }.buttonStyle(.borderedProminent)
        }
        Card {
            Label("Brightspace connection", systemImage: "link").font(.headline)
            Text(model.oauthStatus).font(.caption).foregroundStyle(.secondary)
            Text("Only have a student login? Ask your university Brightspace administrator to register StudyFlow and provide an approved client ID and secret. Your student password cannot enable this connection. You can use Import Calendar without API access. Institutional API authorization is required. Register a personal authorization-code app with the exact redirect http://127.0.0.1:18744/oauth/callback and approved read scopes. Enable refresh tokens in the registration for background access. Credentials and rotated tokens are stored in Keychain. Never enter your university password here.").font(.callout).foregroundStyle(.secondary)
            Form { TextField("Institution HTTPS URL", text: $host); TextField("LP API version", text: $lpVersion); TextField("LE API version", text: $leVersion); SecureField("Authorized OAuth access token", text: $accessToken) }.textFieldStyle(.roundedBorder)
            Form {
                TextField("Registered client ID", text: $clientID)
                SecureField("Registered personal-app client secret", text: $clientSecret)
                TextField("Approved scopes", text: $scopes)
            }.textFieldStyle(.roundedBorder)
            Button("Sign In with Registered App") {
                Task {
                    do {
                        let data = try await model.request("oauth/start", body: ["host":host,"lpVersion":lpVersion,"leVersion":leVersion,"clientId":clientID,"clientSecret":clientSecret,"scopes":scopes])
                        let payload = try JSONSerialization.jsonObject(with: data) as? [String: String]
                        if let link = payload?["url"], let url = URL(string: link) { NSWorkspace.shared.open(url); clientSecret = ""; message = "Complete authorization in your browser, then check Brightspace sync status." }
                    } catch { model.error = error.localizedDescription }
                }
            }.disabled(clientID.isEmpty || clientSecret.isEmpty)
            Text("Advanced alternative: connect an already-authorized access token above. Token-only connections cannot refresh automatically.").font(.caption).foregroundStyle(.secondary)
            HStack {
                Button("Connect Authorized Token") { Task { if await model.saveConnection("brightspace", path: "brightspace/connect", payload: ["host":host,"lpVersion":lpVersion,"leVersion":leVersion,"accessToken":accessToken]) { accessToken = ""; message = "Connection configured. Check sync status for authorization results." } } }.disabled(accessToken.isEmpty)
                Button("Disconnect", role: .destructive) { Task { await model.disconnect("brightspace", path: "brightspace/disconnect"); message = "Brightspace disconnected; local records retained." } }
            }
            Link("Brightspace API authorization requirements ↗", destination: URL(string: "https://docs.valence.desire2learn.com/basic/oauth2.html")!).font(.caption)
        }
        Card {
            Label("Optional cloud assistant", systemImage: "sparkles").font(.headline)
            Text("Use an HTTPS provider compatible with the chat-completions request format. Your question and selected workload context are sent only when you press Ask. Provider retention and fees apply. The key stays in Keychain and is passed only to the local backend.").font(.callout).foregroundStyle(.secondary)
            Form { TextField("Full chat-completions endpoint URL", text: $endpoint); TextField("Model identifier", text: $aiModel); SecureField("API key", text: $apiKey); Toggle("I consent to sending this workload context to my provider", isOn: $consent) }.textFieldStyle(.roundedBorder)
            HStack { Button("Enable Cloud AI") { Task { if await model.saveConnection("ai", path: "assistant/configure", payload: ["enabled":true,"endpoint":endpoint,"model":aiModel,"key":apiKey]) { apiKey = ""; message = "Cloud AI enabled. Requests are sent only when you ask a question." } } }.disabled(!consent || endpoint.isEmpty || aiModel.isEmpty || apiKey.isEmpty)
                Button("Disable & Remove Key", role: .destructive) { Task { await model.disconnect("ai", path: "assistant/configure"); message = "Cloud AI disabled. Local recommendations remain available." } }
            }
        }
        Card {
            Label("Background operation", systemImage: "menubar.rectangle").font(.headline)
            Text("Closing the window keeps StudyFlow in the menu bar. Quit StudyFlow to stop its Java process and synchronization. Polling pauses during sleep and cannot run while logged out. Login startup requires a properly installed app bundle and may need macOS approval.").foregroundStyle(.secondary)
            Toggle("Open StudyFlow at login", isOn: $login).onChange(of: login) { _, enabled in
                do { if enabled { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }; message = "Login setting updated. Check System Settings → General → Login Items if approval is needed." }
                catch { model.error = "Login startup could not be changed: \(error.localizedDescription)" }
            }
        }
        if !message.isEmpty { Label(message, systemImage: "info.circle").foregroundStyle(.teal).textSelection(.enabled) }
    }
}
