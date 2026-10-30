package cn.studyroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 自动未使用与已核实手动违规的唯一事实写源；累计限制由外层同事务领域编排。 */
@Service
public class ViolationService {
    private final Store db;private final AuthService auth;private final AuditService audit;private final ObjectMapper json;private final Clock clock;
    public ViolationService(Store db,AuthService auth,AuditService audit,ObjectMapper json,Clock clock){this.db=db;this.auth=auth;this.audit=audit;this.json=json;this.clock=clock;}
    @Transactional(propagation=Propagation.MANDATORY)
    public long recordUnused(long userId,long reservationId,String type,LocalDateTime occurredAt,LocalDateTime registeredAt,String reason) {
        db.lockBookings();return db.insert("INSERT INTO violations(user_id,reservation_id,type,event_key,occurred_at,registered_at,reason,source) VALUES(?,?,?,?,?,?,?,'automatic')",userId,reservationId,type,"unused:"+reservationId,Timestamp.valueOf(occurredAt),Timestamp.valueOf(registeredAt),reason);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void notifyUnused(long user,long violation,String reason,LocalDateTime now){notifyViolation(user,violation,"未使用预约已结束",reason+"，已释放时段并登记一条独立违规，可在本人违规记录查看。",now);}
    @Transactional
    public Map<String,Object> registerManual(long actor,long eventId,Requests.ManualViolation request) {return registerManual(actor,eventId,request,now());}
    @Transactional(propagation=Propagation.MANDATORY)
    public Map<String,Object> registerManual(long actor,long eventId,Requests.ManualViolation request,LocalDateTime snapshot) {
        db.lockBookings();requireAdmin(actor);Api.require(request!=null,400,"违规登记参数必填");String operation=AttachmentService.uuid(request.operationId(),"operation_id");var saved=db.list("SELECT id,registrant_id,source_event_id,request_hash FROM violations WHERE operation_id=CAST(? AS UUID)",operation);String reason;
        try{reason=reason(request.reason());}catch(Api.Failure failure){if(!saved.isEmpty())throw Api.fail(409,"操作标识已用于其他内容，请按原请求重试");throw failure;}String hash=hash(actor,eventId,reason);
        if(!saved.isEmpty()){var row=saved.get(0);Api.require(Store.id(row,"registrant_id")==actor&&Store.id(row,"source_event_id")==eventId&&hash.equals(row.get("request_hash")),409,"操作标识已用于其他内容，请按原请求重试");return receipt(Store.id(row,"id"),operation,true);}
        var rows=sources("e.id=?",eventId);Api.require(!rows.isEmpty(),404,"核实事件不存在");var source=rows.get(0);if(source.get("existing_id")!=null)throw duplicate(source,eventId);var now=snapshot;String ineligible=ineligible(source,now);Api.require(ineligible==null,400,ineligible==null?"核实来源不符合登记要求":ineligible);
        long user=Store.id(source,"subject_user_id"),reservation=Store.id(source,"reservation_id");var occurred=LocalDateTime.parse(Store.text(source,"occurred_at"));
        long id=db.insert("INSERT INTO violations(user_id,reservation_id,type,event_key,occurred_at,registered_at,status,reason,source,registrant_id,source_event_id,operation_id,request_hash) VALUES(?,?,'occupation_refusal',?,?,?,'active',?,'manual',?,?,CAST(? AS UUID),?)",user,reservation,"verified_refusal:"+eventId,Timestamp.valueOf(occurred),Timestamp.valueOf(now),reason,actor,eventId,operation,hash);
        long notification=notifyViolation(user,id,"违规通知","管理员登记了经核实的拒不配合占座违规。\n公开事实说明：\n"+reason+"\n可在本人违规记录查看事实说明及登记、通知时间。",now);
        audit.record(actor,"violation.register_manual","violation",Long.toString(id),reason,Map.of(),Map.of("user_id",user,"reservation_id",reservation,"type","occupation_refusal","source_event_id",eventId,"occurred_at",occurred.toString(),"registered_at",now.toString(),"notification_id",notification,"source","manual","status","active"));return receipt(id,operation,false);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> mine(long userId) {return RecordPage.items(records(RecordPage.legacy(Map.of(),RecordPage.Domain.VIOLATION,false),userId,now(),false));}
    Map<String,Object> records(RecordPage query,Long user,LocalDateTime snapshot,boolean admin){var selection=query.select(db,RecordPage.Domain.VIOLATION,user,snapshot);var rows=selection.ids().isEmpty()?new ArrayList<Map<String,Object>>():safeRows(db,"v.id IN ("+RecordPage.marks(selection.ids())+") ORDER BY v.occurred_at DESC,v.id DESC",selection.ids().toArray());if(admin)for(var row:rows){long owner=Store.id(db.one("SELECT user_id FROM violations WHERE id=?",row.get("id")),"user_id");row.put("subject",db.one("SELECT id,username,real_name,campus_id FROM users WHERE id=?",owner));}return query.result(rows,selection.total(),snapshot);}
    static Map<String,Object> safeOne(Store db,long id){var rows=safeRows(db,"v.id=?",id);if(rows.size()!=1)throw new IllegalStateException("自动限制的原始违规来源缺失，请核查数据");return rows.get(0);}
    private static List<Map<String,Object>> safeRows(Store db,String where,Object...args){var rows=db.list("SELECT v.id,CASE WHEN v.source='automatic' THEN v.reservation_id END AS reservation_id,v.type,v.occurred_at,v.registered_at,v.status,v.reason,v.source,COALESCE(NULLIF(u.real_name,''),u.username) AS registrant_display_name,n.id AS notification_id,n.created_at AS notified_at,rv.reason AS revocation_reason,rv.reviewed_at AS revoked_at,COALESCE(NULLIF(ru.real_name,''),ru.username) AS revoker_name FROM violations v LEFT JOIN users u ON u.id=v.registrant_id LEFT JOIN notifications n ON n.violation_id=v.id LEFT JOIN appeal_reviews rv ON rv.violation_id=v.id AND rv.violation_action='revoked' LEFT JOIN users ru ON ru.id=rv.actor_id WHERE "+where,args);for(var row:rows){times(row);Object reason=row.remove("revocation_reason"),at=row.remove("revoked_at"),who=row.remove("revoker_name");row.put("revocation",at==null?null:Map.of("reason",reason,"revoked_at",AttachmentService.offset(at),"revoker_display_name",who));}return rows;}
    @Transactional(propagation=Propagation.MANDATORY)
    public String revokeForAppeal(long actor,long violation,long review,String publicReason,LocalDateTime now){
        db.lockBookings();requireAdmin(actor);var before=db.one("SELECT * FROM violations WHERE id=?",violation);if("revoked".equals(before.get("status")))return "already_revoked";if(db.count("SELECT count(*) FROM appeal_reviews WHERE violation_id=? AND violation_action='revoked'",violation)>0)throw new IllegalStateException("违规状态与已有撤销依据矛盾，请核查数据");String reason=reason(publicReason);int changed=db.update("UPDATE violations SET status='revoked' WHERE id=? AND status='active'",violation);if(changed!=1)throw new IllegalStateException("违规状态已变化，请重新预览");audit.record(actor,"violation.revoke","violation",Long.toString(violation),reason,Map.of("status",before.get("status"),"occurred_at",before.get("occurred_at"),"registered_at",before.get("registered_at")),Map.of("status","revoked","review_id",review,"revoked_at",AttachmentService.offset(now)));return "revoked";
    }
    void decorateManagementEvents(List<Map<String,Object>> events,LocalDateTime now){
        if(events.isEmpty())return;var ids=events.stream().map(event->event.get("id")).toArray();String marks=String.join(",",Collections.nCopies(ids.length,"?"));var byId=new HashMap<Long,Map<String,Object>>();for(var row:sources("e.id IN ("+marks+")",ids))byId.put(Store.id(row,"id"),row);
        for(var event:events){var row=byId.get(Store.id(event,"id"));if(row==null)throw new IllegalStateException("核实事件投影缺失，请核查数据");var registration=new LinkedHashMap<String,Object>();boolean existing=row.get("existing_id")!=null;String why=existing?"同一来源事件或同一事实已有违规记录，含已撤销记录均不重复登记":ineligible(row,now);registration.put("eligible",why==null);registration.put("ineligible_reason",why);registration.put("existing_violation",existing?existing(row):null);event.put("violation_registration",registration);}
    }
    private List<Map<String,Object>> sources(String where,Object...args){return db.list("SELECT e.id,e.event_type,e.outcome,e.occurred_at,e.subject_user_id,e.identity_confirmed,e.identity_basis,e.facts_confirmed,e.valid_reservation_confirmed,e.private_note,c.type AS complaint_type,c.user_id AS reporter_id,c.reservation_id,r.res_date,r.start_time,r.end_time,u.role AS subject_role,v.id AS existing_id,v.source_event_id AS existing_source_event_id,v.status AS existing_status,v.type AS existing_type,v.registered_at AS existing_registered_at,COALESCE(NULLIF(a.real_name,''),a.username) AS existing_registrant_display_name,n.created_at AS existing_notified_at FROM complaint_events e JOIN complaints c ON c.id=e.complaint_id LEFT JOIN reservations r ON r.id=c.reservation_id LEFT JOIN users u ON u.id=e.subject_user_id LEFT JOIN violations ve ON ve.source_event_id=e.id LEFT JOIN violations vf ON vf.source='manual' AND vf.reservation_id=c.reservation_id AND vf.user_id=e.subject_user_id AND vf.type='occupation_refusal' AND vf.occurred_at=e.occurred_at LEFT JOIN violations v ON v.id=COALESCE(ve.id,vf.id) LEFT JOIN users a ON a.id=v.registrant_id LEFT JOIN notifications n ON n.violation_id=v.id WHERE "+where,args);}
    private String ineligible(Map<String,Object> row,LocalDateTime now){
        if(!"verification".equals(row.get("event_type"))||!"verified_refusal".equals(row.get("outcome")))return "仅已核实拒不配合的核实事件可登记占座违规";
        if(!"occupation".equals(row.get("complaint_type"))||row.get("reservation_id")==null)return "核实来源须关联被占投诉的原预约";
        if(row.get("subject_user_id")==null||!Boolean.TRUE.equals(row.get("identity_confirmed"))||AuthService.blankNull(Objects.toString(row.get("identity_basis"),null))==null)return "尚未核实明确对象身份和依据，不能登记违规";
        if(!AuthService.isOrdinary(row.get("subject_role")))return "该对象不是学生或教职工账号，不适用普通用户违规登记";
        if(Store.id(row,"subject_user_id")==Store.id(row,"reporter_id"))return "不能把原预约权利人登记为占用其本人预约的第三方";
        if(!Boolean.TRUE.equals(row.get("facts_confirmed"))||!Boolean.TRUE.equals(row.get("valid_reservation_confirmed"))||AuthService.blankNull(Objects.toString(row.get("private_note"),null))==null)return "尚未明确核实占用有效预约的事实与权利依据";
        if(row.get("occurred_at")==null)return "核实事件缺少真实发生时刻";var occurred=LocalDateTime.parse(Store.text(row,"occurred_at"));var date=LocalDate.parse(Store.text(row,"res_date"));if(occurred.isBefore(Slot.endpoint(date,Store.text(row,"start_time")))||!occurred.isBefore(Slot.endpoint(date,Store.text(row,"end_time"))))return "核实事实发生时刻不在原预约时段内";
        if(occurred.isAfter(now))return "核实事实发生时刻不能在未来";return null;
    }
    private Api.Failure duplicate(Map<String,Object> source,long event){return Api.fail(409,"同一来源事件或同一事实已登记违规，不重复登记",Map.of("code","violation_already_registered","existing_violation_id",source.get("existing_id"),"duplicate_by",Store.id(source,"existing_source_event_id")==event?"source_event":"fact"));}
    private Map<String,Object> existing(Map<String,Object> row){var result=new LinkedHashMap<String,Object>();result.put("id",row.get("existing_id"));result.put("status",row.get("existing_status"));result.put("type",row.get("existing_type"));result.put("registered_at",AttachmentService.offset(row.get("existing_registered_at")));result.put("notified_at",AttachmentService.offset(row.get("existing_notified_at")));result.put("registrant_display_name",row.get("existing_registrant_display_name"));return result;}
    private Map<String,Object> receipt(long id,String operation,boolean replayed){var row=db.one("SELECT v.id,v.user_id,v.reservation_id,v.source_event_id,v.type,v.source,v.status,v.reason,v.occurred_at,v.registered_at,COALESCE(NULLIF(u.real_name,''),u.username) AS registrant_display_name,n.id AS notification_id,n.created_at AS notified_at FROM violations v LEFT JOIN users u ON u.id=v.registrant_id LEFT JOIN notifications n ON n.violation_id=v.id WHERE v.id=?",id);times(row);return Map.of("operation_id",operation,"replayed",replayed,"violation",row);}
    private long notifyViolation(long user,long violation,String title,String content,LocalDateTime now){return db.insert("INSERT INTO notifications(user_id,type,title,content,violation_id,created_at) VALUES(?,'violation',?,?,?,?)",user,title,content,violation,Timestamp.valueOf(now));}
    private static void times(Map<String,Object> row){for(String key:List.of("occurred_at","registered_at","notified_at"))row.put(key,AttachmentService.offset(row.get(key)));}
    private void requireAdmin(long actor){var user=auth.profile(actor);Api.require("admin".equals(user.get("role"))&&"active".equals(user.get("status")),403,"需要启用的管理员权限");}
    private String reason(String value){String reason=value==null?null:value.trim();Api.require(reason!=null&&!reason.isEmpty()&&reason.length()<=1000,400,"面向被登记人的公开事实说明须为1至1000字");return reason;}
    private String hash(long actor,long event,String reason){var intent=new TreeMap<String,Object>();intent.put("actor_id",actor);intent.put("source_event_id",event);intent.put("reason",reason);try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(intent).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),BookingPolicy.CAMPUS_ZONE).truncatedTo(ChronoUnit.MICROS);}
}
