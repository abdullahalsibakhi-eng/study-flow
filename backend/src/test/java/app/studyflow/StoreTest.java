package app.studyflow;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static app.studyflow.Domain.*;
class StoreTest {
    @TempDir Path temp;
    Course course=new Course("c","CS","Computer Science","Fall","remote","");
    Assessment remote(String due){return new Assessment("r","c","Remote lab",Kind.LAB,due,null,null,null,2,0,Status.NOT_STARTED,"","remote","",false);}
    @Test void persistenceSurvivesReopen() throws Exception {Path file=temp.resolve("data.db");try(var s=new Store(file)){s.saveCourse(course);s.saveAssessment(remote(null));}try(var s=new Store(file)){assertEquals(1,s.courses().size());assertEquals(1,s.assessments().size());}}
    @Test void reconciliationIsIdempotentAndPreservesMetadata() throws Exception {try(var s=new Store(temp.resolve("data.db"))){assertEquals(1,s.reconcile(List.of(course),List.of(remote(null)),"remote"));assertEquals(0,s.reconcile(List.of(course),List.of(remote(null)),"remote"));s.saveAssessment(new Assessment("r","c","Remote lab",Kind.LAB,null,15.0,null,null,6,1,Status.IN_PROGRESS,"Keep me","remote","",false));s.reconcile(List.of(course),List.of(remote("2026-11-21T00:00:00Z")),"remote");var a=s.assessments().getFirst();assertEquals("Keep me",a.notes());assertEquals(6,a.estimatedHours());assertEquals(15,a.weight());assertEquals("2026-11-21T00:00:00Z",a.due());assertEquals(2,s.notices().size());}}
    @Test void disappearancePreservesLocalDataAndReturnRestores() throws Exception {try(var s=new Store(temp.resolve("data.db"))){s.reconcile(List.of(course),List.of(remote(null)),"remote");s.reconcile(List.of(course),List.of(),"remote");assertTrue(s.assessments().getFirst().missing());s.reconcile(List.of(course),List.of(remote(null)),"remote");assertFalse(s.assessments().getFirst().missing());}}
    @Test void failedSnapshotRollsBack() throws Exception {try(var s=new Store(temp.resolve("data.db"))){var wrong=new Assessment("x","unknown","bad",Kind.LAB,null,null,null,null,2,0,Status.NOT_STARTED,"","remote","",false);assertThrows(Exception.class,()->s.reconcile(List.of(course),List.of(remote(null),wrong),"remote"));assertTrue(s.courses().isEmpty());assertTrue(s.assessments().isEmpty());assertEquals("",s.get("lastSync",""));}}
    @Test void notificationsDeduplicate() throws Exception {try(var s=new Store(temp.resolve("data.db"))){s.notice("id","title","body");s.notice("id","title","body");assertEquals(1,s.notices().size());s.readNotices();assertTrue(s.notices().getFirst().read());}}
    @Test void preferencesPersist() throws Exception {try(var s=new Store(temp.resolve("data.db"))){var p=EngineTest.prefs(9,12,List.of(1,3,5));s.put("preferences",Json.encode(p));assertEquals(p,s.preferences());}}
}
