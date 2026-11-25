package app.studyflow;

import java.time.*;
import java.util.*;
import static app.studyflow.Domain.*;

/** Coordinates plans and session history using the same store lock as synchronization. */
public final class PlanningService {
    private final Store store;
    public PlanningService(Store store) { this.store=store; }
    public List<Ranked> ranked(Instant now) throws Exception {
        synchronized(store) {
            var p=store.preferences();
            return new PriorityEngine().rank(store.assessments(),now,Math.max(.25,(p.endHour()-p.startHour())*p.days().size()/7.0));
        }
    }
    public Scheduler.Plan plan(Instant now,int days) throws Exception {
        synchronized(store) {
            store.archiveElapsed(now);
            var p=store.preferences();
            var result=new Scheduler().plan(ranked(now),store.sessions(),p,now,days);
            store.savePlan(result,now,ZoneId.of(p.zone()));
            return result;
        }
    }
    public void maintain(Instant now) throws Exception {
        synchronized(store) {
            boolean elapsed=store.sessions().stream().anyMatch(s -> !Instant.parse(s.end()).isAfter(now));
            String today=now.atZone(ZoneId.of(store.preferences().zone())).toLocalDate().toString();
            if(elapsed || !today.equals(store.get("lastPlanDate","")))plan(now,7);
        }
    }
    public void complete(String id,Instant now) throws Exception {
        synchronized(store) { store.completeSession(id,now);plan(now,7); }
    }
}
