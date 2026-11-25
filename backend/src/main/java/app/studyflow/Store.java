package app.studyflow;

import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static app.studyflow.Domain.*;

/** Single serialized JDBC connection; snapshot reconciliation commits atomically. */
public final class Store implements AutoCloseable {
    private final Connection db;
    public Store(Path path) throws Exception {
        Files.createDirectories(path.toAbsolutePath().getParent());
        db=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath());
        try(var s=db.createStatement()) { s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA busy_timeout=5000"); }
        execute("CREATE TABLE IF NOT EXISTS schema_version(version INTEGER PRIMARY KEY)");
        int version=query("SELECT COALESCE(MAX(version),0) FROM schema_version",r -> r.getInt(1)).getFirst();
        if(version>2) {db.close();throw new IllegalStateException("Database was created by a newer version of StudyFlow");}
        if(version<1)try(var in=Store.class.getResourceAsStream("/schema.sql")) {
            String migration=new String(Objects.requireNonNull(in).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            transaction(() -> {for(String sql:migration.split(";"))if(!sql.isBlank())execute(sql);});
        }
        if(version==1) {
            Path backup=path.toAbsolutePath().resolveSibling(path.getFileName()+".before-v2.db");
            if(!Files.exists(backup))execute("VACUUM INTO ?",backup.toString());
        }
        if(version<2)try(var in=Store.class.getResourceAsStream("/migration-2.sql")) {
            String migration=new String(Objects.requireNonNull(in).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            transaction(() -> {for(String sql:migration.split(";"))if(!sql.isBlank())execute(sql);});
        }
    }
    private void execute(String sql,Object... args) throws SQLException {
        try(var s=db.prepareStatement(sql)) { for(int i=0;i<args.length;i++) s.setObject(i+1,args[i]);s.executeUpdate(); }
    }
    @FunctionalInterface private interface Row<T> { T read(ResultSet r) throws SQLException; }
    private <T> List<T> query(String sql,Row<T> row,Object... args) throws SQLException {
        try(var s=db.prepareStatement(sql)) { for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);try(var r=s.executeQuery()) { var list=new ArrayList<T>();while(r.next())list.add(row.read(r));return list; } }
    }
    private static Double number(ResultSet r,String key) throws SQLException { double n=r.getDouble(key);return r.wasNull()?null:n; }
    public synchronized List<Course> courses() throws SQLException { return query("SELECT * FROM courses ORDER BY code",r -> new Course(r.getString("id"),r.getString("code"),r.getString("name"),r.getString("term"),r.getString("source"),r.getString("url"))); }
    public synchronized void saveCourse(Course c) throws SQLException { execute("INSERT INTO courses VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET code=excluded.code,name=excluded.name,term=excluded.term,url=excluded.url",c.id(),c.code(),c.name(),c.term(),c.source(),c.url()); }
    /** Remove local course data atomically; suppress future remote re-imports. */
    public synchronized void removeCourse(String id) throws SQLException {
        Course course=courses().stream().filter(c -> c.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Course not found"));
        var tasks=assessments().stream().filter(a -> a.courseId().equals(id)).toList();
        Set<String> taskIds=new HashSet<>();for(var a:tasks)taskIds.add(a.id());
        transaction(() -> {
            if(!course.source().equals("manual"))put("removedCourse:"+id,"true");
            // History has JSON payloads rather than a foreign key; inspect every row.
            for(var session:query("SELECT payload FROM session_history",r -> Json.decode(r.getString(1),Session.class)))
                if(taskIds.contains(session.assessmentId()))execute("DELETE FROM session_history WHERE session_id=?",session.id());
            for(var session:sessions())if(taskIds.contains(session.assessmentId()))execute("DELETE FROM notices WHERE id=?","session:"+session.id());
            for(var a:tasks) {
                for(String table:List.of("sessions","remote_details","assessment_metadata"))
                    execute("DELETE FROM "+table+" WHERE assessment_id=?",a.id());
                for(var notice:query("SELECT id FROM notices",r -> r.getString(1)))
                    if(notice.startsWith("change:"+a.id()+":") || notice.startsWith("missing:"+a.id()+":") || notice.startsWith("soon:"+a.id()+":") || notice.startsWith("overdue:"+a.id()+":"))
                        execute("DELETE FROM notices WHERE id=?",notice);
                execute("DELETE FROM assessments WHERE id=?",a.id());
            }
            execute("DELETE FROM courses WHERE id=?",id);
            put("unscheduled","[]");put("planConflicts","[]");put("lastPlanDate","");
        });
    }
    public synchronized List<Assessment> assessments() throws SQLException {
        return query("SELECT a.*,m.* FROM assessments a JOIN assessment_metadata m ON a.id=m.assessment_id ORDER BY a.id",r -> new Assessment(r.getString("id"),r.getString("course_id"),r.getString("title"),Kind.valueOf(r.getString("kind")),r.getString("due"),number(r,"weight"),number(r,"earned"),number(r,"possible"),r.getDouble("estimated"),r.getDouble("worked"),Status.valueOf(r.getString("status")),r.getString("notes"),r.getString("source"),r.getString("url"),r.getBoolean("missing")));
    }
    private void saveAssessmentRows(Assessment a,boolean remote) throws SQLException {
        execute("INSERT INTO assessments VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET title=excluded.title,kind=excluded.kind,due=excluded.due,url=excluded.url,missing=excluded.missing",a.id(),a.courseId(),a.title(),a.kind().name(),a.due(),a.source(),a.url(),a.missing());
        execute("INSERT INTO assessment_metadata VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(assessment_id) "+(remote?"DO NOTHING":"DO UPDATE SET weight=excluded.weight,earned=excluded.earned,possible=excluded.possible,estimated=excluded.estimated,worked=excluded.worked,status=excluded.status,notes=excluded.notes"),a.id(),a.weight(),a.earned(),a.possible(),a.estimatedHours(),a.workedHours(),a.status().name(),a.notes());
    }
    public synchronized void saveAssessment(Assessment a) throws SQLException { transaction(() -> saveAssessmentRows(a,false)); }
    @FunctionalInterface private interface Work { void run() throws SQLException; }
    private void transaction(Work work) throws SQLException {
        db.setAutoCommit(false);
        try { work.run(); db.commit(); } catch(SQLException|RuntimeException e) { db.rollback();throw e; } finally { db.setAutoCommit(true); }
    }
    public synchronized int reconcile(List<Course> courses,List<Assessment> incoming,String source) throws SQLException {
        return reconcile(courses,incoming,source,List.of());
    }
    public synchronized int reconcile(List<Course> courses,List<Assessment> incoming,String source,List<RemoteDetails> details) throws SQLException {
        Set<String> ids=new HashSet<>();
        for(var a:incoming) if(!source.equals(a.source()) || !ids.add(a.id())) throw new IllegalArgumentException("Invalid or duplicate snapshot identity");
        for(var d:details) if(!ids.contains(d.assessmentId())) throw new IllegalArgumentException("Details refer to an absent assessment");
        Set<String> excluded=new HashSet<>();
        for(var c:courses)if(get("removedCourse:"+c.id(),"false").equals("true"))excluded.add(c.id());
        for(var a:incoming)if(get("removedCourse:"+a.courseId(),"false").equals("true"))excluded.add(a.courseId());
        Set<String> retainedIds=new HashSet<>();for(var a:incoming)if(!excluded.contains(a.courseId()))retainedIds.add(a.id());
        Map<String,RemoteDetails> oldDetails=remoteDetails();
        Map<String,Assessment> previous=new HashMap<>();for(var a:assessments())if(source.equals(a.source()))previous.put(a.id(),a);
        Set<String> changed=new HashSet<>();
        transaction(() -> {
            for(var c:courses)if(!excluded.contains(c.id()))saveCourse(c);
            for(var a:incoming) {
                if(excluded.contains(a.courseId()))continue;
                var old=previous.remove(a.id());
                if(old==null || !Objects.equals(old.due(),a.due()) || !old.title().equals(a.title()) || old.missing()) {
                    changed.add(a.id());
                    notice("change:"+a.id()+":"+UUID.randomUUID(),old==null?"New assessment":"Assessment updated",a.title());
                }
                saveAssessmentRows(a,true);
            }
            for(var d:details) {
                if(!retainedIds.contains(d.assessmentId()))continue;
                var old=oldDetails.get(d.assessmentId());
                if(old!=null && !old.equals(d) && changed.add(d.assessmentId()))notice("details:"+UUID.randomUUID(),"Assessment information changed",incoming.stream().filter(a -> a.id().equals(d.assessmentId())).findFirst().orElseThrow().title());
                execute("INSERT INTO remote_details VALUES(?,?) ON CONFLICT(assessment_id) DO UPDATE SET payload=excluded.payload",d.assessmentId(),Json.encode(d));
            }
            for(var old:previous.values()) if(!old.missing()) { changed.add(old.id());execute("UPDATE assessments SET missing=1 WHERE id=?",old.id());notice("missing:"+old.id()+":"+UUID.randomUUID(),"Assessment no longer visible",old.title()+" — local notes retained"); }
            put("lastSync",Instant.now().toString());
        });
        return changed.size();
    }
    public synchronized List<Session> sessions() throws SQLException { return query("SELECT * FROM sessions ORDER BY start",r -> new Session(r.getString("id"),r.getString("assessment_id"),r.getString("title"),r.getString("start"),r.getString("end"),r.getBoolean("pinned"))); }
    public synchronized void replacePlan(List<Session> sessions) throws SQLException { transaction(() -> { execute("DELETE FROM sessions WHERE pinned=0");for(var s:sessions)execute("INSERT OR IGNORE INTO sessions VALUES(?,?,?,?,?,?)",s.id(),s.assessmentId(),s.title(),s.start(),s.end(),s.pinned()); }); }
    public synchronized void importCalendar(CalendarImport.Preview preview) throws SQLException {
        transaction(() -> {
            for(var a:preview.assessments())saveAssessmentRows(a,true);
            for(var d:preview.details())execute("INSERT INTO remote_details VALUES(?,?) ON CONFLICT(assessment_id) DO UPDATE SET payload=excluded.payload",d.assessmentId(),Json.encode(d));
        });
    }
    public synchronized Map<String,RemoteDetails> remoteDetails() throws SQLException {
        var result=new LinkedHashMap<String,RemoteDetails>();
        for(var d:query("SELECT payload FROM remote_details",r -> Json.decode(r.getString(1),RemoteDetails.class)))result.put(d.assessmentId(),d);
        return result;
    }
    public synchronized void savePlan(Scheduler.Plan plan, Instant now, ZoneId zone) throws SQLException {
        transaction(() -> {
            execute("DELETE FROM sessions");
            for(var s:plan.sessions())execute("INSERT INTO sessions VALUES(?,?,?,?,?,?)",s.id(),s.assessmentId(),s.title(),s.start(),s.end(),s.pinned());
            put("unscheduled",Json.encode(plan.unscheduled()));
            put("planConflicts",Json.encode(plan.conflicts()));
            put("lastPlanDate",now.atZone(zone).toLocalDate().toString());
        });
    }
    public synchronized int archiveElapsed(Instant now) throws SQLException {
        var elapsed=sessions().stream().filter(s -> !Instant.parse(s.end()).isAfter(now)).toList();
        transaction(() -> {
            for(var s:elapsed) {
                execute("INSERT OR IGNORE INTO session_history VALUES(?,?,?,?,?)",s.id(),Json.encode(s),"UNCONFIRMED",0,now.toString());
                execute("DELETE FROM sessions WHERE id=?",s.id());
            }
        });
        return elapsed.size();
    }
    public synchronized List<SessionHistory> history() throws SQLException {
        return query("SELECT * FROM session_history ORDER BY recorded DESC LIMIT 200",r -> new SessionHistory(Json.decode(r.getString("payload"),Session.class),r.getString("outcome"),r.getDouble("credited"),r.getString("recorded")));
    }
    /** Exactly-once explicit progress credit. Elapsed time alone never counts as completed work. */
    public synchronized void completeSession(String id, Instant now) throws SQLException {
        var previous=query("SELECT * FROM session_history WHERE session_id=?",r -> new SessionHistory(Json.decode(r.getString("payload"),Session.class),r.getString("outcome"),r.getDouble("credited"),r.getString("recorded")),id);
        if(!previous.isEmpty() && previous.getFirst().outcome().equals("COMPLETED"))return;
        Session session=previous.isEmpty()?sessions().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Session not found")):previous.getFirst().session();
        if(Instant.parse(session.end()).isAfter(now))throw new IllegalArgumentException("Log a session after its scheduled end; edit assessment progress for partial work");
        Assessment a=assessments().stream().filter(t -> t.id().equals(session.assessmentId())).findFirst().orElseThrow();
        double credit=Math.min(a.remaining(),Duration.between(Instant.parse(session.start()),Instant.parse(session.end())).toSeconds()/3600.0);
        transaction(() -> {
            execute("UPDATE assessment_metadata SET worked=?,status=? WHERE assessment_id=?",a.workedHours()+credit,a.active()?Status.IN_PROGRESS.name():a.status().name(),a.id());
            execute("INSERT INTO session_history VALUES(?,?,?,?,?) ON CONFLICT(session_id) DO UPDATE SET outcome=excluded.outcome,credited=excluded.credited,recorded=excluded.recorded",id,Json.encode(session),"COMPLETED",credit,now.toString());
            execute("DELETE FROM sessions WHERE id=?",id);
        });
    }
    public synchronized void pin(String id,boolean pinned) throws SQLException { execute("UPDATE sessions SET pinned=? WHERE id=?",pinned,id); }
    public synchronized void put(String key,String value) throws SQLException { execute("INSERT INTO settings VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",key,value); }
    public synchronized String get(String key,String fallback) throws SQLException { var values=query("SELECT value FROM settings WHERE key=?",r -> r.getString(1),key);return values.isEmpty()?fallback:values.getFirst(); }
    public synchronized Preferences preferences() throws SQLException { return Json.decode(get("preferences",Json.encode(Preferences.defaults())),Preferences.class); }
    public synchronized void notice(String id,String title,String body) throws SQLException { execute("INSERT OR IGNORE INTO notices(id,title,body,created) VALUES(?,?,?,?)",id,title,body,Instant.now().toString()); }
    public synchronized List<Notice> notices() throws SQLException { return query("SELECT * FROM notices ORDER BY created DESC LIMIT 200",r -> new Notice(r.getString("id"),r.getString("title"),r.getString("body"),r.getString("created"),r.getBoolean("read"))); }
    public synchronized void readNotices() throws SQLException { execute("UPDATE notices SET read=1"); }
    @Override public synchronized void close() throws SQLException { db.close(); }
}
