package app.studyflow;

import java.time.*;
import java.util.*;

/** Immutable values shared by persistence, engines, and the local API. */
public final class Domain {
    private Domain() {}
    public enum Kind { ASSIGNMENT, LAB, QUIZ, MIDTERM, FINAL, PROJECT, PRESENTATION, OTHER }
    public enum Status { NOT_STARTED, IN_PROGRESS, COMPLETED, SUBMITTED }
    public record Course(String id, String code, String name, String term, String source, String url) {
        public Course { required(id, "Course ID"); required(code, "Course code"); required(name, "Course name"); }
    }
    public record Assessment(String id, String courseId, String title, Kind kind, String due,
        Double weight, Double earned, Double possible, double estimatedHours, double workedHours,
        Status status, String notes, String source, String url, boolean missing) {
        public Assessment {
            required(id, "Assessment ID"); required(courseId, "Course"); required(title, "Title");
            Objects.requireNonNull(kind); Objects.requireNonNull(status);
            if (due != null && !due.isBlank()) Instant.parse(due); else due = null;
            range(weight, 0, 100, "Weight"); range(earned, 0, 1e9, "Earned points");
            range(possible, 0.00001, 1e9, "Possible points");
            range(estimatedHours, 0.25, 1000, "Estimated hours"); range(workedHours, 0, 1000, "Worked hours");
            if (workedHours > estimatedHours) throw new IllegalArgumentException("Worked hours exceed estimate; increase the estimate first");
            if (notes == null) notes = "";
            if (notes.length() > 20000) throw new IllegalArgumentException("Notes are too long");
        }
        public boolean active() { return status != Status.COMPLETED && status != Status.SUBMITTED && !missing; }
        public double remaining() { return active() ? Math.max(0, estimatedHours - workedHours) : 0; }
        public Instant deadline() { return due == null ? null : Instant.parse(due); }
    }
    public record Ranked(Assessment assessment, double score, String category, List<String> reasons) {}
    public record Session(String id, String assessmentId, String title, String start, String end, boolean pinned) {
        public Session {
            required(id,"Session ID"); required(assessmentId,"Assessment"); required(title,"Title");
            if (!Instant.parse(end).isAfter(Instant.parse(start))) throw new IllegalArgumentException("Session end must follow start");
        }
    }
    public record Preferences(String zone, int startHour, int endHour, int sessionMinutes, int breakMinutes,
        List<Integer> days, int syncMinutes, boolean notifications, int quietStart, int quietEnd) {
        public Preferences {
            ZoneId.of(zone);
            if (startHour < 0 || endHour > 24 || startHour >= endHour || sessionMinutes < 15 || sessionMinutes > 180 || breakMinutes < 0 || breakMinutes > 60 || syncMinutes < 5 || syncMinutes > 1440 || quietStart < 0 || quietStart > 23 || quietEnd < 0 || quietEnd > 23)
                throw new IllegalArgumentException("Invalid study or notification preferences");
            if (days == null || days.isEmpty() || days.stream().anyMatch(d -> d < 1 || d > 7)) throw new IllegalArgumentException("Choose study days");
            days = List.copyOf(new TreeSet<>(days));
        }
        public static Preferences defaults() { return new Preferences(ZoneId.systemDefault().getId(),18,21,50,10,List.of(1,2,3,4,5),30,false,22,8); }
    }
    /** Source-owned display information. Never mixed with personal notes or inferred grade weights. */
    public record RemoteDetails(String assessmentId, String description, String availableFrom,
        String availableUntil, Double possiblePoints) {
        public RemoteDetails {
            required(assessmentId, "Assessment ID");
            description = description == null ? "" : description;
            if (description.length() > 20000) description = description.substring(0,20000);
            if (availableFrom != null) Instant.parse(availableFrom);
            if (availableUntil != null) Instant.parse(availableUntil);
            range(possiblePoints, 0, 1e9, "Source points");
        }
    }
    public record SessionHistory(Session session, String outcome, double creditedHours, String recordedAt) {}
    public record Notice(String id, String title, String body, String created, boolean read) {}
    public static void required(String value, String label) { if (value == null || value.isBlank() || value.length() > 500) throw new IllegalArgumentException(label + " is required (maximum 500 characters)"); }
    public static void range(Double value, double min, double max, String label) { if (value != null && (!Double.isFinite(value) || value < min || value > max)) throw new IllegalArgumentException(label + " is out of range"); }
}
