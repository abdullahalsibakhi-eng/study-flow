import SwiftUI
import UniformTypeIdentifiers

private struct ImportPreview: Decodable {
    var assessments: [Assessment]
    var warnings: [String]
}
struct CalendarImportView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @ViewState private var course = ""
    @ViewState private var content = ""
    @ViewState private var filename = "No file selected"
    @ViewState private var choosingFile = false
    @ViewState private var preview: ImportPreview?
    @ViewState private var confirmed = false
    @ViewState private var working = false
    @ViewState private var error: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("Import calendar deadlines").font(.title2.bold())
            Text("Use an authorized .ics export when direct Brightspace API access is unavailable. Files stay on this Mac. Review the dates before importing.").foregroundStyle(.secondary)
            Picker("Destination course", selection: $course) { ForEach(model.state?.courses ?? []) { Text($0.code).tag($0.id) } }.disabled(working)
                .onChange(of: course) { _, _ in preview = nil; confirmed = false }
            HStack { Button("Choose .ics File") { choosingFile = true }.disabled(working); Text(filename).font(.caption).foregroundStyle(.secondary); Spacer(); Button("Preview") { loadPreview() }.disabled(content.isEmpty || course.isEmpty || working) }
            if working { ProgressView().controlSize(.small) }
            if let preview {
                Text("\(preview.assessments.count) events to import or update").font(.headline)
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 12) {
                        ForEach(preview.assessments) { item in
                            VStack(alignment: .leading, spacing: 4) {
                                Text(item.title).fontWeight(.medium)
                                Text(item.deadline?.formatted(date: .complete, time: .shortened) ?? "No date").font(.caption).foregroundStyle(.secondary)
                            }
                            Divider()
                        }
                    }
                }.frame(maxHeight: 250)
                ForEach(preview.warnings, id: \.self) { Text($0).font(.caption).foregroundStyle(.secondary) }
                Toggle("Use these event start times as assessment deadlines", isOn: $confirmed)
            } else {
                Text("Supported: individual events with UID, SUMMARY and DTSTART; UTC, IANA time zones, floating local times, and all-day dates. Recurring or cancelled events require review and are rejected without importing anything.").font(.caption).foregroundStyle(.secondary)
            }
            if let error { Text(error).font(.caption).foregroundStyle(.red) }
            Spacer(minLength: 0)
            HStack {
                Button("Cancel") { dismiss() }.keyboardShortcut(.cancelAction)
                Spacer()
                Button("Import Reviewed Events") {
                    working = true
                    Task {
                        defer { working = false }
                        if await model.mutate("imports/calendar", ["courseId": course, "content": content, "confirmEventStartsAsDeadlines": confirmed]) { dismiss() }
                        else { error = model.error }
                    }
                }.buttonStyle(.borderedProminent).disabled(preview == nil || !confirmed || working)
            }
        }.padding(28).frame(width: 660, height: 650)
        .onAppear { course = model.state?.courses.first?.id ?? "" }
        .fileImporter(isPresented: $choosingFile, allowedContentTypes: [UTType(filenameExtension: "ics") ?? .data]) { result in
            do {
                let url = try result.get()
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                guard size <= 500_000 else { throw AppFailure(message: "Choose a calendar file smaller than 500 KB.") }
                let data = try Data(contentsOf: url)
                guard data.count <= 500_000, let text = String(data: data, encoding: .utf8) else { throw AppFailure(message: "Choose a UTF-8 calendar file smaller than 500 KB.") }
                content = text; filename = url.lastPathComponent; preview = nil; confirmed = false; error = nil
            } catch { self.error = error.localizedDescription }
        }
    }
    private func loadPreview() {
        working = true; error = nil; preview = nil; confirmed = false
        Task {
            defer { working = false }
            do { preview = try JSONDecoder().decode(ImportPreview.self, from: await model.request("imports/calendar/preview", body: ["courseId":course,"content":content])) }
            catch { self.error = error.localizedDescription }
        }
    }
}
