import SwiftUI
// Explicit alias selects the stable property wrapper on SDKs that also expose a State macro.
typealias ViewState<Value> = SwiftUI.State<Value>
import Foundation
struct Course: Codable, Identifiable { var id, code, name: String; var term, source, url: String? }
struct Assessment: Codable, Identifiable, Hashable {
    var id, courseId, title, kind: String
    var due: String?
    var weight, earned, possible: Double?
    var estimatedHours, workedHours: Double
    var status, notes, source, url: String
    var missing: Bool
    var remaining: Double { max(0, estimatedHours-workedHours) }
    var active: Bool { status != "COMPLETED" && status != "SUBMITTED" && !missing }
    var deadline: Date? { date(due) }
}
struct Ranked: Codable, Identifiable { var assessment: Assessment; var score: Double; var category: String; var reasons: [String]; var id: String { assessment.id } }
struct Session: Codable, Identifiable { var id, assessmentId, title, start, end: String; var pinned: Bool }
struct Preferences: Codable {
    var zone: String; var startHour, endHour, sessionMinutes, breakMinutes: Int; var days: [Int]
    var syncMinutes: Int; var notifications: Bool; var quietStart, quietEnd: Int
}
struct SyncState: Codable { var connection, lastSuccess, nextSync, error: String; var changed: Int; var running: Bool; var canSync: Bool }
struct RemoteDetails: Codable { var assessmentId, description: String; var availableFrom, availableUntil: String?; var possiblePoints: Double? }
struct PlanConflict: Codable, Identifiable { var sessionId, title, reason: String; var id: String { sessionId + reason } }
struct SessionHistory: Codable, Identifiable { var session: Session; var outcome: String; var creditedHours: Double; var recordedAt: String; var id: String { session.id } }
struct Stats: Codable { var courses, incomplete, dueToday, dueWeek, overdue, completed: Int; var hours: Double }
struct Notice: Codable, Identifiable { var id, title, body, created: String; var read: Bool }
struct Grade: Codable, Identifiable { var courseId: String; var knownWeight, gradedWeight, earnedContribution: Double; var averageOnGradedWork, requiredOnRemaining: Double?; var note: String; var id: String { courseId } }
struct Unscheduled: Codable, Identifiable { var assessmentId, title: String; var hours: Double; var reason: String; var id: String { assessmentId } }
struct AppState: Codable {
    var remoteDetails: [String: RemoteDetails]; var sessionHistory: [SessionHistory]; var planConflicts: [PlanConflict]; var assistantEnabled: Bool
    var courses: [Course]; var assessments: [Assessment]; var ranked: [Ranked]; var sessions: [Session]
    var preferences: Preferences; var stats: Stats; var sync: SyncState; var notices: [Notice]; var grades: [Grade]; var unscheduled: [Unscheduled]
}
struct Answer: Codable { var text: String; var cloud: Bool; var notice: String }
func date(_ value: String?) -> Date? {
    guard let value else { return nil }
    let f = ISO8601DateFormatter()
    if let d = f.date(from: value) { return d }
    f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return f.date(from: value)
}
func iso(_ value: Date) -> String { ISO8601DateFormatter().string(from: value) }
func object<T: Encodable>(_ value: T) throws -> [String: Any] { try JSONSerialization.jsonObject(with: JSONEncoder().encode(value)) as! [String: Any] }

func sourceLabel(_ source: String) -> String { source == "manual" ? "Manual" : source == "calendar" ? "Calendar import" : "Brightspace" }
