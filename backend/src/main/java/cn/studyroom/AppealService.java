package cn.studyroom;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Appeal submission and projections do not settle reservations or evaluate penalties. */
@Service
public class AppealService {
    private final Store db;private final AttachmentService attachments;private final AuditService audit;private final ObjectMapper json;private final Clock clock;
    public AppealService(Store db,AttachmentService attachments,AuditService audit,ObjectMapper json,Clock clock){this.db=db;this.attachments=attachments;this.audit=audit;this.json=json;this.clock=clock;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> eligibility(long actor,long violation){attachments.requireOrdinary(actor);ownViolation(actor,violation);var now=now();var safe=ViolationService.safeOne(db,violation);var result=envelope(now);result.put("violation",safe);result.put("eligibility",eligibilityInternal(actor,violation,safe,now));return result;}
    @Transactional
    public Map<String,Object> create(long actor,long violation,Requests.Appeal request){
        db.lockBookings();attachments.requireOrdinary(actor);Api.require(request!=null,400,"申诉内容必填");String operation=AttachmentService.uuid(request.operationId(),"operation_id");var existing=db.list("SELECT id,user_id,violation_id,request_hash FROM appeals WHERE operation_id=CAST(? AS UUID)",operation);
        String description;List<String> ids;try{description=request.description()==null?null:request.description().trim();Api.require(description!=null&&!description.isEmpty()&&description.length()<=2000,400,"申诉说明须为1至2000字");var normalized=new ArrayList<String>();if(request.attachmentIds()!=null)for(String raw:request.attachmentIds())normalized.add(AttachmentService.uuid(raw,"材料编号"));Api.require(normalized.size()<=3&&new HashSet<>(normalized).size()==normalized.size(),400,"每条申诉最多3个不重复材料");ids=List.copyOf(normalized);}catch(Api.Failure failure){if(!existing.isEmpty())throw Api.fail(409,"操作标识已用于其他内容，请按原请求重试");throw failure;}
        var intent=new TreeMap<String,Object>();intent.put("actor_id",actor);intent.put("violation_id",violation);intent.put("description",description);intent.put("attachment_ids",ids);String hash=hash(intent);var now=now();
        if(!existing.isEmpty()){var saved=existing.get(0);Api.require(Store.id(saved,"user_id")==actor&&Store.id(saved,"violation_id")==violation&&hash.equals(saved.get("request_hash")),409,"操作标识已用于其他内容，请按原请求重试");return receipt(Store.id(saved,"id"),operation,true,now);}
        ownViolation(actor,violation);var safe=ViolationService.safeOne(db,violation);var eligibility=eligibilityInternal(actor,violation,safe,now);
        if(!Boolean.TRUE.equals(eligibility.get("can_submit"))){if("pending".equals(eligibility.get("blocked_by")))throw Api.fail(409,"该违规已有待审核申诉，请查看原申诉进度",Map.of("code","appeal_pending","existing_appeal",eligibility.get("existing_appeal")));var details=envelope(now);details.put("code","appeal_not_eligible");details.put("eligibility",eligibility);throw Api.fail(409,blockedMessage(Store.text(eligibility,"blocked_by")),details);}
        long id=db.insert("INSERT INTO appeals(user_id,violation_id,operation_id,request_hash,description,submitted_at,notification_id,notified_at,deadline,violation_snapshot) VALUES(?,?,CAST(? AS UUID),?,?,?,?,?,?,CAST(? AS JSONB))",actor,violation,operation,hash,description,Timestamp.valueOf(now),eligibility.get("notification_id"),Timestamp.valueOf(local(eligibility.get("notified_at"))),Timestamp.valueOf(local(eligibility.get("deadline"))),write(safe));
        db.update("INSERT INTO appeal_events(appeal_id,actor_id,event_type,source,occurred_at) VALUES(?,?,'submitted','user_submit',?)",id,actor,Timestamp.valueOf(now));attachments.bind(actor,AttachmentService.Purpose.APPEAL,id,ids,now);
        audit.record(actor,"appeal.submit","appeal",Long.toString(id),"本人提交申诉，当前待审核，原违规与限制保持",Map.of(),Map.of("violation_id",violation,"notification_id",eligibility.get("notification_id"),"notified_at",eligibility.get("notified_at"),"deadline",eligibility.get("deadline"),"submitted_at",AttachmentService.offset(now),"attachment_count",ids.size(),"status","submitted"));return receipt(id,operation,false,now);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<Map<String,Object>> mine(long actor){attachments.requireOrdinary(actor);var now=now();return db.list("SELECT id FROM appeals WHERE user_id=? ORDER BY submitted_at DESC,id DESC",actor).stream().map(row->detailInternal(Store.id(row,"id"),now,false)).toList();}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> detail(long actor,long id){attachments.requireOrdinary(actor);Api.require(Store.id(db.one("SELECT user_id FROM appeals WHERE id=?",id),"user_id")==actor,403,"无权读取他人的申诉");return detailInternal(id,now(),false);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<Map<String,Object>> listAdmin(long actor,String status){admin(actor);Api.require(status==null||status.equals("submitted"),400,"申诉状态筛选仅接受submitted");var now=now();return db.list("SELECT id FROM appeals WHERE status='submitted' ORDER BY submitted_at,id").stream().map(row->detailInternal(Store.id(row,"id"),now,true)).toList();}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> detailAdmin(long actor,long id){admin(actor);var result=detailInternal(id,now(),true);result.put("management_context",managementContext(((Number)result.get("violation_id")).longValue()));return result;}
    private Map<String,Object> managementContext(long violation){
        var row=db.one("SELECT v.id,v.user_id,v.reservation_id,v.source_event_id,v.source,v.status,v.type,v.reason,v.occurred_at,v.registered_at,v.registrant_id,e.complaint_id AS source_complaint_id FROM violations v LEFT JOIN complaint_events e ON e.id=v.source_event_id WHERE v.id=?",violation);
        var result=new LinkedHashMap<String,Object>();result.put("source_complaint_id",row.remove("source_complaint_id"));for(String key:List.of("occurred_at","registered_at"))row.put(key,AttachmentService.offset(row.get(key)));result.put("violation",row);return result;
    }
    private void ownViolation(long actor,long violation){Api.require(Store.id(db.one("SELECT user_id FROM violations WHERE id=?",violation),"user_id")==actor,403,"只能查看或申诉本人的违规");}
    private Map<String,Object> eligibilityInternal(long actor,long violation,Map<String,Object> safe,LocalDateTime now){
        var pending=db.list("SELECT id,status,submitted_at FROM appeals WHERE violation_id=? AND user_id=? AND status='submitted' ORDER BY id LIMIT 1",violation,actor);var notifications=db.list("SELECT id,created_at FROM notifications WHERE violation_id=? AND user_id=? AND type='violation'",violation,actor);
        Map<String,Object> existing=null;if(!pending.isEmpty()){existing=new LinkedHashMap<>(pending.get(0));existing.put("submitted_at",AttachmentService.offset(existing.get("submitted_at")));}
        LocalDateTime notified=notifications.isEmpty()?null:LocalDateTime.parse(Store.text(notifications.get(0),"created_at")),deadline=notified==null?null:notified.plusHours(168);
        String blocked=!"active".equals(safe.get("status"))?"revoked":existing!=null?"pending":notified==null?"notification_unknown":now.isBefore(notified)?"before_notification":!now.isBefore(deadline)?"deadline_passed":null;
        var result=new LinkedHashMap<String,Object>();result.put("can_submit",blocked==null);result.put("blocked_by",blocked);result.put("notification_id",notifications.isEmpty()?null:notifications.get(0).get("id"));result.put("notified_at",AttachmentService.offset(notified));result.put("deadline",AttachmentService.offset(deadline));result.put("existing_appeal",existing);return result;
    }
    private Map<String,Object> receipt(long id,String operation,boolean replayed,LocalDateTime now){var result=envelope(now);result.put("operation_id",operation);result.put("replayed",replayed);result.put("appeal",detailInternal(id,now,false));return result;}
    private Map<String,Object> detailInternal(long id,LocalDateTime now,boolean admin){
        var row=db.one("SELECT * FROM appeals WHERE id=?",id);var result=envelope(now);for(String key:List.of("id","violation_id","status","version","description"))result.put(key,row.get(key));result.put("submitted_at",AttachmentService.offset(row.get("submitted_at")));result.put("notification_basis",Map.of("id",row.get("notification_id"),"notified_at",AttachmentService.offset(row.get("notified_at")),"deadline",AttachmentService.offset(row.get("deadline"))));result.put("violation_snapshot",read(Store.text(row,"violation_snapshot")));result.put("attachments",attachments.boundMetadata(AttachmentService.Purpose.APPEAL,id));var events=db.list("SELECT e.id,e.event_type AS type,e.occurred_at,COALESCE(NULLIF(u.real_name,''),u.username) AS actor_display_name FROM appeal_events e JOIN users u ON u.id=e.actor_id WHERE e.appeal_id=? ORDER BY e.occurred_at,e.id",id);for(var event:events)event.put("occurred_at",AttachmentService.offset(event.get("occurred_at")));result.put("events",events);if(admin)result.put("subject",db.one("SELECT id,username,real_name,campus_id,role,status FROM users WHERE id=?",row.get("user_id")));return result;
    }
    private void admin(long actor){Api.require("admin".equals(attachments.requireActive(actor).get("role")),403,"需要启用的管理员权限");}
    private Map<String,Object> envelope(LocalDateTime now){var result=new LinkedHashMap<String,Object>();result.put("server_now",AttachmentService.offset(now));result.put("campus_zone",BookingPolicy.CAMPUS_ZONE.getId());return result;}
    private String blockedMessage(String reason){return switch(reason){case "revoked"->"该违规已撤销，不能新提交申诉";case "notification_unknown"->"缺少可靠违规通知时间，无法确认线上申诉期限，请联系管理员核查";case "before_notification"->"尚未到违规通知时刻，暂不能提交申诉";case "deadline_passed"->"原违规通知起算的7天申诉期限已过，未提交申诉";default->"当前不能提交申诉";};}
    private LocalDateTime local(Object value){return OffsetDateTime.parse(value.toString()).atZoneSameInstant(BookingPolicy.CAMPUS_ZONE).toLocalDateTime();}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),BookingPolicy.CAMPUS_ZONE).truncatedTo(ChronoUnit.MICROS);}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("申诉快照无法序列化",e);}}
    private Object read(String value){try{return json.readValue(value,new TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception e){throw new IllegalStateException("申诉快照无法读取",e);}}
    private String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(write(value).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
