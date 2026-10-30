package cn.studyroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Read-only bounded views of the existing audit facts; never settles or emits events. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class AuditQueryService {
    private static final String METADATA="SELECT a.id,a.actor_id,u.username AS actor_username,a.action,a.object_type,a.object_id,a.reason,a.occurred_at";
    private static final String FROM=" FROM audit_events a LEFT JOIN users u ON u.id=a.actor_id";
    private final Store db;private final AuthService auth;private final Clock clock;private final AuditSafeProjection safe;
    public AuditQueryService(Store db,AuthService auth,Clock clock,ObjectMapper json){this.db=db;this.auth=auth;this.clock=clock;this.safe=new AuditSafeProjection(json);}
    public Map<String,Object> list(long actor,Map<String,String> params){
        admin(actor);Api.require(Set.of("page","page_size","date_from","date_to","actor_id","object_type","object_id","action").containsAll(params.keySet()),400,"审计查询包含不允许的参数");int page=number(params.getOrDefault("page","1")),size=number(params.getOrDefault("page_size","20"));Api.require(size<=50,400,"每页最多50项");LocalDate from=date(params.get("date_from")),to=date(params.get("date_to"));Api.require(from==null||to==null||!from.isAfter(to),400,"开始日期不得晚于结束日期");var where=new StringBuilder(" WHERE 1=1");var args=new ArrayList<Object>();
        if(from!=null){where.append(" AND a.occurred_at>=(CAST(? AS DATE)::timestamp AT TIME ZONE 'Asia/Shanghai')");args.add(from.toString());}if(to!=null){where.append(" AND a.occurred_at<((CAST(? AS DATE)+INTERVAL '1 day') AT TIME ZONE 'Asia/Shanghai')");args.add(to.toString());}
        if(params.containsKey("actor_id")){where.append(" AND a.actor_id=?");args.add(identifier(params.get("actor_id")));}for(String key:List.of("object_type","action","object_id")){if(!params.containsKey(key))continue;String value=params.get(key);if(key.equals("object_id"))Api.require(value!=null&&!value.isBlank()&&value.length()<=100,400,"对象标识须为1至100字");else Api.require(value!=null&&value.matches("[A-Za-z][A-Za-z0-9_.:-]{0,79}"),400,"动作或对象类型格式无效");where.append(" AND a."+key+"=?");args.add(value);}
        long total=db.count("SELECT count(*) FROM audit_events a"+where,args.toArray());args.add(size);args.add((long)(page-1)*size);var items=db.list(METADATA+FROM+where+" ORDER BY a.occurred_at DESC,a.id DESC LIMIT ? OFFSET ?",args.toArray()).stream().map(row->metadata(row,new AuditSafeProjection.Counts())).toList();return Map.of("items",items,"total",total,"page",page,"page_size",size,"server_now",now());
    }
    public Map<String,Object> detail(long actor,long id){admin(actor);var row=db.one(METADATA+",a.before_values,a.after_values"+FROM+" WHERE a.id=?",id);var counts=new AuditSafeProjection.Counts();var result=metadata(row,counts);result.put("before_values",safe.snapshot(row.get("before_values"),counts));result.put("after_values",safe.snapshot(row.get("after_values"),counts));result.put("redaction",counts.view());return result;}
    private Map<String,Object> metadata(Map<String,Object> row,AuditSafeProjection.Counts counts){var result=new LinkedHashMap<String,Object>();for(String key:List.of("id","actor_id"))result.put(key,row.get(key));result.put("actor_username",safe.text((String)row.get("actor_username"),counts));for(String key:List.of("action","object_type","object_id")){String value=Store.text(row,key);boolean identifier=value.matches(key.equals("object_id")?"[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}":"[A-Za-z][A-Za-z0-9_.:-]{0,79}");if(!identifier){value="[历史标识已省略]";counts.omitted++;}result.put(key,safe.text(value,counts));}result.put("reason",safe.text(Store.text(row,"reason"),counts));result.put("occurred_at",OffsetDateTime.parse(Store.text(row,"occurred_at")).atZoneSameInstant(BookingPolicy.CAMPUS_ZONE).toOffsetDateTime().toString());result.put("redaction",counts.view());return result;}
    private void admin(long actor){var user=auth.profile(actor);Api.require("admin".equals(user.get("role"))&&"active".equals(user.get("status")),403,"仅启用管理员可查询审计");}
    private int number(String value){long parsed=identifier(value);Api.require(parsed<=Integer.MAX_VALUE,400,"页码超出范围");return (int)parsed;}
    private long identifier(String value){try{Api.require(value!=null&&value.matches("[0-9]+"),400,"页码或操作者须为正整数");long parsed=Long.parseLong(value);Api.require(parsed>0,400,"页码或操作者须为正整数");return parsed;}catch(NumberFormatException e){throw Api.fail(400,"页码或操作者超出范围");}}
    private LocalDate date(String value){if(value==null)return null;try{Api.require(value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"),400,"日期须为正四位年ISO自然日");var date=LocalDate.parse(value);Api.require(date.getYear()>0,400,"日期年份须为0001至9999");return date;}catch(java.time.format.DateTimeParseException e){throw Api.fail(400,"日期无效");}}
    private String now(){return clock.instant().atZone(BookingPolicy.CAMPUS_ZONE).toOffsetDateTime().truncatedTo(ChronoUnit.MICROS).toString();}
}
