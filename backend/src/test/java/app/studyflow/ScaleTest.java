package app.studyflow;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ScaleTest {
    @TempDir Path temp;
    @Test @Timeout(10) void thousandAssessmentsRemainConsistentAndSchedulable() throws Exception {
        var courses=new ArrayList<Domain.Course>();var tasks=new ArrayList<Domain.Assessment>();
        Instant now=Instant.parse("2026-11-19T12:00:00Z");
        for(int i=0;i<30;i++)courses.add(new Domain.Course("c"+i,"CS"+i,"Course "+i,"Fall","fixture",""));
        for(int i=0;i<1000;i++)tasks.add(new Domain.Assessment("a"+i,"c"+(i%30),"Long assessment name with substantial detail ".repeat(8)+i,Domain.Kind.ASSIGNMENT,now.plusSeconds((i%20+1)*86400).toString(),null,null,null,2,0,Domain.Status.NOT_STARTED,"Keep personal notes","fixture","",false));
        try(var store=new Store(temp.resolve("db"))){store.reconcile(courses,tasks,"fixture");assertEquals(0,store.reconcile(courses,tasks,"fixture"));assertEquals(1000,store.assessments().size());var ranked=new PriorityEngine().rank(store.assessments(),now,3);assertEquals(1000,ranked.size());var plan=new Scheduler().plan(ranked,List.of(),EngineTest.prefs(13,17,List.of(1,2,3,4,5)),now,7);assertFalse(plan.sessions().isEmpty());assertTrue(plan.unscheduled().size()>900);for(int i=1;i<plan.sessions().size();i++)assertFalse(Instant.parse(plan.sessions().get(i).start()).isBefore(Instant.parse(plan.sessions().get(i-1).end())));}
    }
}
