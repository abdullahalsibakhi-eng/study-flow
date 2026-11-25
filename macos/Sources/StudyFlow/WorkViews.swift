import SwiftUI
import Charts

struct CoursesView: View {
    @EnvironmentObject var model: AppModel
    @ViewState private var pendingRemoval: Course?
    @ViewState private var showRemoval = false
    var state: AppState
    @Binding var selected: Assessment?
    var body: some View {
        if state.courses.isEmpty { Card { empty("Welcome to your workspace", "Add your first course using the button above. Brightspace connection is available in Settings when you have institution-approved API access.") } }
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 330))], spacing: 20) {
            ForEach(state.courses) { course in
                Card {
                    HStack { Image(systemName: "books.vertical.fill").font(.title2).foregroundStyle(.teal); Spacer(); Text(course.source == "manual" ? "Manual" : "Brightspace").font(.caption).foregroundStyle(.secondary) }
                    Text(course.code).font(.title2.bold()); Text(course.name).font(.headline)
                    if let term = course.term, !term.isEmpty { Text(term).font(.caption).foregroundStyle(.secondary) }
                    let tasks = state.assessments.filter { $0.courseId == course.id }
                    let complete = tasks.filter { $0.status == "COMPLETED" || $0.status == "SUBMITTED" }.count
                    ProgressView(value: Double(complete), total: Double(max(1,tasks.count)))
                    Text("\(complete) of \(tasks.count) assessments complete").font(.caption).foregroundStyle(.secondary)
                    ForEach(tasks.filter(\.active).sorted { ($0.deadline ?? .distantFuture) < ($1.deadline ?? .distantFuture) }.prefix(3)) { a in Button(a.title) { selected = a }.buttonStyle(.link).lineLimit(2) }
                    Button("Remove Course", role: .destructive) {
                        pendingRemoval = course
                        showRemoval = true
                    }.disabled(model.busy)
                    if let link = course.url, let url = URL(string: link), url.scheme == "https" { Link("Open Brightspace ↗", destination: url).font(.caption) }
                }
            }
        }
        .alert("Remove course and its work?", isPresented: $showRemoval, presenting: pendingRemoval) { course in
            Button("Cancel", role: .cancel) { pendingRemoval = nil }
            Button("Remove Course", role: .destructive) {
                Task {
                    if await model.mutate("courses/remove", ["id": course.id, "confirm": true]) {
                        if selected?.courseId == course.id { selected = nil }
                        pendingRemoval = nil
                    }
                }
            }
        } message: { course in
            Text("Remove \(course.code) from StudyFlow? Its assignments, quizzes, notes, grades, study sessions, and study history will be permanently removed locally. This cannot be undone. Brightspace records are unchanged. Removed imported courses will stay excluded from future syncs.")
        }
    }
}
struct AssessmentsView: View {
    @EnvironmentObject var model: AppModel
    var state: AppState
    @Binding var selected: Assessment?
    @ViewState private var search = ""
    @ViewState private var course = "all"
    @ViewState private var kind = "all"
    @ViewState private var status = "active"
    @ViewState private var sort = "priority"
    var filtered: [Assessment] {
        let scores = Dictionary(uniqueKeysWithValues: state.ranked.map { ($0.id,$0.score) })
        return state.assessments.filter { a in
            (search.isEmpty || a.title.localizedCaseInsensitiveContains(search) || a.notes.localizedCaseInsensitiveContains(search)) && (course == "all" || a.courseId == course) && (kind == "all" || a.kind == kind) && (status == "all" || (status == "active" ? a.active : a.status == "COMPLETED" || a.status == "SUBMITTED"))
        }.sorted { a,b in
            switch sort { case "due": return (a.deadline ?? .distantFuture) < (b.deadline ?? .distantFuture)
            case "weight": return (a.weight ?? -1) > (b.weight ?? -1)
            case "work": return a.remaining > b.remaining
            default: return (scores[a.id] ?? -1) > (scores[b.id] ?? -1) }
        }
    }
    var body: some View {
        HStack {
            TextField("Search assessments and notes", text: $search).textFieldStyle(.roundedBorder).frame(minWidth: 180)
            Picker("Course", selection: $course) { Text("All courses").tag("all"); ForEach(state.courses) { Text($0.code).tag($0.id) } }.labelsHidden().frame(width: 150)
            Picker("Type", selection: $kind) { Text("All types").tag("all"); ForEach(kinds, id: \.self) { Text($0.capitalized).tag($0) } }.labelsHidden().frame(width: 140)
            Picker("Status", selection: $status) { Text("Active").tag("active"); Text("Completed").tag("complete"); Text("All").tag("all") }.labelsHidden().frame(width: 120)
            Picker("Sort", selection: $sort) { Text("Priority").tag("priority"); Text("Due date").tag("due"); Text("Weight").tag("weight"); Text("Workload").tag("work") }.frame(width: 160)
        }
        Card {
            if filtered.isEmpty { empty("Nothing here yet", "Add an assessment or adjust your filters.") }
            LazyVStack(spacing: 0) {
                ForEach(filtered) { a in
                    Button { selected = a } label: {
                        HStack(spacing: 16) {
                            Image(systemName: a.active ? "circle" : "checkmark.circle.fill").foregroundStyle(a.active ? Color.secondary : Color.teal)
                            VStack(alignment: .leading, spacing: 5) { Text(a.title).font(.headline); Text("\(model.courseName(a.courseId)) · \(a.kind.capitalized) · \(sourceLabel(a.source))\(a.missing ? " · No longer visible" : "")").font(.caption).foregroundStyle(.secondary) }
                            Spacer()
                            VStack(alignment: .trailing, spacing: 5) { Text(a.deadline?.formatted(date: .abbreviated, time: .shortened) ?? "No deadline").font(.callout); Text("\(a.remaining, specifier: "%.1f") h left · \(a.weight.map { String(format: "%.1f%% weight", $0) } ?? "Weight unknown")").font(.caption).foregroundStyle(.secondary) }
                            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                        }.padding(.vertical, 15).contentShape(Rectangle())
                    }.buttonStyle(.plain)
                    Divider()
                }
            }
        }
    }
}
let kinds = ["ASSIGNMENT", "LAB", "QUIZ", "MIDTERM", "FINAL", "PROJECT", "PRESENTATION", "OTHER"]
struct CourseEditor: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) var dismiss
    @ViewState private var code = ""
    @ViewState private var name = ""
    @ViewState private var term = ""
    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            Text("Add a course").font(.title2.bold())
            Form { TextField("Course code", text: $code); TextField("Course name", text: $name); TextField("Academic term", text: $term) }.textFieldStyle(.roundedBorder)
            if let error = model.error { Text(error).foregroundStyle(.red).font(.caption) }
            HStack { Button("Cancel") { dismiss() }.keyboardShortcut(.cancelAction); Spacer(); Button("Add Course") { Task { if await model.mutate("courses", ["code": code, "name": name, "term": term]) { dismiss() } } }.buttonStyle(.borderedProminent).disabled(code.trimmingCharacters(in: .whitespaces).isEmpty || name.trimmingCharacters(in: .whitespaces).isEmpty || model.busy).keyboardShortcut(.defaultAction) }
        }.padding(30).frame(width: 470)
    }
}
struct AssessmentEditor: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) var dismiss
    var assessment: Assessment?
    @ViewState private var title = ""
    @ViewState private var course = ""
    @ViewState private var kind = "ASSIGNMENT"
    @ViewState private var status = "NOT_STARTED"
    @ViewState private var notes = ""
    @ViewState private var hasDue = true
    @ViewState private var due = Date().addingTimeInterval(86400)
    @ViewState private var estimate = 2.0
    @ViewState private var worked = 0.0
    @ViewState private var weight = ""
    @ViewState private var earned = ""
    @ViewState private var possible = ""
    @ViewState private var localError: String?
    var remote: Bool { assessment != nil && assessment?.source != "manual" }
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack { Text(assessment == nil ? "New assessment" : "Assessment details").font(.title2.bold()); Spacer(); if remote { Label(sourceLabel(assessment?.source ?? "manual"), systemImage: "link").font(.caption).foregroundStyle(.secondary) } }
            ScrollView {
                Form {
                    Section("Assessment") {
                        TextField("Title", text: $title).disabled(remote)
                        Picker("Course", selection: $course) { ForEach(model.state?.courses ?? []) { Text($0.code).tag($0.id) } }.disabled(assessment != nil)
                        Picker("Type", selection: $kind) { ForEach(kinds, id: \.self) { Text($0.capitalized).tag($0) } }.disabled(remote)
                        Toggle("Has a deadline", isOn: $hasDue).disabled(remote)
                        if hasDue { DatePicker("Due", selection: $due).disabled(remote) }
                    }
                    Section("Your progress") {
                        Picker("Status", selection: $status) { Text("Not started").tag("NOT_STARTED"); Text("In progress").tag("IN_PROGRESS"); Text("Completed").tag("COMPLETED"); Text("Submitted").tag("SUBMITTED") }
                        TextField("Estimated hours", value: $estimate, format: .number)
                        TextField("Hours completed", value: $worked, format: .number)
                        TextField("Final course weight (%) — optional", text: $weight)
                        TextField("Earned points — optional", text: $earned)
                        TextField("Possible points — optional", text: $possible)
                        Text("Points are never treated as final-course weights. Estimates for imported work default to 2 hours; adjust them here.").font(.caption).foregroundStyle(.secondary)
                    }
                    if let id = assessment?.id, let details = model.state?.remoteDetails[id] {
                        Section("From " + sourceLabel(assessment?.source ?? "manual")) {
                            if !details.description.isEmpty { Text(details.description).textSelection(.enabled) }
                            if let from = date(details.availableFrom) { LabeledContent("Available from", value: from.formatted(date: .abbreviated, time: .shortened)) }
                            if let until = date(details.availableUntil) { LabeledContent("Available until", value: until.formatted(date: .abbreviated, time: .shortened)) }
                            if let points = details.possiblePoints { Text("Source points: \(points, specifier: "%.1f") · not a final-course weight") }
                            Text(assessment?.source == "calendar" ? "Imported source text. Re-import the same UID to update source fields; your notes and estimates are preserved." : "Availability is source information, not a personalized deadline. Check Brightspace for accommodations and access restrictions.").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Section("Personal notes") { TextEditor(text: $notes).frame(height: 95) }
                    if let ranked = model.state?.ranked.first(where: { $0.id == assessment?.id }) {
                        Section("Why this priority · \(ranked.score, specifier: "%.1f")/100") { ForEach(ranked.reasons, id: \.self) { Text($0).font(.callout).foregroundStyle(.secondary) } }
                    }
                    if let link = assessment?.url, let url = URL(string: link), url.scheme == "https" { Link("Open original assessment ↗", destination: url) }
                }.formStyle(.grouped)
            }
            if let message = localError ?? model.error { Text(message).font(.caption).foregroundStyle(.red) }
            HStack { Button("Cancel") { dismiss() }.keyboardShortcut(.cancelAction); Spacer(); Button("Save Assessment") { save() }.buttonStyle(.borderedProminent).disabled(title.trimmingCharacters(in: .whitespaces).isEmpty || course.isEmpty || model.busy).keyboardShortcut(.defaultAction) }
        }.padding(24).frame(width: 640, height: 730)
        .onAppear {
            course = assessment?.courseId ?? model.state?.courses.first?.id ?? ""
            if let a = assessment { title = a.title; kind = a.kind; status = a.status; notes = a.notes; hasDue = a.deadline != nil; due = a.deadline ?? Date(); estimate = a.estimatedHours; worked = a.workedHours; weight = a.weight.map(String.init(describing:)) ?? ""; earned = a.earned.map(String.init(describing:)) ?? ""; possible = a.possible.map(String.init(describing:)) ?? "" }
        }
    }
    func save() {
        for value in [weight, earned, possible] where !value.isEmpty { if Double(value) == nil { localError = "Grade fields must be numbers or left blank."; return } }
        let a = Assessment(id: assessment?.id ?? UUID().uuidString, courseId: course, title: title, kind: kind, due: hasDue ? iso(due) : nil, weight: Double(weight), earned: Double(earned), possible: Double(possible), estimatedHours: estimate, workedHours: worked, status: status, notes: notes, source: assessment?.source ?? "manual", url: assessment?.url ?? "", missing: assessment?.missing ?? false)
        Task { do { if await model.mutate("assessments", try object(a)) { dismiss() } } catch { localError = error.localizedDescription } }
    }
}
struct PlannerView: View {
    @EnvironmentObject var model: AppModel
    var state: AppState
    var body: some View {
        HStack { Label("\(state.preferences.sessionMinutes)-minute sessions · \(state.preferences.breakMinutes)-minute breaks", systemImage: "clock").foregroundStyle(.secondary); Spacer(); Button("Plan Today") { Task { await model.mutate("plan", ["days": 1]) } }; Button("Plan This Week") { Task { await model.mutate("plan", ["days": 7]) } }.buttonStyle(.borderedProminent) }.disabled(model.busy)
        if !state.planConflicts.isEmpty {
            Card {
                Label("Pinned sessions need attention", systemImage: "exclamationmark.triangle").font(.headline).foregroundStyle(.orange)
                ForEach(state.planConflicts) { conflict in
                    VStack(alignment: .leading, spacing: 4) { Text(conflict.title).fontWeight(.medium); Text(conflict.reason).font(.caption).foregroundStyle(.secondary) }
                }
                Text("Unpin a conflicting session to let StudyFlow move or remove it. Preserved reservations are not silently changed.").font(.caption)
            }
        }
        if !state.unscheduled.isEmpty {
            Card { Label("Some work needs more time", systemImage: "exclamationmark.circle").foregroundStyle(.orange).font(.headline)
                ForEach(state.unscheduled) { item in VStack(alignment: .leading, spacing: 4) { Text("\(item.title) · \(item.hours, specifier: "%.1f") h unscheduled").fontWeight(.medium); Text(item.reason).font(.caption).foregroundStyle(.secondary) } }
            }
        }
        Card {
            Text("Your study sessions").font(.headline)
            if state.sessions.isEmpty { empty("Build a little momentum", "Add assessments and set your availability in Settings, then generate a plan.") }
            ForEach(state.sessions) { s in
                HStack(spacing: 18) {
                    VStack(alignment: .leading) { Text(date(s.start)?.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day()) ?? "").font(.caption.bold()); Text("\(date(s.start)?.formatted(date: .omitted, time: .shortened) ?? "") – \(date(s.end)?.formatted(date: .omitted, time: .shortened) ?? "")").font(.caption).foregroundStyle(.secondary) }.frame(width: 190, alignment: .leading)
                    Rectangle().fill(.teal).frame(width: 3, height: 35)
                    Text(s.title).font(.headline); Spacer()
                    Button { Task { await model.mutate("pin", ["id": s.id, "pinned": !s.pinned]) } } label: { Label(s.pinned ? "Pinned" : "Pin", systemImage: s.pinned ? "pin.fill" : "pin") }.help("Pinned sessions are preserved when the plan changes")
                }.padding(.vertical, 7)
                Divider()
            }
        }
        if !state.sessionHistory.isEmpty {
            Card {
                Text("Recent study history").font(.headline)
                Text("Elapsed sessions are unconfirmed until you log the work. Unconfirmed effort is included in replanning.").font(.caption).foregroundStyle(.secondary)
                ForEach(state.sessionHistory.prefix(30)) { item in
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(item.session.title).fontWeight(.medium)
                            Text(date(item.session.start)?.formatted(date: .abbreviated, time: .shortened) ?? "").font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        if item.outcome == "COMPLETED" { Label("\(item.creditedHours, specifier: "%.2f") h logged", systemImage: "checkmark.circle").foregroundStyle(.teal) }
                        else { Button("Log completed work") { Task { await model.mutate("sessions/complete", ["id": item.id]) } }.disabled(model.busy).help("Credit the scheduled session duration, capped at remaining estimated work") }
                    }.padding(.vertical, 6)
                }
            }
        }
    }
}
struct GradesView: View {
    @EnvironmentObject var model: AppModel
    var state: AppState
    var body: some View {
        if state.grades.isEmpty { Card { empty("Your grades, in context", "Add courses and enter known grades and final-course weights in assessment details.") } }
        ForEach(state.grades) { grade in
            Card {
                Text(model.courseName(grade.courseId)).font(.title2.bold())
                HStack(spacing: 40) {
                    VStack(alignment: .leading) { Text(grade.averageOnGradedWork.map { String(format: "%.1f%%", $0) } ?? "—").font(.largeTitle.bold()); Text("Average on graded work").font(.caption).foregroundStyle(.secondary) }
                    VStack(alignment: .leading) { Text("\(grade.earnedContribution, specifier: "%.1f") / 100").font(.title2); Text("Earned final-course percentage points").font(.caption).foregroundStyle(.secondary) }
                }
                let tasks = state.assessments.filter { $0.courseId == grade.courseId && $0.weight != nil && !$0.missing }
                if !tasks.isEmpty {
                    Chart(tasks) { a in BarMark(x: .value("Weight", a.weight ?? 0), y: .value("Assessment", a.title)).foregroundStyle(.teal) }.frame(height: CGFloat(max(100,min(tasks.count*35,360)))).chartXAxisLabel("Explicit final-course weight (%)")
                }
                Text(grade.note).font(.caption).foregroundStyle(.secondary)
                if let required = grade.requiredOnRemaining { Text(required > 100 ? "An 80% target is not reachable under these assumptions." : required <= 0 ? "An 80% target is already secured under these assumptions." : String(format: "For an 80%% final grade, you need %.1f%% on the remaining weight.", required)).font(.callout) }
            }
        }
    }
}
