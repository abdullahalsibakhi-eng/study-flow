package app.studyflow;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static app.studyflow.Domain.*;
import static org.junit.jupiter.api.Assertions.*;
class AssistantContextTest {
    final Instant now=Instant.parse("2026-11-20T12:00:00Z");
    AssistantService.Context context(List<Assessment> tasks){return new AssistantService.Context(List.of(new Course("c","CS2402","Computing","","manual","")),tasks,new PriorityEngine().rank(tasks,now,4),List.of(),EngineTest.prefs(13,17,List.of(1,2,3,4,5,6,7)),now);}
    @Test void countUsesAllTasksRatherThanTruncatedProviderContext(){var tasks=new ArrayList<Assessment>();for(int i=0;i<25;i++)tasks.add(EngineTest.task("a"+i,1,null,now.plusSeconds(86400)));String answer=new AssistantService().answer("How many assessments are due this week?",context(tasks)).text();assertTrue(answer.startsWith("25 active assessments"));}
    @Test void tomorrowPreviewUsesActualAvailability(){var answer=new AssistantService().answer("Make a plan for tomorrow",context(List.of(EngineTest.task("a",2,null,now.plusSeconds(3*86400)))));assertFalse(answer.cloud());assertTrue(answer.text().contains("2026-11-21"));assertTrue(answer.text().contains("13:00"));assertTrue(answer.text().contains("Nothing has been saved"));}
    @Test void budgetPreviewLimitsGeneratedTime(){var answer=new AssistantService().answer("I only have one hour today",context(List.of(EngineTest.task("a",5,null,now.plusSeconds(3*86400)))));assertTrue(answer.text().contains("1.0 hours allocated"));}
    @Test void overdueWorkIsNotPromisedFeasible(){var answer=new AssistantService().answer("What should I study tonight?",context(List.of(EngineTest.task("a",2,null,now.minusSeconds(60)))));assertTrue(answer.text().contains("No feasible study time"));}
    @Test void contextHasRealCountsAndExcludesPersonalMetadata(){var service=new AssistantService();service.enable((q,c)->{assertTrue(c.contains("CS2402"));assertTrue(c.contains("activeAssessmentCount"));assertFalse(c.contains("PRIVATE-NOTE"));assertFalse(c.contains("88.125"));return "Grounded answer";});var a=new Assessment("a","c","Lab",Kind.LAB,now.plusSeconds(86400).toString(),null,88.125,100.0,2,0,Status.IN_PROGRESS,"PRIVATE-NOTE","manual","",false);assertTrue(service.answer("Why?",context(List.of(a))).cloud());}
}
