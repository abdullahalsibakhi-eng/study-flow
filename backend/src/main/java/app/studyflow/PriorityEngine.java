package app.studyflow;

import java.time.*;
import java.util.*;
import static app.studyflow.Domain.*;

/** Bounded, deterministic scoring. Unknown grade weight is neutral, never inferred from points. */
public final class PriorityEngine {
    public record Weights(double urgency, double impact, double feasibility, double progress) {
        public Weights { if (!Double.isFinite(urgency+impact+feasibility+progress) || Math.min(Math.min(urgency,impact),Math.min(feasibility,progress)) < 0 || urgency+impact+feasibility+progress <= 0) throw new IllegalArgumentException("Invalid priority weights"); }
    }
    private final Weights weights;
    public PriorityEngine() { this(new Weights(.45,.25,.25,.05)); }
    public PriorityEngine(Weights weights) { this.weights=weights; }
    public List<Ranked> rank(List<Assessment> tasks, Instant now, double dailyHours) {
        range(dailyHours, .25, 24, "Daily study hours");
        return tasks.stream().filter(Assessment::active).map(a -> score(a,now,dailyHours))
            .sorted(Comparator.comparingDouble(Ranked::score).reversed().thenComparing(r -> r.assessment().id())).toList();
    }
    private Ranked score(Assessment a, Instant now, double dailyHours) {
        double hours = a.deadline() == null ? Double.POSITIVE_INFINITY : Duration.between(now,a.deadline()).toMinutes()/60.0;
        double urgency = hours <= 0 ? 1 : Double.isInfinite(hours) ? .1 : 1/(1+hours/48);
        double impact = a.weight() == null ? .25 : Math.min(1,a.weight()/30);
        double capacity = Double.isInfinite(hours) ? 7*dailyHours : Math.max(.25,hours/24*dailyHours);
        double risk = Math.min(1,a.remaining()/capacity);
        double progress = a.workedHours()/a.estimatedHours();
        double sum=weights.urgency+weights.impact+weights.feasibility+weights.progress;
        double score=100*(weights.urgency*urgency+weights.impact*impact+weights.feasibility*risk+weights.progress*progress)/sum;
        var reasons=new ArrayList<String>();
        reasons.add(a.due()==null ? "No deadline available" : hours<=0 ? "Overdue; confirm whether late submission is accepted" : "Due in " + Math.max(1,(long)Math.ceil(hours)) + " hours");
        reasons.add(a.weight()==null ? "Final-grade weight unknown; neutral impact used" : String.format(Locale.ROOT,"%.1f%% of final course grade",a.weight()));
        reasons.add(String.format(Locale.ROOT,"%.1f hours remaining",a.remaining()));
        if (a.remaining()>capacity) reasons.add("Work exceeds estimated available time before deadline");
        reasons.add("Assessment type does not change the score");
        return new Ranked(a,Math.round(score*10)/10.0,score>=70 ? "High" : score>=45 ? "Medium" : "Low",reasons);
    }
}
