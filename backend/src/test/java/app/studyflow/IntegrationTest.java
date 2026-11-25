package app.studyflow;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class IntegrationTest {
    @TempDir Path temp;
    static final String TOKEN="0123456789012345678901234567890123456789";
    HttpResponse<String> call(Api api,String method,String path,String body,String token) throws Exception {
        var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+api.port()+"/api/"+path)).header("Authorization","Bearer "+token).header("Content-Type","application/json");
        if(method.equals("POST"))r.POST(HttpRequest.BodyPublishers.ofString(body));else r.GET();
        return HttpClient.newHttpClient().send(r.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void apiAuthenticationValidationAndPersistence() throws Exception {
        try(var store=new Store(temp.resolve("db"));var api=new Api(store,TOKEN,0)) {
            api.start();assertEquals(401,call(api,"GET","state","","wrong").statusCode());assertEquals(200,call(api,"GET","health","",TOKEN).statusCode());
            assertEquals(400,call(api,"POST","courses","{bad",TOKEN).statusCode());assertEquals(400,call(api,"POST","courses","{\"code\":\"\",\"name\":\"x\"}",TOKEN).statusCode());
            assertEquals(200,call(api,"POST","courses","{\"code\":\"CS\",\"name\":\"Computer Science\"}",TOKEN).statusCode());
            var state=Json.MAPPER.readTree(call(api,"GET","state","",TOKEN).body());assertEquals(1,state.path("courses").size());assertEquals("Disconnected",state.path("sync").path("connection").asText());assertEquals(0,state.path("assessments").size());
            String course=state.path("courses").get(0).path("id").asText();
            var a=new Domain.Assessment("a",course,"A real task",Domain.Kind.LAB,java.time.Instant.now().plusSeconds(14*86400).toString(),null,null,null,2,0,Domain.Status.NOT_STARTED,"","manual","",false);
            assertEquals(200,call(api,"POST","assessments",Json.encode(a),TOKEN).statusCode());
            assertEquals(1,store.assessments().size());assertFalse(store.sessions().isEmpty());
            assertEquals(404,call(api,"GET","missing","",TOKEN).statusCode());
            var origin=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+api.port()+"/api/state")).header("Authorization","Bearer "+TOKEN).header("Origin","https://example.com").GET().build();
            assertEquals(403,HttpClient.newHttpClient().send(origin,HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
    @Test void insecureConfigRejected(){assertThrows(IllegalArgumentException.class,()->new Brightspace.Config("http://example.com","1.49","1.82"));assertThrows(IllegalArgumentException.class,()->AssistantService.compatible("http://example.com","model","key"));}
    @Test void brightspacePaginatesAndMapsWithoutInventingWeights() throws Exception {
        var config=new Brightspace.Config("https://lms.example.edu","1.49","1.82");var paths=new ArrayList<String>();
        Brightspace.Transport transport=(uri,token)->{
            String path=uri.toString();paths.add(path);
            String body=path.contains("bookmark=")?"{\"Items\":[],\"PagingInfo\":{\"HasMoreItems\":false}}":path.contains("myenrollments")?"{\"Items\":[{\"OrgUnit\":{\"Id\":123,\"Code\":\"CS\",\"Name\":\"Computing\"}}],\"PagingInfo\":{\"HasMoreItems\":true,\"Bookmark\":\"123\"}}":path.contains("dropbox")?"[{\"Id\":8,\"Name\":\"Lab\",\"DueDate\":\"2026-12-01T00:00:00Z\",\"ScoreDenominator\":100}]":"{\"Objects\":[{\"QuizId\":9,\"Name\":\"Quiz\",\"IsActive\":true}],\"Next\":null}";
            return Json.MAPPER.readTree(body);
        };
        var snapshot=new Brightspace(config,"token",transport).fetch();assertEquals(1,snapshot.courses().size());assertEquals(2,snapshot.assessments().size());assertTrue(paths.stream().anyMatch(p->p.contains("bookmark=123")));assertTrue(snapshot.assessments().stream().allMatch(a->a.weight()==null));assertNotEquals(snapshot.assessments().get(0).id(),snapshot.assessments().get(1).id());
    }
    @Test void remoteFailuresAreSafe() throws Exception {var client=new Brightspace(new Brightspace.Config("https://lms.example.edu","1.49","1.82"),"secret",(uri,token)->{throw new Brightspace.RemoteFailure(401,60);});var e=assertThrows(Brightspace.RemoteFailure.class,client::fetch);assertFalse(e.getMessage().contains("secret"));}
    @Test void crossOriginPaginationRejected() throws Exception {var client=new Brightspace(new Brightspace.Config("https://lms.example.edu","1.49","1.82"),"secret",(uri,token)->Json.MAPPER.readTree("{\"Items\":[],\"Next\":\"https://attacker.example/steal\"}"));assertThrows(IllegalArgumentException.class,client::fetch);}
}
