package cn.studyroom;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** A bounded current-project audit projection, never a general private-record viewer. */
final class AuditSafeProjection {
    private static final int DEPTH=4,ARRAY=20,TEXT=300,SNAPSHOT=65536;
    private static final Set<String> IDS=Set.of("id","user_id","actor_id","reservation_id","seat_id","room_id","event_id","review_id","violation_id","restriction_id","notification_id","source_event_id","related_feedback_id","feedback_event_id","handler_id","target_id","position","source_order");
    private static final Set<String> NUMBERS=Set.of("version","appeal_version_before","rating","seat_rows","seat_cols","capacity","row_no","col_no","threshold","duration_hours","valid_count","valid_count_before","valid_count_after","attachment_count","continued_checked_in_count","settled_unused_count","duration_seconds","affected_count","cancelled_count","count","created","updated","inserted","ignored","imported_count","reservation_count","seat_count","weekday","day_of_week","reservation_version");
    private static final Set<String> BOOLEAN=Set.of("enabled","has_power","has_window","near_window","is_accessible","facts_confirmed","identity_confirmed","valid_reservation_confirmed","release_facts_confirmed","window_start_inclusive","window_end_inclusive","legacy_remaining_only","original_sources_preserved","credentials_reset");
    private static final Set<String> TOKENS=Set.of("status","status_before","status_after","source","type","decision","outcome","violation_action","end_reason","termination_reason","strategy","kind","verification_source","role","subject_role","subject_status");
    private static final Set<String> TIMES=Set.of("date","res_date","start","end","start_time","end_time","open_time","close_time","starts_at","ends_at","created_at","updated_at","occurred_at","registered_at","notified_at","submitted_at","ended_at","settled_at","checkin_at","checkout_at","leave_at","deadline","feedback_deadline","window_start","window_end","evaluated_at","business_checkin_at","operated_at","effects_applied_at","released_at","due_at","detected_at");
    private static final Set<String> RESOURCE_TEXT=Set.of("name","building","floor","location","seat_no","zone");
    private static final Set<String> CONTAINERS=Set.of("before","after","changes","effects","cancelled_pending","reservations","items","windows","segments","exceptions","weekly","date_exceptions","unavailability","automatic_evaluation","source_violations","booking_effects","usage_record","slots","periods","opening_windows","opening_schedules","protected_uses","cancelled","settled","continued","rules","source_violation_ids","reservation_ids","affected_reservation_ids","seat_ids","room_ids","user_ids");
    private static final Pattern TOKEN=Pattern.compile("[a-z][a-z0-9_:-]{0,79}");
    private static final Pattern EMAIL=Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE=Pattern.compile("(?<!\\d)(?:(?:\\+?86[-\\s]?)?1[3-9]\\d{9}|0\\d{2,3}[-\\s]?\\d{7,8})(?!\\d)|(?<![A-Za-z0-9])(?:\\+\\d{1,3}[ -]?)?(?:\\(?\\d{2,4}\\)?[ -]?)?\\d{3,4}[ -]\\d{4}(?!\\d)");
    private static final Pattern SQL=Pattern.compile("(?is)\\b(?:select\\s+.+?\\s+from|insert\\s+into|update\\s+\\w+\\s+set|delete\\s+from|drop\\s+(?:table|schema)|alter\\s+table)\\b");
    private static final Pattern HASH=Pattern.compile("(?i)(?<![A-Za-z0-9])[0-9a-f]{32,128}(?![A-Za-z0-9])");
    private static final Pattern CREDENTIAL=Pattern.compile("(?i)(?:bearer\\s+[A-Za-z0-9._~-]+|(?:password|token|secret|authorization|api[_-]?key)\\s*[:=]\\s*\\S+)");
    static final class Counts {int omitted,redacted,truncated,items;Map<String,Object> view(){return Map.of("omitted_fields",omitted,"redacted_values",redacted,"truncated_values",truncated,"omitted_items",items,"notice","安全投影，仅保留必要白名单事实；原正文、身份及私有材料已省略。深度最多4层、数组20项、文本300码点；超64Ki字符或解析深度64的快照整体省略。计数仅针对已读取投影，不代表完整原件；常见联系方式脱敏不保证识别任意自然语言隐私。");}}
    private final ObjectMapper json;
    AuditSafeProjection(ObjectMapper json){this.json=json;}
    Map<String,Object> snapshot(Object raw,Counts count){
        if(raw==null)return Map.of();String value=raw.toString();if(value.length()>SNAPSHOT){count.truncated++;return Map.of();}
        try(var parser=json.getFactory().createParser(value)){int depth=0;JsonToken token;while((token=parser.nextToken())!=null){if(token==JsonToken.START_ARRAY||token==JsonToken.START_OBJECT){if(++depth>64){count.truncated++;return Map.of();}}else if(token==JsonToken.END_ARRAY||token==JsonToken.END_OBJECT)depth--;}}
        catch(Exception e){count.omitted++;return Map.of();}
        try{var node=json.readTree(value);if(!node.isObject()){count.omitted++;return Map.of();}return object(node,1,count);}catch(Exception e){count.omitted++;return Map.of();}
    }
    private Map<String,Object> object(JsonNode node,int depth,Counts count){
        if(depth>DEPTH){count.truncated++;return Map.of();}var result=new LinkedHashMap<String,Object>();var fields=node.fields();while(fields.hasNext()){var field=fields.next();String key=field.getKey();JsonNode value=field.getValue();Object safe=null;boolean allowed=false;
            if(IDS.contains(key)){allowed=value.isNull()||value.isIntegralNumber()&&value.canConvertToLong()&&value.longValue()>=0;if(allowed&&!value.isNull())safe=value.longValue();}
            else if(NUMBERS.contains(key)){allowed=value.isNull()||value.isNumber()&&value.decimalValue().precision()<=24&&Math.abs(value.decimalValue().scale())<=12;if(allowed&&!value.isNull())safe=value.numberValue();}
            else if(BOOLEAN.contains(key)){allowed=value.isNull()||value.isBoolean();if(allowed&&!value.isNull())safe=value.booleanValue();}
            else if(TOKENS.contains(key)){allowed=value.isNull()||value.isTextual()&&TOKEN.matcher(value.textValue()).matches()&&!HASH.matcher(value.textValue()).find();if(allowed&&!value.isNull())safe=value.textValue();}
            else if(TIMES.contains(key)){allowed=value.isNull()||value.isTextual()&&time(value.textValue());if(allowed&&!value.isNull())safe=value.textValue();}
            else if(RESOURCE_TEXT.contains(key)){allowed=value.isNull()||value.isTextual();if(allowed&&!value.isNull())safe=text(value.textValue(),count);}
            else if(key.equals("operation_id")){allowed=value.isNull()||value.isTextual()&&value.textValue().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");if(allowed&&!value.isNull())safe=value.textValue();}
            else if(CONTAINERS.contains(key)){allowed=value.isNull()||value.isObject()||value.isArray();if(value.isObject())safe=object(value,depth+1,count);else if(value.isArray())safe=array(value,depth+1,count);}
            if(allowed)result.put(key,safe);else count.omitted++;
        }return result;
    }
    private List<Object> array(JsonNode node,int depth,Counts count){if(depth>DEPTH){count.truncated++;return List.of();}var result=new ArrayList<Object>();int limit=Math.min(node.size(),ARRAY);if(node.size()>limit){count.items+=node.size()-limit;count.truncated++;}for(int i=0;i<limit;i++){var item=node.get(i);if(item.isObject())result.add(object(item,depth+1,count));else if(item.isArray())result.add(array(item,depth+1,count));else if(item.isIntegralNumber()&&item.canConvertToLong()&&item.longValue()>=0)result.add(item.longValue());else{count.items++;}}return result;}
    String text(String raw,Counts count){if(raw==null)return null;String safe=raw;if(SQL.matcher(safe).find()){safe="[SQL内容已省略]";}else{safe=CREDENTIAL.matcher(safe).replaceAll("[凭证已省略]");safe=EMAIL.matcher(safe).replaceAll("[邮箱已脱敏]");safe=PHONE.matcher(safe).replaceAll("[电话已脱敏]");safe=HASH.matcher(safe).replaceAll("[摘要/哈希已省略]");}if(!safe.equals(raw))count.redacted++;int length=safe.codePointCount(0,safe.length());if(length>TEXT){safe=safe.substring(0,safe.offsetByCodePoints(0,TEXT))+"…";count.truncated++;}return safe;}
    private boolean time(String value){if(value.length()>64)return false;if(value.matches("24:00(?::00(?:\\.0{1,6})?)?"))return true;try{OffsetDateTime.parse(value);return true;}catch(Exception ignored){}try{LocalDateTime.parse(value);return true;}catch(Exception ignored){}try{LocalDate.parse(value);return true;}catch(Exception ignored){}try{LocalTime.parse(value);return true;}catch(Exception ignored){}return false;}
}
