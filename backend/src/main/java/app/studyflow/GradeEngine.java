package app.studyflow;
import java.util.*;
import static app.studyflow.Domain.*;
/** Computes earned percentage points only from explicit final-course weights. */
public final class GradeEngine {
    public record Insight(String courseId, double knownWeight, double gradedWeight, double earnedContribution, Double averageOnGradedWork, Double requiredOnRemaining, String note) {}
    public Insight calculate(String courseId,List<Assessment> all,double target) {
        range(target,0,100,"Target");
        var tasks=all.stream().filter(a -> a.courseId().equals(courseId) && !a.missing()).toList();
        double known=0,graded=0,earned=0;
        for(var a:tasks) if(a.weight()!=null) { known+=a.weight(); if(a.earned()!=null && a.possible()!=null) { graded+=a.weight();earned+=a.weight()*a.earned()/a.possible(); } }
        boolean complete=known>99.999 && known<100.001 && tasks.stream().allMatch(a -> a.weight()!=null);
        Double required=complete && graded<100 ? (target-earned)/(100-graded)*100:null;
        return new Insight(courseId,known,graded,earned,graded>0?earned/graded*100:null,required,known>100.001?"Weights exceed 100%; correct the grading scheme":complete?"Assumes all final-course weights are listed; no dropped grades or extra-credit rules":"Incomplete grading scheme; target scenario unavailable");
    }
}
