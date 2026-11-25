package app.studyflow;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class RemoteReliabilityTest {
    @TempDir Path temp;
    @Test void retryAfterSupportsDatesAndRejectsUnboundedDelays(){var now=Instant.parse("2026-11-20T12:00:00Z");assertEquals(3600,HttpTransport.retryAfter(DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(3600).atZone(ZoneOffset.UTC)),now));assertEquals(60,HttpTransport.retryAfter("nonsense",now));assertEquals(1,HttpTransport.retryAfter("-10",now));assertEquals(2_592_000,HttpTransport.retryAfter("999999999",now));}
    @Test void syncDoesNotReportAPlanningFailureAsLostRemoteData() throws Exception {
        try(var store=new Store(temp.resolve("db"));var sync=new SyncService(store,()->{throw new IllegalStateException("Planner failure");})){
            sync.connect(()->new SyncService.Snapshot(List.of(new Domain.Course("c","CS","Course","","remote","")),List.of(),"remote"));assertEquals("Connecting",sync.state().connection());sync.tick();assertEquals("Connected",sync.state().connection());assertEquals(1,store.courses().size());assertFalse(sync.state().lastSuccess().isBlank());assertTrue(store.notices().stream().anyMatch(n->n.title().contains("plan")));
        }
    }
    @Test void mapsDisplayableDescriptionsAndAvailabilityWithoutInventingWeights() throws Exception {
        var client=new Brightspace(new Brightspace.Config("https://lms.example.edu","1.49","1.82"),"token",(uri,token)->{
            if(uri.toString().contains("enrollments"))return Json.MAPPER.readTree("{\"Items\":[{\"OrgUnit\":{\"Id\":1,\"Code\":\"CS\",\"Name\":\"Course\"}}]}");
            if(uri.toString().contains("dropbox"))return Json.MAPPER.readTree("[{\"Id\":8,\"Name\":\"Lab\",\"CustomInstructions\":{\"Text\":\"Read chapter 4\"},\"Availability\":{\"StartDate\":\"2026-11-25T12:00:00Z\"},\"Assessment\":{\"ScoreDenominator\":50}},{\"Id\":9,\"Name\":\"Hidden\",\"IsHidden\":true}]");
            return Json.MAPPER.readTree("{\"Objects\":[{\"QuizId\":10,\"Name\":\"Quiz\",\"IsActive\":true,\"Description\":{\"IsDisplayed\":false,\"Text\":{\"Text\":\"HIDDEN MATERIAL\"}},\"Instructions\":{\"IsDisplayed\":true,\"Text\":{\"Html\":\"<b>Visible</b><script>bad()</script>\"}}}],\"Next\":null}");
        });
        var snapshot=client.fetch();assertEquals(2,snapshot.assessments().size());assertEquals(50,snapshot.details().getFirst().possiblePoints());assertNull(snapshot.assessments().getFirst().weight());assertEquals("2026-11-25T12:00:00Z",snapshot.details().getFirst().availableFrom());assertEquals("Visible",snapshot.details().get(1).description());
        try(var store=new Store(temp.resolve("db"))){store.reconcile(snapshot.courses(),snapshot.assessments(),snapshot.source(),snapshot.details());assertEquals(2,store.remoteDetails().size());}
    }
    @Test void sourceDetailsChangesDoNotReplacePersonalNotes() throws Exception {
        try(var store=new Store(temp.resolve("db"))){var c=new Domain.Course("c","CS","Course","","remote","");var a=new Domain.Assessment("a","c","Lab",Domain.Kind.LAB,null,null,null,null,2,0,Domain.Status.NOT_STARTED,"My notes","remote","",false);store.reconcile(List.of(c),List.of(a),"remote",List.of(new Domain.RemoteDetails("a","First",null,null,null)));store.reconcile(List.of(c),List.of(a),"remote",List.of(new Domain.RemoteDetails("a","Revised",null,null,null)));assertEquals("My notes",store.assessments().getFirst().notes());assertEquals("Revised",store.remoteDetails().get("a").description());}
    }
    @Test void boundedTransportRejectsOversizedResponse() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",ex->{byte[] b=new byte[100];ex.sendResponseHeaders(200,b.length);ex.getResponseBody().write(b);ex.close();});server.start();
        try{assertThrows(Exception.class,()->HttpTransport.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort())).GET().build(),10,2));}finally{server.stop(0);}
    }
    @Test @Timeout(5) void timeoutIncludesSlowBodyAfterHeaders() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var workers=Executors.newSingleThreadExecutor();server.setExecutor(workers);
        server.createContext("/",ex->{ex.sendResponseHeaders(200,0);ex.getResponseBody().write('a');ex.getResponseBody().flush();try{Thread.sleep(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{ex.close();}});server.start();
        try{assertThrows(TimeoutException.class,()->HttpTransport.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort())).GET().build(),100,1));}finally{server.stop(0);workers.shutdownNow();}
    }
}
