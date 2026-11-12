package cn.studyroom;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 本人提交事实与公开进度的唯一投影；管理私有核实不进入公开DTO。 */
@Service
public class ComplaintService {
    private static final String BASIC="SELECT c.id,c.operation_id,c.type,c.status,c.description,c.reservation_id,c.room_id,c.context_snapshot,c.submitted_at,c.version,c.accepted_at,c.service_kind,c.service_topic,c.fault_type,c.impact_scope,c.safety_risk,c.unsafe_area,c.initial_priority,c.priority_status,c.priority_mapping_version,c.triaged_priority,c.triaged_safety_risk,c.triaged_unsafe_area,c.triaged_reason,c.triaged_at,CASE WHEN c.service_kind IN('facility_repair','other_service') THEN (SELECT e.initial_arrangement FROM complaint_events e WHERE e.complaint_id=c.id AND e.event_type='accepted' ORDER BY e.id LIMIT 1) END AS initial_arrangement,(SELECT COALESCE(NULLIF(h.real_name,''),h.username) FROM users h WHERE h.id=c.handler_id) AS handler_name,count(a.id) AS attachment_count FROM complaints c LEFT JOIN evidence_attachments a ON a.complaint_id=c.id ";
    private final Store db;private final AttachmentService attachments;private final AuditService audit;private final ObjectMapper json;private final Validator validator;private final Clock clock;private final ComplaintPhaseProjection phases;private final ComplaintTargetService targets;private final ComplaintPriorityService priorities;
    public ComplaintService(Store db,AttachmentService attachments,AuditService audit,ObjectMapper json,Validator validator,Clock clock,ComplaintPhaseProjection phases,ComplaintTargetService targets,ComplaintPriorityService priorities) { this.db=db;this.attachments=attachments;this.audit=audit;this.json=json;this.validator=validator;this.clock=clock;this.phases=phases;this.targets=targets;this.priorities=priorities; }
    @Transactional
    public Map<String,Object> create(long actor,Requests.Complaint request) {
        db.lockBookings();attachments.requireOrdinary(actor);Api.require(request!=null,400,"投诉内容必填");String operation=AttachmentService.uuid(request.operationId(),"operation_id");var existing=db.list("SELECT id,user_id,request_hash FROM complaints WHERE operation_id=CAST(? AS UUID)",operation);
        boolean legacyShape=request.serviceKind()==null&&request.serviceTopic()==null&&request.faultType()==null&&request.impactScope()==null&&request.safetyRisk()==null&&request.unsafeArea()==null;
        boolean replay=legacyShape&&!existing.isEmpty();
        Requests.Complaint normalized;try { normalized=normalize(request,operation,replay); } catch(Api.Failure failure) { if(!existing.isEmpty()) throw Api.fail(409,"操作标识已用于其他内容，请按原请求重试");throw failure; }
        String intent=hash(actor,normalized,!legacyShape);
        if(!existing.isEmpty()) { var saved=existing.get(0);Api.require(Store.id(saved,"user_id")==actor&&intent.equals(saved.get("request_hash")),409,"操作标识已用于其他内容，不能修改原投诉请求");var result=detailInternal(Store.id(saved,"id"));result.put("replayed",true);return result; }
        Map<String,Object> context=context(actor,normalized);Long room=context==null?null:Store.id(context,"room_id");var now=LocalDateTime.ofInstant(clock.instant(),BookingPolicy.CAMPUS_ZONE).truncatedTo(ChronoUnit.MICROS);var classification="facility_repair".equals(normalized.serviceKind())?classification(normalized):null;
        long id=db.insert("INSERT INTO complaints(user_id,operation_id,request_hash,type,status,description,reservation_id,room_id,context_snapshot,submitted_at,service_kind,service_topic,fault_type,impact_scope,safety_risk,unsafe_area,initial_priority,priority_status,priority_mapping_version) VALUES(?,CAST(? AS UUID),?,?,'submitted',?,?,?,CAST(? AS JSONB),?,?,?,?,?,?,?,?,?,?)",actor,operation,intent,normalized.type(),normalized.description(),normalized.reservationId(),room,context==null?null:write(context),java.sql.Timestamp.valueOf(now),normalized.serviceKind(),normalized.serviceTopic(),normalized.faultType(),normalized.impactScope(),normalized.safetyRisk(),normalized.unsafeArea(),classification==null?null:classification.priority(),classification==null?null:classification.status(),classification==null?null:classification.mappingVersion());
        var targetSnapshot=targets.initialize(id,normalized.type(),room,now,normalized.serviceKind());
        db.update("INSERT INTO complaint_events(complaint_id,actor_id,event_type,source,created_at) VALUES(?,?,'submitted','user_submit',?)",id,actor,java.sql.Timestamp.valueOf(now));attachments.bind(actor,id,normalized.attachmentIds(),now);
        var evidence=new LinkedHashMap<String,Object>();evidence.put("complaint_id",id);evidence.put("type",normalized.type());evidence.put("service_kind",normalized.serviceKind());evidence.put("status","submitted");evidence.put("reservation_id",normalized.reservationId());evidence.put("room_id",room);evidence.put("attachment_count",normalized.attachmentIds().size());evidence.put("initial_priority",classification==null?null:classification.priority());evidence.put("priority_status",classification==null?null:classification.status());evidence.put("service_target",targetSnapshot);evidence.put("submitted_at",now.toString());
        audit.record(actor,"complaint.submit","complaint",Long.toString(id),"用户提交求助投诉，当前待受理且内容尚未核实",Map.of(),evidence);var result=detailInternal(id);result.put("replayed",false);return result;
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> mine(long actor) {return mineAt(actor,now());}
    List<Map<String,Object>> mineAt(long actor,LocalDateTime now) {attachments.requireOrdinary(actor);var selected=RecordPage.legacy(Map.of(),RecordPage.Domain.COMPLAINT,false).select(db,RecordPage.Domain.COMPLAINT,actor,now);return pageItems(selected.ids(),now);}
    List<Map<String,Object>> pageItems(List<Long> ids,LocalDateTime now){var rows=ids.isEmpty()?new ArrayList<Map<String,Object>>():summariesInternal("WHERE c.id IN ("+RecordPage.marks(ids)+")",ids.toArray());phases.decorate(rows,now,false);return rows;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> detail(long actor,long complaint) {return detailAt(actor,complaint,now());}
    Map<String,Object> detailAt(long actor,long complaint,LocalDateTime now) {attachments.requireOrdinary(actor);Api.require(Store.id(db.one("SELECT user_id FROM complaints WHERE id=?",complaint),"user_id")==actor,403,"无权读取他人的投诉");return detailInternal(complaint,now);}
    Map<String,Object> summaryInternal(long id) {return basic(db.one(BASIC+"WHERE c.id=? GROUP BY c.id",id));}
    List<Map<String,Object>> summariesInternal(String where,Object...args) {return db.list(BASIC+where+" GROUP BY c.id ORDER BY c.submitted_at DESC,c.id DESC",args).stream().map(this::basic).toList();}
    Map<String,Object> detailInternal(long complaint) {return detailInternal(complaint,now());}
    Map<String,Object> detailInternal(long complaint,LocalDateTime now) {
        var result=summaryInternal(complaint);result.put("attachments",attachments.boundMetadata(complaint));var events=db.list("SELECT e.id,e.event_type AS type,e.created_at,e.occurred_at,e.outcome,e.public_note,e.initial_arrangement,e.source,e.feedback_result AS result,e.feedback_basis AS basis,e.response_note AS description,e.related_feedback_id,e.feedback_deadline,e.triage_priority,e.triage_safety_risk,e.triage_unsafe_area,e.triage_reason,e.service_progress_stage,e.maintenance_channel,e.handoff_at,CASE WHEN e.source='admin_self_report' THEN COALESCE(NULLIF(u.real_name,''),u.username) END AS actor_display_name FROM complaint_events e LEFT JOIN users u ON u.id=e.actor_id WHERE e.complaint_id=? ORDER BY e.created_at,e.id",complaint);for(var event:events) { event.put("created_at",AttachmentService.offset(event.get("created_at")));event.put("occurred_at",AttachmentService.offset(event.get("occurred_at")));event.put("feedback_deadline",AttachmentService.offset(event.get("feedback_deadline")));event.put("handoff_at",AttachmentService.offset(event.get("handoff_at"))); }result.put("events",events);phases.decorate(result,now,false);return result;
    }
    private LocalDateTime now() {return LocalDateTime.ofInstant(clock.instant(),BookingPolicy.CAMPUS_ZONE).truncatedTo(ChronoUnit.MICROS);}
    private Map<String,Object> basic(Map<String,Object> row) { row.put("accepted_at",AttachmentService.offset(row.get("accepted_at")));row.put("triaged_at",AttachmentService.offset(row.get("triaged_at")));Object handler=row.remove("handler_name");row.put("handler",handler==null?null:Map.of("display_name",handler)); row.put("operation_id",row.get("operation_id").toString());row.put("submitted_at",AttachmentService.offset(row.get("submitted_at")));row.put("context_snapshot",row.get("context_snapshot")==null?null:read(row.get("context_snapshot").toString()));return row; }
    private Requests.Complaint normalize(Requests.Complaint request,String operation,boolean allowLegacyReplay) {
        String type=request.type()==null?null:request.type().trim(),description=request.description()==null?null:request.description().trim();var ids=new ArrayList<String>();if(request.attachmentIds()!=null) for(String id:request.attachmentIds()) ids.add(AttachmentService.uuid(id,"材料编号"));Api.require(ids.size()<=3&&new HashSet<>(ids).size()==ids.size(),400,"每条投诉最多3个不重复材料");
        String serviceKind=blank(request.serviceKind()),topic=blank(request.serviceTopic()),fault=blank(request.faultType()),scope=blank(request.impactScope());Boolean safety=request.safetyRisk(),unsafe=request.unsafeArea();
        if("occupation".equals(type)){Api.require(serviceKind==null&&topic==null&&fault==null&&scope==null&&safety==null&&unsafe==null,400,"座位求助不接受服务分类或报修分级字段");Api.require(request.reservationId()!=null,400,"被占求助必须关联本人预约");}
        else if(allowLegacyReplay&&serviceKind==null&&topic==null&&fault==null&&scope==null&&safety==null&&unsafe==null){}
        else {Api.require(serviceKind!=null&&Set.of("facility_repair","other_service").contains(serviceKind),400,"请选择设施报修或其他服务问题");Api.require(request.roomId()!=null,400,"设施报修与其他服务问题必须选择所属空间");Api.require(request.reservationId()==null,400,"设施报修与其他服务问题不关联预约");if("facility_repair".equals(serviceKind)){Api.require(topic==null&&fault!=null&&fault.length()<=100&&scope!=null&&scope.length()<=100&&safety!=null&&unsafe!=null,400,"报修须填写故障类型、影响范围及安全风险/区域可用情况");}else{Api.require(topic!=null&&ids.isEmpty()&&fault==null&&scope==null&&safety==null&&unsafe==null,400,"其他服务问题须填写诉求类别，不接收报修照片或分级字段");}}
        var result=new Requests.Complaint(type,request.reservationId(),request.roomId(),description,List.copyOf(ids),operation,serviceKind,topic,fault,scope,safety,unsafe);var errors=validator.validate(result);Api.require(errors.isEmpty(),400,errors.stream().map(error->error.getPropertyPath()+": "+error.getMessage()).sorted().findFirst().orElse("投诉参数错误"));
        if("service".equals(type)&&serviceKind!=null){var roomId=Objects.requireNonNull(request.roomId());db.one("SELECT id FROM rooms WHERE id=?",roomId);if("facility_repair".equals(serviceKind))classification(result);}
        return result;
    }
    private Map<String,Object> context(long actor,Requests.Complaint request) {
        if(request.reservationId()!=null) {
            var row=db.one("SELECT rv.id AS reservation_id,rv.user_id,rv.res_date,rv.start_time,rv.end_time,rv.status,s.id AS seat_id,s.seat_no,r.id AS room_id,r.name AS room_name,r.building,r.floor,r.location FROM reservations rv JOIN seats s ON s.id=rv.seat_id JOIN rooms r ON r.id=s.room_id WHERE rv.id=?",request.reservationId());Api.require(Store.id(row,"user_id")==actor,403,"只能关联本人的预约");Api.require(request.roomId()==null||request.roomId()==Store.id(row,"room_id"),400,"投诉空间与本人预约不一致");
            var context=new LinkedHashMap<String,Object>();for(String key:List.of("reservation_id","room_id","room_name","seat_id","seat_no","building","floor","location")) context.put(key,row.get(key));context.put("date",row.get("res_date"));context.put("start_time",row.get("start_time"));context.put("end_time",row.get("end_time"));context.put("status_at_submission",row.get("status"));return context;
        }
        if(request.roomId()==null) return null;return db.one("SELECT id AS room_id,name AS room_name,building,floor,location FROM rooms WHERE id=?",request.roomId());
    }
    private String hash(long actor,Requests.Complaint request) {
        return hash(actor,request,true);
    }
    private String hash(long actor,Requests.Complaint request,boolean includeService){
        var intent=new TreeMap<String,Object>();intent.put("actor_id",actor);intent.put("type",request.type());intent.put("reservation_id",request.reservationId());intent.put("room_id",request.roomId());intent.put("description",request.description());intent.put("attachment_ids",request.attachmentIds());if(includeService){intent.put("service_kind",request.serviceKind());intent.put("service_topic",request.serviceTopic());intent.put("fault_type",request.faultType());intent.put("impact_scope",request.impactScope());intent.put("safety_risk",request.safetyRisk());intent.put("unsafe_area",request.unsafeArea());}
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(write(intent).getBytes(StandardCharsets.UTF_8))); } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private ComplaintPriorityService.Classification classification(Requests.Complaint request){return priorities.classify(request.faultType(),request.impactScope(),Boolean.TRUE.equals(request.safetyRisk()),Boolean.TRUE.equals(request.unsafeArea()));}
    private String blank(String value){return AuthService.blankNull(value==null?null:value.trim());}
    private String write(Object value) { try { return json.writeValueAsString(value); } catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("投诉记录不能序列化",e); } }
    private Object read(String value) { try { return json.readValue(value,new TypeReference<LinkedHashMap<String,Object>>() {}); } catch(java.io.IOException e) { throw new IllegalStateException("投诉上下文记录无法读取",e); } }
}
