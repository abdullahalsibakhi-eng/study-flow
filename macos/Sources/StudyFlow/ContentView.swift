import SwiftUI
import Charts

enum Page: String, CaseIterable, Identifiable {
    case dashboard = "Dashboard", courses = "My Courses", assessments = "Assignments & Quizzes", planner = "Study Planner", calendar = "Calendar", grades = "Grade Insights", assistant = "AI Assistant", notifications = "Notifications", settings = "Settings"
    var id: String { rawValue }
    var icon: String { switch self { case .dashboard: "square.grid.2x2"; case .courses: "books.vertical"; case .assessments: "checklist"; case .planner: "sparkles"; case .calendar: "calendar"; case .grades: "chart.bar.xaxis"; case .assistant: "bubble.left.and.bubble.right"; case .notifications: "bell"; case .settings: "gearshape" } }
}
struct ContentView: View {
    @EnvironmentObject var model: AppModel
    @AppStorage("appearance") private var appearance = "system"
    @ViewState private var page: Page? = .dashboard
    @ViewState private var addCourse = false
    @ViewState private var importCalendar = false
    @ViewState private var addAssessment = false
    @ViewState private var selected: Assessment?
    var body: some View {
        NavigationSplitView {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 10) {
                    Image(systemName: "book.closed.fill").font(.title2).foregroundStyle(.teal)
                    VStack(alignment: .leading) { Text("StudyFlow").font(.title3.bold()); Text("SPACE TO FOCUS").font(.system(size: 9, weight: .semibold, design: .rounded)).tracking(1.5).foregroundStyle(.secondary) }
                }.padding(20).padding(.top, 8)
                List(Page.allCases, selection: $page) { item in Label(item.rawValue, systemImage: item.icon).padding(.vertical, 6).tag(item) }.listStyle(.sidebar)
                VStack(alignment: .leading, spacing: 8) {
                    Label(model.state?.sync.connection ?? "Starting…", systemImage: model.state?.sync.connection == "Connected" ? "checkmark.circle.fill" : "circle.dotted").font(.caption).foregroundStyle(.secondary)
                    Text("Your academic day, organized.").font(.caption2).foregroundStyle(.tertiary)
                }.padding(20)
            }.navigationSplitViewColumnWidth(min: 220, ideal: 235)
        } detail: {
            VStack(spacing: 0) {
                if let error = model.error {
                    HStack { Image(systemName: "exclamationmark.triangle"); Text(error).font(.callout); Spacer(); Button("Dismiss") { model.error = nil } }.padding(12).background(.orange.opacity(0.12))
                }
                if model.loading { Spacer(); ProgressView("Opening your workspace…"); Spacer() }
                else if model.startupFailed { ContentUnavailableView { Label("Backend unavailable", systemImage: "externaldrive.badge.exclamationmark") } description: { Text("Build the app and install Java 21 or later.") } actions: { Button("Retry") { Task { await model.start() } } } }
                else if let state = model.state {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 28) {
                            header
                            switch page ?? .dashboard {
                            case .dashboard: DashboardView(state: state, selected: $selected)
                            case .courses: CoursesView(state: state, selected: $selected)
                            case .assessments: AssessmentsView(state: state, selected: $selected)
                            case .planner: PlannerView(state: state)
                            case .calendar: CalendarView(state: state, selected: $selected)
                            case .grades: GradesView(state: state)
                            case .assistant: AssistantView()
                            case .notifications: NotificationsView(state: state)
                            case .settings: SettingsView(preferences: state.preferences)
                            }
                        }.padding(32).frame(maxWidth: 1350, alignment: .leading).frame(maxWidth: .infinity)
                    }.background(Color(nsColor: .windowBackgroundColor))
                } else { ContentUnavailableView("No connection", systemImage: "wifi.slash", description: Text("Reconnect to the local backend to access your saved workspace.")) }
            }
            .toolbar { ToolbarItemGroup { if model.busy { ProgressView().controlSize(.small) }; Button { Task { await model.mutate("sync") } } label: { Label("Sync Now", systemImage: "arrow.triangle.2.circlepath") }.disabled(model.state?.sync.canSync != true || model.busy) } }
        }
        .tint(.teal)
        .preferredColorScheme(appearance == "light" ? .light : appearance == "dark" ? .dark : nil)
        .sheet(isPresented: $importCalendar) { CalendarImportView() }
        .sheet(isPresented: $addCourse) { CourseEditor() }
        .sheet(isPresented: $addAssessment) { AssessmentEditor(assessment: nil) }
        .sheet(item: $selected) { AssessmentEditor(assessment: $0) }
    }
    var header: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 7) {
                Text(Date.now.formatted(.dateTime.weekday(.wide).month(.wide).day()).uppercased()).font(.system(size: 11, weight: .semibold)).tracking(1.5).foregroundStyle(.secondary)
                Text(page == .dashboard ? "Make room for your best work." : (page ?? .dashboard).rawValue).font(.system(size: 30, weight: .bold, design: .rounded))
                Text(subtitle).font(.callout).foregroundStyle(.secondary)
            }
            Spacer()
            if page == .courses || page == .assessments { Button("Import Calendar", systemImage: "square.and.arrow.down") { importCalendar = true }.disabled(model.state?.courses.isEmpty ?? true) }
            if page == .courses { Button { addCourse = true } label: { Label("Add Course", systemImage: "plus") }.buttonStyle(.borderedProminent) }
            else if page == .dashboard || page == .assessments { Button { addAssessment = true } label: { Label("Add Assessment", systemImage: "plus") }.buttonStyle(.borderedProminent).disabled(model.state?.courses.isEmpty ?? true) }
        }
    }
    var subtitle: String {
        switch page ?? .dashboard {
        case .dashboard: "A clear view of what matters next."
        case .courses: "One workspace for every course you’re taking."
        case .assessments: "Deadlines, progress, and the details that matter."
        case .planner: "A realistic plan, built around your availability."
        case .calendar: "See the shape of your week."
        case .grades: "Known grades and explicit weights. No invented predictions."
        case .assistant: "Thoughtful guidance, grounded in your workload."
        case .notifications: "Changes and reminders, all in one place."
        case .settings: "Make StudyFlow work for you."
        }
    }
}
struct Card<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View { VStack(alignment: .leading, spacing: 16) { content }.padding(22).frame(maxWidth: .infinity, alignment: .leading).background(Color(nsColor: .controlBackgroundColor), in: RoundedRectangle(cornerRadius: 16)).overlay(RoundedRectangle(cornerRadius: 16).stroke(.primary.opacity(0.06))) }
}
struct DashboardView: View {
    @EnvironmentObject var model: AppModel
    var state: AppState
    @Binding var selected: Assessment?
    var body: some View {
        HStack(spacing: 16) {
            metric("Active courses", "\(state.stats.courses)", "books.vertical", .teal)
            metric("Due this week", "\(state.stats.dueWeek)", "calendar", .blue)
            metric("Work remaining", String(format: "%.1f h", state.stats.hours), "clock", .indigo)
            metric("Completed", "\(state.stats.completed)", "checkmark.circle", .green)
        }
        HStack(alignment: .top, spacing: 20) {
            VStack(spacing: 20) {
                Card {
                    HStack { Label("Recommended focus", systemImage: "sparkles").font(.headline); Spacer(); Text("TODAY").font(.caption2.bold()).foregroundStyle(.teal) }
                    if let first = state.ranked.first {
                        Text(first.assessment.title).font(.title2.bold())
                        Text(first.reasons.prefix(3).joined(separator: " · ")).font(.callout).foregroundStyle(.secondary)
                        HStack { Text(model.courseName(first.assessment.courseId)).font(.caption.bold()); Spacer(); Button("View assessment") { selected = first.assessment }.buttonStyle(.bordered) }
                    } else { empty("A little space to breathe", "Add a course and assessment to start planning your work.") }
                }
                Card {
                    HStack { Text("Your priorities").font(.headline); Spacer(); Text("\(state.stats.incomplete) active").foregroundStyle(.secondary).font(.caption) }
                    if state.ranked.isEmpty { Text("Your priorities will appear here.").foregroundStyle(.secondary) }
                    ForEach(Array(state.ranked.prefix(8).enumerated()), id: \.element.id) { index, ranked in
                        Button { selected = ranked.assessment } label: {
                            HStack(alignment: .top, spacing: 14) {
                                Text(String(format: "%02d", index+1)).font(.system(.callout, design: .monospaced)).foregroundStyle(.tertiary).frame(width: 24)
                                VStack(alignment: .leading, spacing: 6) { Text(ranked.assessment.title).font(.headline).foregroundStyle(.primary); Text("\(model.courseName(ranked.assessment.courseId)) · \(ranked.assessment.kind.capitalized) · \(ranked.assessment.remaining, specifier: "%.1f") h left").font(.caption).foregroundStyle(.secondary) }
                                Spacer(); VStack(alignment: .trailing, spacing: 6) { Text(ranked.category).font(.caption.bold()).foregroundStyle(ranked.category == "High" ? .orange : .teal); Text("\(ranked.score, specifier: "%.1f") / 100").font(.caption2).foregroundStyle(.secondary) }
                            }.padding(.vertical, 9).contentShape(Rectangle())
                        }.buttonStyle(.plain)
                        if index < min(state.ranked.count,8)-1 { Divider() }
                    }
                }
            }.frame(maxWidth: .infinity)
            VStack(spacing: 20) {
                Card {
                    Text("At a glance").font(.headline)
                    summary("Due today", state.stats.dueToday)
                    summary("Overdue", state.stats.overdue)
                    summary("Incomplete", state.stats.incomplete)
                }
                Card {
                    Text("On the horizon").font(.headline)
                    ForEach(state.assessments.filter { $0.active && $0.deadline != nil }.sorted { $0.deadline! < $1.deadline! }.prefix(5)) { a in
                        Button { selected = a } label: { VStack(alignment: .leading, spacing: 5) { Text(a.title).font(.callout.weight(.medium)).lineLimit(2); Text(a.deadline!.formatted(date: .abbreviated, time: .shortened)).font(.caption).foregroundStyle(.secondary) }.frame(maxWidth: .infinity, alignment: .leading) }.buttonStyle(.plain)
                    }
                    if state.assessments.isEmpty { Text("No deadlines yet").foregroundStyle(.secondary) }
                }
                Card {
                    Label("Brightspace", systemImage: "arrow.triangle.2.circlepath").font(.headline)
                    Text(state.sync.connection).foregroundStyle(.secondary)
                    if let last = date(state.sync.lastSuccess) { Text("Last synced \(last.formatted(date: .abbreviated, time: .shortened))").font(.caption) }
                    if let next = date(state.sync.nextSync) { Text("Next check \(next.formatted(date: .omitted, time: .shortened))").font(.caption).foregroundStyle(.secondary) }
                    if !state.sync.error.isEmpty { Text(state.sync.error).font(.caption).foregroundStyle(.orange) }
                    if state.sync.connection == "Disconnected" { Text("Connect an authorized account in Settings, or add courses manually.").font(.caption).foregroundStyle(.secondary) }
                }
            }.frame(width: 280)
        }
    }
    func metric(_ title: String, _ value: String, _ icon: String, _ color: Color) -> some View {
        Card { Image(systemName: icon).foregroundStyle(color).font(.title3); Text(value).font(.system(size: 30, weight: .semibold, design: .rounded)); Text(title).font(.caption).foregroundStyle(.secondary) }
    }
    func summary(_ label: String, _ value: Int) -> some View { HStack { Text(label).foregroundStyle(.secondary); Spacer(); Text("\(value)").fontWeight(.semibold) } }
}
func empty(_ title: String, _ detail: String) -> some View { VStack(alignment: .leading, spacing: 8) { Text(title).font(.title3.bold()); Text(detail).foregroundStyle(.secondary) }.padding(.vertical, 18) }
