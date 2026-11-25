package app.studyflow;

import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import static app.studyflow.Domain.*;

/** Read-only adapter. A failed or incomplete fetch never reconciles a partial snapshot. */
public final class Brightspace implements SyncService.Source {
    public record Config(String host,String lpVersion,String leVersion) {
        public Config {
            URI uri=URI.create(host);
            if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) throw new IllegalArgumentException("Brightspace requires an HTTPS origin");
            if(!lpVersion.matches("[0-9]+\\.[0-9]+") || !leVersion.matches("[0-9]+\\.[0-9]+"))throw new IllegalArgumentException("Invalid API version");
            host=host.replaceAll("/$", "");
        }
    }
    public static final class RemoteFailure extends Exception {
        public final int status; public final long retrySeconds;
        RemoteFailure(int status,long retrySeconds) { super(status==401?"Brightspace authorization expired; reconnect":status==403?"Brightspace permission denied; ask the institution for API access":status==429?"Brightspace rate limit reached":"Brightspace request failed (HTTP "+status+")");this.status=status;this.retrySeconds=retrySeconds; }
    }
    @FunctionalInterface public interface Transport { JsonNode get(URI uri,String token) throws Exception; }
    private final Config config;private final String token;private final Transport transport;
    public Brightspace(Config config,String token) { this(config,token,Brightspace::request); }
    public Brightspace(Config config,String token,Transport transport) { this.config=config;this.token=token;this.transport=transport; }
    private static JsonNode request(URI uri,String token) throws Exception {
        var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25)).header("Authorization","Bearer "+token).header("Accept","application/json").GET().build();
        var response=HttpTransport.send(request,4_000_000,30);
        if(response.statusCode()!=200)throw new RemoteFailure(response.statusCode(),HttpTransport.retryAfter(response.headers().firstValue("Retry-After").orElse("60"),Instant.now()));
        return Json.MAPPER.readTree(response.body());
    }
    private List<JsonNode> pages(String path) throws Exception {
        var result=new ArrayList<JsonNode>();var seen=new HashSet<String>();String next=path;
        for(int count=0;next!=null && count<200;count++) {
            if(!seen.add(next))throw new IllegalArgumentException("Repeated Brightspace page");
            URI uri=URI.create(config.host()).resolve(next);
            if(!Objects.equals(uri.getHost(),URI.create(config.host()).getHost()) || !"https".equals(uri.getScheme()) || uri.getPort()!=URI.create(config.host()).getPort() || uri.getUserInfo()!=null || uri.getFragment()!=null || !uri.getPath().startsWith("/d2l/api/"))throw new IllegalArgumentException("Unsafe pagination origin");
            JsonNode json=transport.get(uri,token);
            if(json.isArray()) {json.forEach(result::add);next=null;continue;}
            JsonNode items=json.has("Items")?json.get("Items"):json.get("Objects");
            if(items==null || !items.isArray())throw new IllegalArgumentException("Unrecognized Brightspace response");
            items.forEach(result::add);next=null;
            if(json.path("PagingInfo").path("HasMoreItems").asBoolean()) {
                String bookmark=json.path("PagingInfo").path("Bookmark").asText();
                if(bookmark.isBlank())throw new IllegalArgumentException("Missing page bookmark");
                next=path+(path.contains("?")?"&":"?")+"bookmark="+URLEncoder.encode(bookmark,java.nio.charset.StandardCharsets.UTF_8);
            } else if(!json.path("Next").isMissingNode() && !json.path("Next").isNull())next=json.get("Next").asText();
        }
        if(next!=null)throw new IllegalArgumentException("Brightspace pagination limit reached");
        return result;
    }
    @Override public SyncService.Snapshot fetch() throws Exception {
        var courses=new ArrayList<Course>();var assessments=new ArrayList<Assessment>();var details=new ArrayList<RemoteDetails>();
        String source="brightspace:"+URI.create(config.host()).getHost();
        for(var enrollment:pages("/d2l/api/lp/"+config.lpVersion()+"/enrollments/myenrollments/?orgUnitTypeId=3&isActive=true")) {
            var org=enrollment.path("OrgUnit");String id=org.path("Id").asText();
            if(!id.matches("[0-9]+"))throw new IllegalArgumentException("Invalid remote course ID");
            String courseId=source+":"+id;
            courses.add(new Course(courseId,org.path("Code").asText(id),org.path("Name").asText(),"",source,config.host()+"/d2l/home/"+id));
            for(var folder:pages("/d2l/api/le/"+config.leVersion()+"/"+id+"/dropbox/folders/")) if(!folder.path("IsHidden").asBoolean(false)) {
                var a=map(folder,courseId,id,source,false);assessments.add(a);details.add(details(folder,a.id(),false));
            }
            for(var quiz:pages("/d2l/api/le/"+config.leVersion()+"/"+id+"/quizzes/")) if(quiz.path("IsActive").asBoolean(true)) {
                var a=map(quiz,courseId,id,source,true);assessments.add(a);details.add(details(quiz,a.id(),true));
            }
        }
        return new SyncService.Snapshot(courses,assessments,source,details);
    }
    private RemoteDetails details(JsonNode node,String id,boolean quiz) {
        JsonNode availability=quiz?node:node.path("Availability");
        String description=quiz?(node.path("Description").path("IsDisplayed").asBoolean(false)?plain(node.path("Description").path("Text")):""):plain(node.path("CustomInstructions"));
        if(quiz && node.path("Instructions").path("IsDisplayed").asBoolean(false))description += "\n"+plain(node.path("Instructions").path("Text"));
        JsonNode points=node.path("Assessment").path("ScoreDenominator");
        return new RemoteDetails(id,description.strip(),text(availability,"StartDate"),text(availability,"EndDate"),points.isNumber()?points.asDouble():null);
    }
    private static String text(JsonNode value,String field){return value.path(field).isTextual()?value.path(field).asText():null;}
    private static String plain(JsonNode rich) {
        // Display as literal text only. Never execute HTML or load remote course assets.
        String value=rich.path("Text").asText("");
        if(value.isBlank())value=rich.path("Html").asText("").replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1>","").replaceAll("<[^>]+>"," ");
        return value.replace("&nbsp;"," ").replace("&amp;","&").replace("&lt;","<").replace("&gt;",">");
    }
    private Assessment map(JsonNode node,String courseId,String org,String source,boolean quiz) {
        String id=node.path(quiz?"QuizId":"Id").asText();if(!id.matches("[0-9]+"))throw new IllegalArgumentException("Invalid remote assessment ID");
        String due=node.path("DueDate").isTextual()?node.path("DueDate").asText():null;
        String url=config.host()+(quiz?"/d2l/lms/quizzing/user/quiz_summary.d2l?qi=":"/d2l/lms/dropbox/user/folder_submit_files.d2l?db=")+id+"&ou="+org;
        return new Assessment(courseId+":"+(quiz?"quiz:":"assignment:")+id,courseId,node.path("Name").asText(),quiz?Kind.QUIZ:Kind.ASSIGNMENT,due,null,null,null,2,0,Status.NOT_STARTED,"",source,url,false);
    }
}
