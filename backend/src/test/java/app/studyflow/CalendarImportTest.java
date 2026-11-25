package app.studyflow;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static app.studyflow.Domain.*;
import static org.junit.jupiter.api.Assertions.*;
class CalendarImportTest {
    @TempDir Path temp;
    String calendar(String extra){return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:task-1\r\nSUMMARY:Java\\, lab\r\nDESCRIPTION:First line\\nSecond\r\n continued\r\n"+extra+"\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";}
    @Test void parsesUtcAndFoldedEscapedText(){var preview=new CalendarImport().parse(calendar("DTSTART:20261125T180000Z"),"c","America/Toronto");assertEquals("2026-11-25T18:00:00Z",preview.assessments().getFirst().due());assertEquals("Java, lab",preview.assessments().getFirst().title());assertEquals("First line\nSecondcontinued",preview.details().getFirst().description());}
    @Test void parsesIanaAndAllDay(){var parser=new CalendarImport();assertEquals("2026-11-25T23:00:00Z",parser.parse(calendar("DTSTART;TZID=America/Toronto:20261125T180000"),"c","UTC").assessments().getFirst().due());assertEquals("2026-11-25T05:00:00Z",parser.parse(calendar("DTSTART;VALUE=DATE:20261125"),"c","America/Toronto").assessments().getFirst().due());}
    @Test void rejectsRecurrenceAndMalformedFiles(){var parser=new CalendarImport();assertThrows(IllegalArgumentException.class,()->parser.parse(calendar("DTSTART:20261125T180000Z\r\nRRULE:FREQ=WEEKLY"),"c","UTC"));assertThrows(IllegalArgumentException.class,()->parser.parse(calendar("DTSTART:20261125T180000Z").replace("END:VEVENT",""),"c","UTC"));}
    @Test void rejectsAmbiguousLocalTime(){assertThrows(IllegalArgumentException.class,()->new CalendarImport().parse(calendar("DTSTART;TZID=America/Toronto:20261101T013000"),"c","UTC"));}
    @Test void reimportPreservesNotesAndDoesNotRemoveOtherWork() throws Exception {
        try(var store=new Store(temp.resolve("db"))){store.saveCourse(new Course("c","CS","Computing","","manual",""));var parser=new CalendarImport();var first=parser.parse(calendar("DTSTART:20261125T180000Z"),"c","UTC");store.importCalendar(first);var a=store.assessments().getFirst();store.saveAssessment(new Assessment(a.id(),"c",a.title(),a.kind(),a.due(),15.0,null,null,5,1,Status.IN_PROGRESS,"Personal note",a.source(),a.url(),false));store.saveAssessment(EngineTest.task("other",2,null,null));store.importCalendar(parser.parse(calendar("DTSTART:20261126T180000Z"),"c","UTC"));assertEquals(2,store.assessments().size());var updated=store.assessments().stream().filter(t->t.id().equals(a.id())).findFirst().orElseThrow();assertEquals("Personal note",updated.notes());assertEquals(5,updated.estimatedHours());assertEquals("2026-11-26T18:00:00Z",updated.due());assertEquals("",store.get("lastSync",""));}
    }
    @Test void dropsUnsafeLinksAndDoesNotLoadAttachments(){var p=new CalendarImport().parse(calendar("DTSTART:20261125T180000Z\r\nURL:javascript:alert(1)\r\nATTACH:https://example.test/private"),"c","UTC");assertEquals("",p.assessments().getFirst().url());}
}
