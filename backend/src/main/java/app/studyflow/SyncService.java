package app.studyflow;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static app.studyflow.Domain.*;

/** Polls only when authorized; serializes reconciliation and backs off without sleeping worker threads. */
public final class SyncService implements AutoCloseable {
    public record Snapshot(List<Course> courses,List<Assessment> assessments,String source,List<RemoteDetails> details) {
        public Snapshot(List<Course> courses,List<Assessment> assessments,String source){this(courses,assessments,source,List.of());}
    }
    @FunctionalInterface public interface Source { Snapshot fetch() throws Exception; }
    public record State(String connection,String lastSuccess,String nextSync,String error,int changed,boolean running,boolean canSync) {}
    private final Store store;private final Runnable replan;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    private volatile Source source;private volatile Instant next=Instant.MAX;private volatile String error="";
    private volatile boolean running;private volatile boolean verified;private volatile int failures,changed;private volatile long generation;
    public SyncService(Store store,Runnable replan) {this.store=store;this.replan=replan;timer.scheduleWithFixedDelay(this::tick,5,15,TimeUnit.SECONDS);}
    public synchronized void connect(Source source) {this.source=source;generation++;verified=false;failures=0;error="";next=Instant.now();}
    public synchronized void disconnect() {source=null;generation++;verified=false;next=Instant.MAX;error="";}
    public State state() throws Exception {return new State(source==null?"Disconnected":error.isEmpty()?(verified?"Connected":"Connecting"):"Needs attention",store.get("lastSync",""),next.equals(Instant.MAX)?"":next.toString(),error,changed,running,source!=null && !running && (failures==0 || !Instant.now().isBefore(next)));}
    public void request() {if(source!=null && Instant.now().isAfter(next))timer.submit(this::tick);else if(source!=null && failures==0){next=Instant.now();timer.submit(this::tick);}}
    void tick() {
        Source active;long epoch;
        synchronized(this) {if(source==null || running || Instant.now().isBefore(next))return;running=true;active=source;epoch=generation;}
        try {
            var snapshot=active.fetch();
            synchronized(this) {
                if(epoch!=generation)return;
                changed=store.reconcile(snapshot.courses(),snapshot.assessments(),snapshot.source(),snapshot.details());
                verified=true;failures=0;error="";next=Instant.now().plusSeconds(store.preferences().syncMinutes()*60L);
                try {replan.run();}catch(RuntimeException planningFailure){store.notice("planning-error:"+LocalDate.now(),"Study plan needs attention","Synchronization succeeded, but the study plan could not be refreshed. Try Plan This Week.");}
            }
        } catch(Exception e) {
            synchronized(this) {
                if(epoch!=generation)return;
                failures++;long delay=Math.min(21600,60L*(1L<<Math.min(failures,8)));
                if(e instanceof Brightspace.RemoteFailure remote) {delay=Math.max(delay,remote.retrySeconds);error=remote.getMessage();if(remote.status==401 || remote.status==403)next=Instant.MAX;else next=Instant.now().plusSeconds(delay);}
                else {error="Synchronization failed; stored data is unchanged. Check network and API configuration.";next=Instant.now().plusSeconds(delay);}
                try {store.notice("sync-error:"+LocalDate.now(),"Brightspace needs attention",error);}catch(Exception ignored) {}
            }
        } finally {running=false;}
    }
    @Override public void close() {timer.shutdownNow();}
}
