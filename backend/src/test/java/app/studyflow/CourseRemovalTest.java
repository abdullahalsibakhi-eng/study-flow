package app.studyflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import static app.studyflow.Domain.*;
import static org.junit.jupiter.api.Assertions.*;

class CourseRemovalTest {
    @TempDir Path temp;
    private Course course(String id) { return new Course(id,id,"Course "+id,"Fall","remote",""); }
    private Assessment task(String id,String course,Kind kind) {
        return new Assessment(id,course,id,kind,null,null,null,null,2,0,Status.NOT_STARTED,"notes","remote","",false);
    }
    @Test void removesCourseChildrenAndHistoryWhilePreservingOtherCourse() throws Exception {
        try(var store=new Store(temp.resolve("data.db"))) {
            store.saveCourse(course("remove"));store.saveCourse(course("keep"));
            store.saveAssessment(task("assignment","remove",Kind.ASSIGNMENT));
            store.saveAssessment(task("quiz","remove",Kind.QUIZ));
            store.saveAssessment(task("other","keep",Kind.LAB));
            store.replacePlan(List.of(
                new Session("past","quiz","Quiz","2020-01-01T10:00:00Z","2020-01-01T11:00:00Z",true),
                new Session("future","assignment","Assignment","2099-01-01T10:00:00Z","2099-01-01T11:00:00Z",true),
                new Session("kept","other","Keep","2099-01-01T12:00:00Z","2099-01-01T13:00:00Z",true)));
            store.archiveElapsed(Instant.parse("2026-01-01T00:00:00Z"));
            assertEquals(1,store.history().size());
            store.notice("soon:assignment:deadline","Due","Assignment");
            store.notice("keep-notice","Keep","Other course");
            store.removeCourse("remove");
            assertEquals(List.of("keep"),store.courses().stream().map(Course::id).toList());
            assertEquals(List.of("other"),store.assessments().stream().map(Assessment::id).toList());
            assertEquals(List.of("kept"),store.sessions().stream().map(Session::id).toList());
            assertTrue(store.history().isEmpty());
            assertEquals(List.of("keep-notice"),store.notices().stream().map(Notice::id).toList());
        }
    }
    @Test void removedRemoteCourseStaysRemovedAfterRestartAndSync() throws Exception {
        Path file=temp.resolve("data.db");
        var courses=List.of(course("remove"),course("keep"));
        var tasks=List.of(task("a","remove",Kind.ASSIGNMENT),task("b","keep",Kind.QUIZ));
        try(var store=new Store(file)) {store.reconcile(courses,tasks,"remote",List.of(new RemoteDetails("a","Description",null,null,null)));store.removeCourse("remove");}
        try(var store=new Store(file)) {
            store.reconcile(courses,tasks,"remote",List.of(new RemoteDetails("a","Description",null,null,null)));
            assertTrue(store.remoteDetails().isEmpty());
            assertEquals(List.of("keep"),store.courses().stream().map(Course::id).toList());
            assertEquals(List.of("b"),store.assessments().stream().map(Assessment::id).toList());
        }
    }
    @Test void endpointRequiresConfirmationAndRefreshesState() throws Exception {
        var client=new IntegrationTest();
        try(var store=new Store(temp.resolve("api.db"));var api=new Api(store,IntegrationTest.TOKEN,0)) {
            api.start();store.saveCourse(course("remove"));store.saveAssessment(task("a","remove",Kind.QUIZ));
            assertEquals(400,client.call(api,"POST","courses/remove","{\"id\":\"remove\"}",IntegrationTest.TOKEN).statusCode());
            assertEquals(1,store.courses().size());
            assertEquals(200,client.call(api,"POST","courses/remove","{\"id\":\"remove\",\"confirm\":true}",IntegrationTest.TOKEN).statusCode());
            var state=Json.MAPPER.readTree(client.call(api,"GET","state","",IntegrationTest.TOKEN).body());
            assertEquals(0,state.path("courses").size());assertEquals(0,state.path("assessments").size());
            assertEquals(0,state.path("grades").size());assertEquals(0,state.path("sessions").size());
        }
    }
    @Test void unknownCourseDoesNotChangeStoredData() throws Exception {
        try(var store=new Store(temp.resolve("data.db"))) {
            store.saveCourse(course("keep"));
            assertThrows(IllegalArgumentException.class,() -> store.removeCourse("unknown"));
            assertEquals(1,store.courses().size());
        }
    }
}
