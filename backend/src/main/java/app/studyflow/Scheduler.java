package app.studyflow;

import java.time.*;
import java.util.*;
import static app.studyflow.Domain.*;

/** Greedy planning in real instants, using local availability and respecting pinned reservations. */
public final class Scheduler {
    public record Unscheduled(String assessmentId, String title, double hours, String reason) {}
    public record Conflict(String sessionId, String title, String reason) {}
    public record Plan(List<Session> sessions, List<Unscheduled> unscheduled, List<Conflict> conflicts) {}
    public Plan plan(List<Ranked> ranked, List<Session> existing, Preferences prefs, Instant now, int days) {
        if (days<1 || days>31) throw new IllegalArgumentException("Plan horizon must be 1–31 days");
        ZoneId zone=ZoneId.of(prefs.zone());
        var sessions=new ArrayList<>(existing.stream().filter(s -> Instant.parse(s.end()).isAfter(now) && (s.pinned() || !Instant.parse(s.start()).isAfter(now))).toList());
        var conflicts=new ArrayList<Conflict>();
        Set<String> invalid=new HashSet<>();
        for(var s:sessions) {
            Assessment a=ranked.stream().map(Ranked::assessment).filter(t -> t.id().equals(s.assessmentId())).findFirst().orElse(null);
            String reason=null;
            if(a==null)reason="Assessment is completed, submitted, or no longer visible";
            else if(a.deadline()!=null && Instant.parse(s.end()).isAfter(a.deadline()))reason="Reserved session ends after the current deadline";
            else if(sessions.stream().anyMatch(t -> !t.id().equals(s.id()) && Instant.parse(t.start()).isBefore(Instant.parse(s.end())) && Instant.parse(t.end()).isAfter(Instant.parse(s.start()))))reason="Reserved sessions overlap";
            if(reason!=null){conflicts.add(new Conflict(s.id(),s.title(),reason));invalid.add(s.id());}
        }
        var remaining=new LinkedHashMap<String,Double>();
        for (Ranked r:ranked) {
            double reserved=sessions.stream().filter(s -> s.assessmentId().equals(r.assessment().id()) && !invalid.contains(s.id()) && Instant.parse(s.end()).isAfter(now))
                .mapToDouble(s -> Duration.between(Instant.parse(s.start()).isBefore(now)?now:Instant.parse(s.start()),Instant.parse(s.end())).toMinutes()/60.0).sum();
            remaining.put(r.assessment().id(),Math.max(0,r.assessment().remaining()-reserved));
        }
        LocalDate date=now.atZone(zone).toLocalDate();
        for(int day=0;day<days;day++) {
            LocalDate d=date.plusDays(day);
            if(!prefs.days().contains(d.getDayOfWeek().getValue())) continue;
            Instant cursor=d.atTime(prefs.startHour(),0).atZone(zone).toInstant();
            // Resolve wall-clock end independently so daylight-saving transitions do not extend availability.
            Instant end=prefs.endHour()==24?d.plusDays(1).atStartOfDay(zone).toInstant():d.atTime(prefs.endHour(),0).atZone(zone).toInstant();
            cursor=cursor.isBefore(now)?now.plusSeconds(60-now.getEpochSecond()%60):cursor;
            while(cursor.plusSeconds(15*60).compareTo(end)<=0) {
                final Instant at=cursor;
                Session occupied=sessions.stream().filter(s -> Instant.parse(s.end()).plusSeconds(prefs.breakMinutes()*60L).isAfter(at) && !Instant.parse(s.start()).minusSeconds(prefs.breakMinutes()*60L).isAfter(at)).findFirst().orElse(null);
                if(occupied!=null) { cursor=Instant.parse(occupied.end()).plusSeconds(prefs.breakMinutes()*60L); continue; }
                Instant freeUntil=sessions.stream().map(s -> Instant.parse(s.start()).minusSeconds(prefs.breakMinutes()*60L)).filter(t -> t.isAfter(at)).min(Instant::compareTo).orElse(end);
                if(freeUntil.isAfter(end)) freeUntil=end;
                boolean placed=false;
                for(Ranked r:ranked) {
                    Assessment a=r.assessment(); double left=remaining.get(a.id());
                    if(left<.001) continue;
                    Instant limit=a.deadline()!=null && a.deadline().isBefore(freeUntil)?a.deadline():freeUntil;
                    long minutes=Math.min(prefs.sessionMinutes(),Math.min((long)Math.ceil(left*60),Duration.between(cursor,limit).toMinutes()));
                    if(minutes<15 && minutes<Math.ceil(left*60)) continue;
                    if(minutes<=0) continue;
                    Instant finish=cursor.plusSeconds(minutes*60);
                    sessions.add(new Session(UUID.nameUUIDFromBytes((a.id()+"|"+cursor+"|"+finish).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),a.id(),a.title(),cursor.toString(),finish.toString(),false));
                    remaining.put(a.id(),Math.max(0,left-minutes/60.0));
                    cursor=finish.plusSeconds(prefs.breakMinutes()*60L);placed=true;break;
                }
                if(!placed) cursor=cursor.plusSeconds(15*60);
            }
        }
        var unmet=new ArrayList<Unscheduled>();
        for(Ranked r:ranked) if(remaining.get(r.assessment().id())>.01) unmet.add(new Unscheduled(r.assessment().id(),r.assessment().title(),Math.round(remaining.get(r.assessment().id())*100)/100.0,"Insufficient availability within the plan horizon and deadline; overdue work requires a revised deadline"));
        sessions.sort(Comparator.comparing(Session::start));
        return new Plan(sessions,unmet,conflicts);
    }
}
