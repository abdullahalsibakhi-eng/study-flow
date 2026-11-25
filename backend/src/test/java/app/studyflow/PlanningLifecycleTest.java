package app.studyflow;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static app.studyflow.Domain.*;

class PlanningLifecycleTest {
    @TempDir Path temp;
    private final Instant now=Instant.parse("2026-11-20T12:50:00Z");
    private Preferences prefs(){return EngineTest.prefs(12,17,List.of(1,2,3,4,5,6,7));}
    private List<Ranked> ranks(Assessment a){return new PriorityEngine().rank(List.of(a),now,5);}
    @Test void respectsBreakImmediatelyBeforePinnedSession() {
        var task=EngineTest.task("a",3,null,now.plusSeconds(86400));
        var pin=new Session("p","a","Pinned", "2026-11-20T13:00:00Z","2026-11-20T14:00:00Z",true);
        var plan=new Scheduler().plan(ranks(task),List.of(pin),prefs(),now,1);
        for(var s:plan.sessions())if(!s.pinned())assertFalse(Instant.parse(s.start()).isBefore(Instant.parse("2026-11-20T14:10:00Z")));
    }
    @Test void preservesAnOngoingUnpinnedSession() {
        var task=EngineTest.task("a",3,null,now.plusSeconds(86400));
        var ongoing=new Session("running","a","Working", "2026-11-20T12:30:00Z","2026-11-20T13:20:00Z",false);
        var plan=new Scheduler().plan(ranks(task),List.of(ongoing),prefs(),now,1);
        assertTrue(plan.sessions().contains(ongoing));
        assertTrue(plan.sessions().stream().filter(s -> !s.id().equals("running")).allMatch(s -> !Instant.parse(s.start()).isBefore(Instant.parse("2026-11-20T13:30:00Z"))));
    }
    @Test void deadlineConflictDoesNotPretendWorkIsCovered() {
        var task=EngineTest.task("a",1,null,now.minusSeconds(1));
        var pin=new Session("p","a","Pinned", "2026-11-20T13:00:00Z","2026-11-20T14:00:00Z",true);
        var plan=new Scheduler().plan(ranks(task),List.of(pin),prefs(),now,1);
        assertEquals(1,plan.conflicts().size());assertEquals(1,plan.unscheduled().getFirst().hours());assertTrue(plan.sessions().contains(pin));
    }
    @Test void stableGeneratedIdentitiesPreventDuplicateReminders() {
        var task=EngineTest.task("a",2,null,now.plusSeconds(86400));var scheduler=new Scheduler();
        assertEquals(scheduler.plan(ranks(task),List.of(),prefs(),now,1),scheduler.plan(ranks(task),List.of(),prefs(),now,1));
    }
    @Test void elapsedSessionsStayUnconfirmedAndExplicitCreditIsIdempotent() throws Exception {
        try(var store=new Store(temp.resolve("db"))) {
            store.saveCourse(new Course("c","CS","Computing","","manual",""));store.saveAssessment(EngineTest.task("a",2,null,now.plusSeconds(86400)));
            store.put("preferences",Json.encode(prefs()));
            var old=new Session("old","a","Prior study","2026-11-20T11:00:00Z","2026-11-20T12:00:00Z",true);
            store.replacePlan(List.of(old));var planning=new PlanningService(store);planning.maintain(now);
            assertEquals("UNCONFIRMED",store.history().getFirst().outcome());assertEquals(0,store.assessments().getFirst().workedHours());
            assertTrue(store.sessions().stream().noneMatch(s -> s.id().equals("old")));
            planning.complete("old",now);planning.complete("old",now);
            assertEquals(1,store.assessments().getFirst().workedHours());assertEquals(1,store.history().getFirst().creditedHours());
        }
        try(var store=new Store(temp.resolve("db"))){assertEquals("COMPLETED",store.history().getFirst().outcome());assertEquals(1,store.assessments().getFirst().workedHours());}
    }
    @Test void futureWorkCannotBeLogged() throws Exception {
        try(var store=new Store(temp.resolve("db"))) {
            store.saveCourse(new Course("c","CS","Computing","","manual",""));store.saveAssessment(EngineTest.task("a",2,null,null));
            store.replacePlan(List.of(new Session("future","a","Task",now.plusSeconds(60).toString(),now.plusSeconds(3600).toString(),true)));
            assertThrows(IllegalArgumentException.class,()->store.completeSession("future",now));assertEquals(0,store.assessments().getFirst().workedHours());
        }
    }
    @Test void versionOneDatabaseMigratesWithoutChangingUserMetadata() throws Exception {
        Path file=temp.resolve("legacy.db");
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+file);var statement=connection.createStatement()) {
            String sql=new String(Objects.requireNonNull(Store.class.getResourceAsStream("/schema.sql")).readAllBytes());
            for(String line:sql.split(";"))if(!line.isBlank())statement.execute(line);
            statement.execute("INSERT INTO courses VALUES('c','CS','Computing','','manual','')");
            statement.execute("INSERT INTO assessments VALUES('a','c','Existing work','LAB',NULL,'manual','',0)");
            statement.execute("INSERT INTO assessment_metadata VALUES('a',15,NULL,NULL,5,1,'IN_PROGRESS','Keep these notes')");
        }
        try(var store=new Store(file)){assertEquals("Keep these notes",store.assessments().getFirst().notes());assertEquals(1,store.assessments().getFirst().workedHours());assertTrue(store.remoteDetails().isEmpty());assertTrue(store.history().isEmpty());}
        try(var store=new Store(file)){assertEquals(1,store.assessments().size());}
        assertTrue(Files.exists(temp.resolve("legacy.db.before-v2.db")));
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+temp.resolve("legacy.db.before-v2.db"));var statement=connection.createStatement();var result=statement.executeQuery("SELECT MAX(version) FROM schema_version")){assertTrue(result.next());assertEquals(1,result.getInt(1));}
    }
}
