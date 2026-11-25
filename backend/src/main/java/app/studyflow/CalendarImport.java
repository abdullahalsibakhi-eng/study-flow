package app.studyflow;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.*;
import java.util.*;
import static app.studyflow.Domain.*;

/** Deliberately constrained iCalendar import: preview first; no network or recurrence guessing. */
public final class CalendarImport {
    public record Preview(List<Assessment> assessments,List<RemoteDetails> details,List<String> warnings) {}
    private record Property(String value,Map<String,String> parameters) {}
    public Preview parse(String content,String courseId,String fallbackZone) {
        required(courseId,"Course");ZoneId zone=ZoneId.of(fallbackZone);
        if(content==null || content.getBytes(StandardCharsets.UTF_8).length>500_000)throw new IllegalArgumentException("Choose a calendar file smaller than 500 KB");
        String unfolded=content.replace("\r\n","\n").replaceAll("\n[ \\t]","");
        var stack=new ArrayDeque<String>();Map<String,Property> event=null;
        var tasks=new ArrayList<Assessment>();var details=new ArrayList<RemoteDetails>();var ids=new HashSet<String>();
        boolean calendar=false,ended=false;
        for(String line:unfolded.split("\n")) {
            if(line.isBlank())continue;
            int colon=separator(line);if(colon<0)throw new IllegalArgumentException("Malformed iCalendar property");
            String head=line.substring(0,colon),value=line.substring(colon+1);
            String[] fields=head.split(";",-1);String key=fields[0].toUpperCase(Locale.ROOT);
            if(key.equals("BEGIN")) {
                if(ended)throw new IllegalArgumentException("Only one calendar per import is supported");
                if(value.equals("VCALENDAR")){if(!stack.isEmpty() || calendar)throw new IllegalArgumentException("Invalid calendar nesting");calendar=true;}
                else if(stack.isEmpty())throw new IllegalArgumentException("Missing VCALENDAR");
                if(value.equals("VEVENT")){if(!stack.peek().equals("VCALENDAR"))throw new IllegalArgumentException("Invalid event nesting");event=new HashMap<>();}
                if(value.equals("VTODO"))throw new IllegalArgumentException("To-do components are not supported; export individual calendar events");
                stack.push(value);continue;
            }
            if(key.equals("END")) {
                if(stack.isEmpty() || !stack.pop().equals(value))throw new IllegalArgumentException("Unbalanced calendar component");
                if(value.equals("VEVENT")) {
                    if(tasks.size()>=2000)throw new IllegalArgumentException("Import at most 2000 events at a time");
                    String uid=get(event,"UID"),title=get(event,"SUMMARY");required(uid,"Event UID");required(title,"Event summary");
                    if("CANCELLED".equals(get(event,"STATUS")))throw new IllegalArgumentException("Cancelled events require manual review; remove them from the import first");
                    String id="calendar:"+UUID.nameUUIDFromBytes((courseId+"\n"+uid).getBytes(StandardCharsets.UTF_8));
                    if(!ids.add(id))throw new IllegalArgumentException("Duplicate event UID; export individual nonrecurring events");
                    Property start=event.get("DTSTART");if(start==null)throw new IllegalArgumentException("Each event needs DTSTART");
                    String due=instant(start,zone).toString();String url=get(event,"URL");
                    if(!url.isEmpty()){URI uri=URI.create(url);if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null)url="";}
                    tasks.add(new Assessment(id,courseId,title,Kind.OTHER,due,null,null,null,2,0,Status.NOT_STARTED,"","calendar",url,false));
                    details.add(new RemoteDetails(id,get(event,"DESCRIPTION"),null,null,null));event=null;
                }
                if(value.equals("VCALENDAR"))ended=true;
                continue;
            }
            if(stack.isEmpty())throw new IllegalArgumentException("Property outside calendar");
            if(event!=null && stack.peek().equals("VEVENT")) {
                if(Set.of("RRULE","RDATE","EXDATE","RECURRENCE-ID").contains(key))throw new IllegalArgumentException("Recurring events are not supported; export expanded individual events");
                if(!Set.of("UID","SUMMARY","DTSTART","DESCRIPTION","URL","STATUS").contains(key))continue;
                Map<String,String> parameters=new HashMap<>();
                for(int i=1;i<fields.length;i++){String[] pair=fields[i].split("=",2);if(pair.length!=2)throw new IllegalArgumentException("Invalid calendar parameter");parameters.put(pair[0].toUpperCase(Locale.ROOT),pair[1].replaceAll("^\"|\"$",""));}
                if(event.put(key,new Property(value,parameters))!=null)throw new IllegalArgumentException("Duplicate event property: "+key);
            }
        }
        if(!calendar || !ended || !stack.isEmpty() || tasks.isEmpty())throw new IllegalArgumentException("Choose a complete calendar containing individual events");
        return new Preview(tasks,details,List.of("Event start times become assessment deadlines only after you confirm this preview.","All-day events use the beginning of that day in "+fallbackZone+"; verify these deadlines.","Repeated imports update matching UIDs in the selected course and preserve personal metadata. Missing events are not deleted. Estimates start at two hours."));
    }
    private static int separator(String line){boolean quoted=false;for(int i=0;i<line.length();i++){if(line.charAt(i)=='"')quoted=!quoted;if(line.charAt(i)==':' && !quoted)return i;}return -1;}
    private static String get(Map<String,Property> event,String key){Property p=event.get(key);if(p==null)return "";StringBuilder result=new StringBuilder();String v=p.value();for(int i=0;i<v.length();i++){char c=v.charAt(i);if(c=='\\' && i+1<v.length()){c=v.charAt(++i);result.append(c=='n'||c=='N'?'\n':c);}else result.append(c);}return result.toString();}
    private static Instant instant(Property p,ZoneId fallback) {
        try {
            String value=p.value();ZoneId zone=p.parameters().containsKey("TZID")?ZoneId.of(p.parameters().get("TZID")):fallback;
            if("DATE".equals(p.parameters().get("VALUE")))return LocalDate.parse(value,DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(zone).toInstant();
            boolean utc=value.endsWith("Z");String raw=utc?value.substring(0,value.length()-1):value;
            var local=LocalDateTime.parse(raw,DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss").withResolverStyle(ResolverStyle.STRICT));
            if(utc)return local.toInstant(ZoneOffset.UTC);
            if(zone.getRules().getValidOffsets(local).size()!=1)throw new IllegalArgumentException("Ambiguous or nonexistent local event time; export it in UTC");
            return local.atZone(zone).toInstant();
        }catch(DateTimeException e){throw new IllegalArgumentException("Unsupported event date or time zone; use UTC or an IANA zone");}
    }
}
