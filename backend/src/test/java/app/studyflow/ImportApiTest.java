package app.studyflow;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ImportApiTest {
    @TempDir Path temp;
    @Test void previewDoesNotWriteAndImportRequiresExplicitConfirmation() throws Exception {
        var client=new IntegrationTest();
        try(var store=new Store(temp.resolve("db"));var api=new Api(store,IntegrationTest.TOKEN,0)){
            api.start();store.saveCourse(new Domain.Course("c","CS","Course","","manual",""));
            String content="BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:lab\nSUMMARY:Import lab\nDTSTART:20261201T180000Z\nEND:VEVENT\nEND:VCALENDAR\n";
            var payload=new HashMap<String,Object>();payload.put("content",content);payload.put("courseId","c");
            assertEquals(200,client.call(api,"POST","imports/calendar/preview",Json.encode(payload),IntegrationTest.TOKEN).statusCode());assertTrue(store.assessments().isEmpty());
            assertEquals(400,client.call(api,"POST","imports/calendar",Json.encode(payload),IntegrationTest.TOKEN).statusCode());assertTrue(store.assessments().isEmpty());
            payload.put("confirmEventStartsAsDeadlines",true);assertEquals(200,client.call(api,"POST","imports/calendar",Json.encode(payload),IntegrationTest.TOKEN).statusCode());assertEquals(1,store.assessments().size());
            var state=Json.MAPPER.readTree(client.call(api,"GET","state","",IntegrationTest.TOKEN).body());assertTrue(state.has("sessionHistory"));assertTrue(state.has("planConflicts"));assertEquals(1,state.path("remoteDetails").size());assertFalse(state.path("assistantEnabled").asBoolean());
            assertEquals(400,client.call(api,"POST","plan","null",IntegrationTest.TOKEN).statusCode());
        }
    }
}
