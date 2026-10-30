package cn.studyroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 限制动作与预约善后薄编排；Policy不反注预约，旧操作重放只读当前投影。 */
@Service
public class RestrictionWorkflow {
    private final Store db;private final AuthService auth;private final RestrictionPolicy policy;private final BookingService bookings;private final ViolationService violations;private final ObjectMapper json;private final Clock clock;
    public RestrictionWorkflow(Store db,AuthService auth,RestrictionPolicy policy,BookingService bookings,ViolationService violations,ObjectMapper json,Clock clock){this.db=db;this.auth=auth;this.policy=policy;this.bookings=bookings;this.violations=violations;this.json=json;this.clock=clock;}
    @Transactional
    public Map<String,Object> registerManualViolation(long actor,long event,Requests.ManualViolation request){db.lockBookings();var snapshot=now();var result=violations.registerManual(actor,event,request,snapshot);if(Boolean.TRUE.equals(result.get("replayed")))return result;@SuppressWarnings("unchecked") var violation=(Map<String,Object>)result.get("violation");bookings.enforceRestrictions(Store.id(violation,"user_id"),snapshot,actor,null);return result;}
    @Transactional
    public Map<String,Object> create(long actor,Requests.RestrictionCreate request){
        db.lockBookings();admin(actor);Api.require(request!=null,400,"限制参数必填");String operation=AttachmentService.uuid(request.operationId(),"operation_id");var previous=policy.operation(operation);String reason,note;LocalDateTime end;
        try{reason=text(request.reason(),1000,"公开限制原因");note=text(request.verificationNote(),2000,"私有核实依据");confirmed(request.factsConfirmed());Api.require(request.userId()!=null&&request.userId()>0,400,"被限制用户必填且须正整数");end=end(request.endsAt());}catch(Api.Failure failure){if(previous!=null)throw Api.fail(409,"操作标识已用于其他内容，请按原请求重试");throw failure;}
        var intent=intent(actor,"create",request.userId(),null,reason,note);intent.put("ends_at",RestrictionPolicy.offset(end));String hash=hash(intent);var now=now();if(previous!=null)return replay(previous,actor,"create",hash,operation,now);
        var subject=db.one("SELECT role FROM users WHERE id=?",request.userId());Api.require(AuthService.isOrdinary(subject.get("role")),400,"手动预约限制只适用于学生和教职工账号");Api.require(end.isAfter(now),400,"限制截止必须晚于当前服务器时刻");long id=policy.create(actor,request.userId(),now,end,reason,note);var effects=bookings.enforceRestrictions(request.userId(),now,actor,id);policy.saveOperation(operation,id,actor,"create",hash,effects,now);return receipt(id,operation,false,effects,now);
    }
    @Transactional
    public Map<String,Object> release(long actor,long id,Requests.RestrictionRelease request){
        db.lockBookings();admin(actor);Api.require(request!=null,400,"解除参数必填");String operation=AttachmentService.uuid(request.operationId(),"operation_id");var previous=policy.operation(operation);String reason,note;
        try{reason=text(request.reason(),1000,"公开解除原因");note=text(request.verificationNote(),2000,"私有核实依据");confirmed(request.factsConfirmed());Api.require(request.version()!=null&&request.version()>0,400,"限制版本必填且须正整数");}catch(Api.Failure failure){if(previous!=null)throw Api.fail(409,"操作标识已用于其他内容，请按原请求重试");throw failure;}
        String hash=hash(intent(actor,"release",id,request.version(),reason,note));var now=now();if(previous!=null)return replay(previous,actor,"release",hash,operation,now);var before=policy.required(id);Api.require(Store.id(before,"version")==request.version(),409,"限制已更新，请刷新版本");
        boolean active=before.get("released_at")==null&&!now.isBefore(LocalDateTime.parse(Store.text(before,"starts_at")))&&now.isBefore(LocalDateTime.parse(Store.text(before,"ends_at")));
        if(!active)throw Api.fail(409,"该条限制已到期、已解除或尚未生效，未执行解除",Map.of("code","restriction_not_active","restriction",policy.detail(id,now,false),"eligibility",policy.eligibility(Store.id(before,"user_id"),now)));
        long user=Store.id(before,"user_id");var first=bookings.reconcileForRestriction(user,now);policy.release(actor,before,reason,note,now);var effects=bookings.enforceRestrictions(user,now,actor,null);effects.put("settled_unused_count",((Number)first.get("settled_unused_count")).longValue()+((Number)effects.get("settled_unused_count")).longValue());policy.saveOperation(operation,id,actor,"release",hash,effects,now);return receipt(id,operation,false,effects,now);
    }
    @Transactional
    public List<Map<String,Object>> mine(long actor){db.lockBookings();auth.profile(actor);var now=now();bookings.enforceRestrictions(actor,now,null,null);return policy.list(actor,null,now,false);}
    @Transactional
    public Map<String,Object> eligibility(long actor){db.lockBookings();auth.profile(actor);var now=now();bookings.enforceRestrictions(actor,now,null,null);return policy.eligibility(actor,now);}
    @Transactional
    public List<Map<String,Object>> list(long actor,Long user,String status){db.lockBookings();admin(actor);RestrictionPolicy.validateFilter(user,status);var now=now();if(user!=null){db.one("SELECT id FROM users WHERE id=?",user);bookings.enforceRestrictions(user,now,null,null);}else bookings.settleRestrictions(now);return policy.list(user,status,now,true);}
    @Transactional
    public Map<String,Object> records(long actor,Map<String,String> parameters,boolean management){var query=RecordPage.parse(parameters,RecordPage.Domain.RESTRICTION,management);db.lockBookings();if(management)admin(actor);else auth.profile(actor);var snapshot=now();Long user=management?query.user():Long.valueOf(actor);if(user==null)bookings.settleRestrictions(snapshot);else if(db.count("SELECT count(*) FROM users WHERE id=?",user)>0)bookings.enforceRestrictions(user,snapshot,null,null);return policy.records(query,user,snapshot,management);}
    @Transactional
    public Map<String,Object> detail(long actor,long id){db.lockBookings();auth.profile(actor);var row=policy.required(id);Api.require(Store.id(row,"user_id")==actor,403,"无权读取他人限制");bookings.enforceRestrictions(actor,now(),null,null);return policy.detail(id,now(),false);}
    @Transactional
    public Map<String,Object> managementDetail(long actor,long id){db.lockBookings();admin(actor);var before=policy.required(id);var snapshot=now();bookings.enforceRestrictions(Store.id(before,"user_id"),snapshot,null,null);return policy.detail(id,snapshot,true);}
    @Transactional
    public void settle(){db.lockBookings();bookings.settleRestrictions(now());}
    private Map<String,Object> replay(Map<String,Object> previous,long actor,String action,String hash,String operation,LocalDateTime now){Api.require(Store.id(previous,"actor_id")==actor&&action.equals(previous.get("action"))&&hash.equals(previous.get("request_hash")),409,"操作标识已用于其他内容，请按原请求重试");return receipt(Store.id(previous,"restriction_id"),operation,true,policy.effects(previous),now);}
    private Map<String,Object> receipt(long id,String operation,boolean replayed,Map<String,Object> effects,LocalDateTime now){var row=policy.required(id);return Map.of("operation_id",operation,"replayed",replayed,"restriction",policy.detail(id,now,true),"effects",effects,"eligibility",policy.eligibility(Store.id(row,"user_id"),now));}
    private TreeMap<String,Object> intent(long actor,String action,long target,Long version,String reason,String note){var result=new TreeMap<String,Object>();result.put("actor_id",actor);result.put("action",action);result.put("target_id",target);result.put("version",version);result.put("reason",reason);result.put("verification_note",note);result.put("facts_confirmed",true);return result;}
    private String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private void admin(long actor){var user=auth.profile(actor);Api.require("admin".equals(user.get("role"))&&"active".equals(user.get("status")),403,"需要启用的管理员权限");}
    private void confirmed(Boolean confirmed){Api.require(Boolean.TRUE.equals(confirmed),400,"必须由管理员明确自报核实事实与依据，系统不代为核实");}
    private String text(String raw,int max,String label){String value=raw==null?null:raw.trim();Api.require(value!=null&&!value.isEmpty()&&value.length()<=max,400,label+"须为1至"+max+"字");return value;}
    private LocalDateTime end(String raw){try{var parsed=OffsetDateTime.parse(raw);Api.require(parsed.getNano()%1000==0,400,"限制截止最多支持微秒精度");return parsed.atZoneSameInstant(BookingPolicy.CAMPUS_ZONE).toLocalDateTime();}catch(java.time.format.DateTimeParseException|NullPointerException e){throw Api.fail(400,"限制截止须为含明确offset的ISO时间");}}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),BookingPolicy.CAMPUS_ZONE).truncatedTo(ChronoUnit.MICROS);}
}
