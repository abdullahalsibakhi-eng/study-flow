package app.studyflow;

import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static app.studyflow.Domain.*;

/** Loopback-only authenticated HTTP boundary. No CORS, no cookies, and bounded request bodies. */
public final class Api implements AutoCloseable {
    private final Store store;private final HttpServer server;private final String token;
    private final ExecutorService workers=Executors.newFixedThreadPool(6);
    private final PlanningService planning;
    private final OAuthSession oauth;
    private final AssistantService assistant=new AssistantService();private final SyncService sync;
    private final ScheduledExecutorService reminders=Executors.newSingleThreadScheduledExecutor();
    public Api(Store store,String token,int port) throws Exception {
        if(token==null || token.length()<32)throw new IllegalArgumentException("Local API token must contain at least 32 characters");
        this.store=store;this.token=token;this.planning=new PlanningService(store);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),32);server.setExecutor(workers);
        sync=new SyncService(store,() -> {try{plan(7);}catch(Exception failure){throw new IllegalStateException("Planning failed",failure);}});
        oauth=new OAuthSession(OAuthSession::saveKeychain, source -> {sync.connect(source);sync.request();});
        server.createContext("/api/",this::handle);
        reminders.scheduleWithFixedDelay(this::remind,10,60,TimeUnit.SECONDS);
    }
    public void start(){server.start();}
    public int port(){return server.getAddress().getPort();}
    private List<Ranked> ranked() throws Exception {return planning.ranked(Instant.now());}
    private Scheduler.Plan plan(int days) throws Exception {return planning.plan(Instant.now(),days);}
    private void remind() {
        try {
            Instant now=Instant.now();
            planning.maintain(now);
            for(var a:store.assessments())if(a.active() && a.deadline()!=null && a.deadline().isBefore(now.plusSeconds(86400)))store.notice((a.deadline().isBefore(now)?"overdue:":"soon:")+a.id()+":"+a.due(),a.deadline().isBefore(now)?"Assessment overdue":"Due within 24 hours",a.title());
            for(var s:store.sessions())if(Instant.parse(s.start()).isAfter(now) && Instant.parse(s.start()).isBefore(now.plusSeconds(900)))store.notice("session:"+s.id(),"Study session starts soon",s.title());
        }catch(Exception failure) {try{store.notice("maintenance:"+LocalDate.now(),"Background planning needs attention","StudyFlow could not refresh the local plan. Your stored work is retained; try Plan This Week.");}catch(Exception ignored){}}
    }
    private void handle(HttpExchange exchange) {
        try {
            String auth=exchange.getRequestHeaders().getFirst("Authorization");
            if(auth==null || !MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8),("Bearer "+token).getBytes(StandardCharsets.UTF_8))) {send(exchange,401,Map.of("error","Unauthorized"));return;}
            if(exchange.getRequestHeaders().containsKey("Origin")) {send(exchange,403,Map.of("error","Browser requests are not allowed"));return;}
            String method=exchange.getRequestMethod();String path=exchange.getRequestURI().getPath().substring(5);
            if(method.equals("GET")) {
                Object result=switch(path) {
                    case "health" -> Map.of("status","ok","version","1.0.0");
                    case "state" -> state();
                    case "oauth/status" -> Map.of("status",oauth.status());
                    default -> null;
                };
                send(exchange,result==null?404:200,result==null?Map.of("error","Not found"):result);return;
            }
            if(!method.equals("POST")) {send(exchange,405,Map.of("error","Method not allowed"));return;}
            if(!Objects.toString(exchange.getRequestHeaders().getFirst("Content-Type"), "").startsWith("application/json"))throw new IllegalArgumentException("JSON body required");
            byte[] bytes=exchange.getRequestBody().readNBytes(1_000_001);if(bytes.length>1_000_000){send(exchange,413,Map.of("error","Request too large"));return;}
            String body=new String(bytes,StandardCharsets.UTF_8);var json=Json.MAPPER.readTree(body);if(json==null || !json.isObject())throw new IllegalArgumentException("JSON object required");Object result=Map.of("ok",true);
            switch(path) {
                case "courses" -> {
                    String id=UUID.randomUUID().toString();store.saveCourse(new Course(id,json.path("code").asText(),json.path("name").asText(),json.path("term").asText(),"manual",""));
                }
                case "courses/remove" -> {
                    if(!json.path("confirm").asBoolean(false))throw new IllegalArgumentException("Confirm removal of the course and its assessments");
                    synchronized(store) { store.removeCourse(json.path("id").asText()); }
                    // Removal is already committed. A planning failure must not look like a failed deletion.
                    try { plan(7); } catch(Exception failure) { store.notice("replan-after-removal", "Plan needs attention", "Course removed. Use Plan This Week to refresh remaining sessions."); }
                }
                case "assessments" -> {
                    Assessment a=Json.decode(body,Assessment.class);
                    String assessmentId=a.id();
                    Assessment old=store.assessments().stream().filter(t -> t.id().equals(assessmentId)).findFirst().orElse(null);
                    if(old!=null && !old.source().equals("manual"))a=new Assessment(old.id(),old.courseId(),old.title(),old.kind(),old.due(),a.weight(),a.earned(),a.possible(),a.estimatedHours(),a.workedHours(),a.status(),a.notes(),old.source(),old.url(),old.missing());
                    else if(!"manual".equals(a.source()) || a.missing())throw new IllegalArgumentException("Manual assessment required");
                    if(store.courses().stream().noneMatch(c -> c.id().equals(json.path("courseId").asText())))throw new IllegalArgumentException("Choose an existing course");
                    store.saveAssessment(a);plan(7);
                }
                case "preferences" -> {var p=Json.decode(body,Preferences.class);store.put("preferences",Json.encode(p));plan(7);}
                case "plan" -> result=plan(json.path("days").asInt(7));
                case "pin" -> {store.pin(json.path("id").asText(),json.path("pinned").asBoolean());plan(7);}
                case "imports/calendar/preview", "imports/calendar" -> {
                    String courseId=json.path("courseId").asText();
                    if(store.courses().stream().noneMatch(c -> c.id().equals(courseId)))throw new IllegalArgumentException("Choose an existing course");
                    var preview=new CalendarImport().parse(json.path("content").asText(),courseId,store.preferences().zone());
                    if(path.equals("imports/calendar")) {
                        if(!json.path("confirmEventStartsAsDeadlines").asBoolean(false))throw new IllegalArgumentException("Confirm that event starts should become deadlines");
                        store.importCalendar(preview);plan(7);
                    }
                    result=preview;
                }
                case "sessions/complete" -> planning.complete(json.path("id").asText(),Instant.now());
                case "notices/read" -> store.readNotices();
                case "sync" -> sync.request();
                case "brightspace/connect" -> {
                    var config=new Brightspace.Config(json.path("host").asText(),json.path("lpVersion").asText(),json.path("leVersion").asText());
                    String access=json.path("accessToken").asText();if(access.isBlank() || access.length()>10000)throw new IllegalArgumentException("Authorized access token required");
                    oauth.close();sync.connect(new Brightspace(config,access));sync.request();
                }
                case "brightspace/disconnect" -> {sync.disconnect();oauth.close();}
                case "oauth/start" -> result=Map.of("url",oauth.start(Json.decode(body,OAuthSession.Credentials.class)));
                case "oauth/restore" -> oauth.restore(Json.decode(body,OAuthSession.Credentials.class));
                case "assistant/configure" -> {
                    if(json.path("enabled").asBoolean())assistant.enable(AssistantService.compatible(json.path("endpoint").asText(),json.path("model").asText(),json.path("key").asText()));else assistant.disable();
                }
                case "assistant" -> result=assistant.answer(json.path("question").asText(),assistantContext());
                default -> {send(exchange,404,Map.of("error","Not found"));return;}
            }
            send(exchange,200,result);
        }catch(IllegalArgumentException|com.fasterxml.jackson.core.JsonProcessingException e) {safeError(exchange,400,"Invalid input: "+(e instanceof IllegalArgumentException?e.getMessage():"Malformed JSON"));}
        catch(Exception e) {safeError(exchange,500,"The operation could not be completed. Your stored data is retained.");}
        finally {exchange.close();}
    }
    private AssistantService.Context assistantContext() throws Exception {
        synchronized(store){return new AssistantService.Context(store.courses(),store.assessments(),ranked(),store.sessions(),store.preferences(),Instant.now());}
    }
    private synchronized Map<String,Object> state() throws Exception {
        var tasks=store.assessments();var p=store.preferences();Instant now=Instant.now();LocalDate today=now.atZone(ZoneId.of(p.zone())).toLocalDate();
        long todayCount=tasks.stream().filter(a -> a.active() && a.deadline()!=null && a.deadline().atZone(ZoneId.of(p.zone())).toLocalDate().equals(today)).count();
        var stats=Map.of("courses",store.courses().size(),"incomplete",tasks.stream().filter(Assessment::active).count(),"dueToday",todayCount,"dueWeek",tasks.stream().filter(a -> a.active() && a.deadline()!=null && !a.deadline().isBefore(now) && a.deadline().isBefore(now.plusSeconds(7*86400))).count(),"overdue",tasks.stream().filter(a -> a.active() && a.deadline()!=null && a.deadline().isBefore(now)).count(),"completed",tasks.stream().filter(a -> a.status()==Status.COMPLETED || a.status()==Status.SUBMITTED).count(),"hours",tasks.stream().mapToDouble(Assessment::remaining).sum());
        var grades=new ArrayList<GradeEngine.Insight>();for(var c:store.courses())grades.add(new GradeEngine().calculate(c.id(),tasks,80));
        return Map.ofEntries(Map.entry("remoteDetails",store.remoteDetails()),Map.entry("sessionHistory",store.history()),Map.entry("planConflicts",Json.MAPPER.readTree(store.get("planConflicts","[]"))),Map.entry("assistantEnabled",assistant.enabled()),Map.entry("courses",store.courses()),Map.entry("assessments",tasks),Map.entry("ranked",ranked()),Map.entry("sessions",store.sessions()),Map.entry("preferences",p),Map.entry("stats",stats),Map.entry("sync",sync.state()),Map.entry("notices",store.notices()),Map.entry("grades",grades),Map.entry("unscheduled",Json.MAPPER.readTree(store.get("unscheduled","[]"))));
    }
    private void safeError(HttpExchange ex,int code,String message){try{send(ex,code,Map.of("error",message));}catch(Exception ignored){}}
    private void send(HttpExchange ex,int code,Object body) throws java.io.IOException {
        byte[] bytes=Json.encode(body).getBytes(StandardCharsets.UTF_8);ex.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");ex.getResponseHeaders().set("Cache-Control","no-store");ex.getResponseHeaders().set("X-Content-Type-Options","nosniff");ex.sendResponseHeaders(code,bytes.length);ex.getResponseBody().write(bytes);
    }
    @Override public void close(){oauth.close();sync.close();reminders.shutdownNow();server.stop(1);workers.shutdownNow();}
}
