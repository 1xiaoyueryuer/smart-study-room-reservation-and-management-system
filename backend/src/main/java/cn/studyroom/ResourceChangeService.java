package cn.studyroom;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 只编排既有资源写源/预约取消/使用结算，不提供force或第二套开放规则。 */
@Service
public class ResourceChangeService {
    private static final Set<String> TYPES=Set.of("room.update","seat.update","opening.save","opening.delete_exception","block.create","block.update","block.cancel");
    private static final TypeReference<LinkedHashMap<String,Object>> MAP=new TypeReference<>() {};
    private record Normalized(Requests.ResourceChangeProposal proposal,Object body) {}
    private record Projection(Normalized normalized,long roomId,String label,Map<String,Object> versions,List<Map<String,Object>> pending,List<Map<String,Object>> inUse,String hash) {}
    private final Store db;private final AuthService auth;private final ResourceService resources;private final OpeningScheduleService opening;private final BookingService bookings;private final AuditService audit;private final ObjectMapper json;private final Validator validator;private final Clock clock;
    public ResourceChangeService(Store db,AuthService auth,ResourceService resources,OpeningScheduleService opening,BookingService bookings,AuditService audit,ObjectMapper json,Validator validator,Clock clock) { this.db=db;this.auth=auth;this.resources=resources;this.opening=opening;this.bookings=bookings;this.audit=audit;this.json=json;this.validator=validator;this.clock=clock; }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> preview(long actor,Requests.ResourceChangeProposal request) {
        requireAdmin(actor);var now=now();var projection=project(normalize(request),now);var result=new LinkedHashMap<String,Object>();
        result.put("normalized_proposal",projection.normalized().proposal());result.put("target_label",projection.label());result.put("versions",projection.versions());result.put("impact_hash",projection.hash());result.put("pending",projection.pending());result.put("in_use",projection.inUse());
        result.put("summary",Map.of("pending_count",projection.pending().size(),"in_use_count",projection.inUse().size(),"total_count",projection.pending().size()+projection.inUse().size(),"immediate_termination",true,"warning","确认时立即取消整条受影响预约并按确认时刻结算使用；如需继续使用请稍后确认"));result.put("server_now",now.atZone(BookingPolicy.CAMPUS_ZONE).toOffsetDateTime().toString());result.put("campus_zone",BookingPolicy.CAMPUS_ZONE.getId());return result;
    }
    @Transactional
    public Map<String,Object> confirm(long actor,Requests.ResourceChangeConfirm request) {
        db.lockBookings();requireAdmin(actor);Api.require(request!=null,400,"确认参数必填");String operation=operationId(request.operationId());
        var existing=db.list("SELECT actor_id,request_hash,result FROM resource_change_operations WHERE operation_id=CAST(? AS UUID)",operation);
        Normalized normalized;Map<String,Object> coordination;
        try { normalized=normalize(request.proposal());coordination=coordination(request.coordination()); }
        catch(Api.Failure failure) { if(!existing.isEmpty()) throw Api.fail(409,"操作标识已用于其他内容，请保留原请求重试");throw failure; }
        String intent=hash(Map.of("actor_id",actor,"proposal",normalized.proposal(),"impact_hash",Objects.toString(request.impactHash(),""),"coordination",coordination));
        if(!existing.isEmpty()) {
            var saved=existing.get(0);Api.require(Store.id(saved,"actor_id")==actor&&intent.equals(saved.get("request_hash")),409,"操作标识已用于其他内容，不能修改原请求");
            var result=readResult(saved.get("result").toString());result.put("replayed",true);return result;
        }
        Api.require(request.impactHash()!=null&&request.impactHash().matches("[0-9a-f]{64}"),400,"影响指纹格式错误");var now=now();var projection=project(normalized,now);
        Api.require(projection.hash().equals(request.impactHash()),409,"资源或预约影响已变化，请重新预览确认");
        boolean inUse=!projection.inUse().isEmpty();if(inUse) Api.require(Boolean.TRUE.equals(coordination.get("confirmed"))&&coordination.get("note")!=null&&!coordination.get("note").toString().isBlank(),400,"使用中预约须由管理员自报已现场协调并填写说明");
        String reason=Store.text(normalized.proposal().payload(),"reason");var cancelled=new ArrayList<Map<String,Object>>();var affected=new ArrayList<>(projection.pending());affected.addAll(projection.inUse());affected.sort(Comparator.comparingLong(row->Store.id(row,"reservation_id")));
        for(var row:affected) cancelled.add(bookings.cancelForResource(actor,Store.id(row,"reservation_id"),reason,now));
        Object resource=apply(actor,projection);var result=new LinkedHashMap<String,Object>();result.put("operation_id",operation);result.put("change_type",normalized.proposal().changeType());result.put("target_id",normalized.proposal().targetId());result.put("room_id",projection.roomId());result.put("cancelled_count",cancelled.size());result.put("pending_cancelled_count",projection.pending().size());result.put("in_use_cancelled_count",projection.inUse().size());result.put("cancelled_reservations",cancelled);result.put("resource_result",resource);result.put("versions",versions(projection.roomId(),normalized.proposal()));result.put("coordination_source",inUse?"admin_self_report":null);result.put("replayed",false);
        var privateCoordination=new LinkedHashMap<>(coordination);if(inUse) privateCoordination.put("source","admin_self_report");
        db.update("INSERT INTO resource_change_operations(operation_id,actor_id,request_hash,impact_hash,proposal,coordination,result,created_at) VALUES(CAST(? AS UUID),?,?,?,CAST(? AS JSONB),CAST(? AS JSONB),CAST(? AS JSONB),?)",operation,actor,intent,projection.hash(),write(normalized.proposal()),write(privateCoordination),write(result),Timestamp.valueOf(now));
        var evidence=new LinkedHashMap<String,Object>();evidence.put("change_type",normalized.proposal().changeType());evidence.put("target_id",normalized.proposal().targetId());evidence.put("room_id",projection.roomId());evidence.put("cancelled_reservation_ids",affected.stream().map(row->row.get("reservation_id")).toList());evidence.put("coordination",privateCoordination);
        audit.record(actor,"resource_change.confirm","resource_change_operation",operation,reason,Map.of(),evidence);return result;
    }
    private Projection project(Normalized normalized,LocalDateTime now) {
        var proposal=normalized.proposal();String type=proposal.changeType();long target=proposal.targetId(),room;
        Map<String,Object> targetRow=Map.of();if(type.equals("seat.update")) { targetRow=db.one("SELECT * FROM seats WHERE id=?",target);room=Store.id(targetRow,"room_id"); }
        else if(type.equals("block.update")||type.equals("block.cancel")) { targetRow=db.one("SELECT * FROM resource_unavailability WHERE id=?",target);room=Store.id(targetRow,"room_id"); } else room=target;
        var before=opening.calendar(room);var after=before;boolean moved=false;
        switch(type) {
            case "room.update" -> { var change=resources.projectRoom(before,(Requests.Room)normalized.body());after=before.withRoom(change.after());moved=change.moved(); }
            case "seat.update" -> { var change=resources.projectSeat(before,target,(Requests.Seat)normalized.body());after=before.withSeat(change.after());moved=change.moved(); }
            case "opening.save" -> after=opening.projectSave(before,(Requests.OpeningChange)normalized.body());
            case "opening.delete_exception" -> after=opening.projectDelete(before,(Requests.OpeningDelete)normalized.body());
            case "block.create" -> after=opening.projectBlock(before,null,(Requests.Unavailability)normalized.body());
            case "block.update" -> after=opening.projectBlock(before,target,(Requests.Unavailability)normalized.body());
            case "block.cancel" -> after=opening.projectCancelBlock(before,target,(Requests.ResourceDelete)normalized.body());
            default -> throw new IllegalStateException("未覆盖资源变更类型");
        }
        var pending=new ArrayList<Map<String,Object>>();var used=new ArrayList<Map<String,Object>>();var fixed=new ArrayList<Map<String,Object>>();
        var rows=db.list("SELECT rv.*,s.seat_no,u.real_name,ur.id AS usage_id,ur.checkin_at AS usage_checkin_at,ur.ended_at AS usage_ended_at FROM reservations rv JOIN seats s ON s.id=rv.seat_id JOIN users u ON u.id=rv.user_id LEFT JOIN usage_records ur ON ur.reservation_id=rv.id WHERE s.room_id=? AND rv.status IN('pending','checked_in') AND rv.res_date+rv.end_time>? ORDER BY rv.id",room,Timestamp.valueOf(now));
        for(var row:rows) {
            if(OpeningScheduleService.remaining(row,now)==null) continue;
            Map<String,Object> actualUsage=null;if(row.get("usage_id")!=null) { actualUsage=new LinkedHashMap<>();actualUsage.put("checkin_at",row.get("usage_checkin_at"));actualUsage.put("ended_at",row.get("usage_ended_at")); }
            UsageService.requireReservationState(Store.text(row,"status"),actualUsage,now);
            boolean physical=moved&&(!type.equals("seat.update")||Store.id(row,"seat_id")==target);
            if(!OpeningScheduleService.affected(before,after,row,now,physical)) continue;
            var safe=new LinkedHashMap<String,Object>();safe.put("reservation_id",row.get("id"));for(String field:List.of("user_id","real_name","seat_id","seat_no","status")) safe.put(field,row.get(field));safe.put("date",row.get("res_date"));safe.put("start_time",row.get("start_time"));safe.put("end_time",row.get("end_time"));safe.put("checkin_at",row.get("usage_checkin_at"));
            if("pending".equals(row.get("status"))) pending.add(safe);else used.add(safe);
            var fingerprint=new LinkedHashMap<>(safe);fingerprint.remove("real_name");fingerprint.remove("seat_no");fingerprint.put("usage_id",row.get("usage_id"));fixed.add(fingerprint);
        }
        var versions=versions(room,proposal);String fingerprint=hash(Map.of("proposal",proposal,"versions",versions,"affected",fixed));String label=Store.text(before.room,"name");
        if(type.equals("seat.update")) label+=" / "+Store.text(targetRow,"seat_no");
        if(type.equals("block.update")||type.equals("block.cancel")) label+=blockLabel(before,targetRow);
        if(type.equals("block.create")) { var body=(Requests.Unavailability)normalized.body();var block=new LinkedHashMap<String,Object>();block.put("seat_id",body.seatId());block.put("start_at",body.startAt());block.put("end_at",body.endAt());label+=blockLabel(before,block); }
        return new Projection(normalized,room,label,versions,pending,used,fingerprint);
    }
    private String blockLabel(OpeningScheduleService.Calendar calendar,Map<String,Object> block) {
        String scope=block.get("seat_id")==null?"全空间":calendar.seats.stream().filter(seat->Store.id(seat,"id")==Store.id(block,"seat_id")).map(seat->Store.text(seat,"seat_no")).findFirst().orElse("座位"+block.get("seat_id"));return " / "+scope+" / "+block.get("start_at")+"—"+block.get("end_at");
    }
    private Map<String,Object> versions(long room,Requests.ResourceChangeProposal proposal) {
        var result=new LinkedHashMap<String,Object>();result.put("room_id",room);result.put("room_version",db.one("SELECT version FROM rooms WHERE id=?",room).get("version"));String type=proposal.changeType();
        if(type.equals("seat.update")) { result.put("seat_id",proposal.targetId());result.put("seat_version",db.one("SELECT version FROM seats WHERE id=?",proposal.targetId()).get("version")); }
        if(type.equals("block.update")||type.equals("block.cancel")) { result.put("block_id",proposal.targetId());result.put("block_version",db.one("SELECT version FROM resource_unavailability WHERE id=?",proposal.targetId()).get("version")); }return result;
    }
    private Object apply(long actor,Projection projection) {
        long target=projection.normalized().proposal().targetId();Object body=projection.normalized().body();return switch(projection.normalized().proposal().changeType()) {
            case "room.update" -> resources.save(actor,target,(Requests.Room)body);
            case "seat.update" -> resources.updateSeat(actor,target,(Requests.Seat)body);
            case "opening.save" -> opening.save(actor,projection.roomId(),(Requests.OpeningChange)body);
            case "opening.delete_exception" -> opening.deleteException(actor,projection.roomId(),(Requests.OpeningDelete)body);
            case "block.create" -> opening.saveBlock(actor,projection.roomId(),null,(Requests.Unavailability)body);
            case "block.update" -> opening.saveBlock(actor,projection.roomId(),target,(Requests.Unavailability)body);
            case "block.cancel" -> opening.cancelBlock(actor,projection.roomId(),target,(Requests.ResourceDelete)body);
            default -> throw new IllegalStateException("未覆盖资源变更类型");
        };
    }
    private Normalized normalize(Requests.ResourceChangeProposal input) {
        Api.require(input!=null,400,"变更提案必填");validate(input);Api.require(TYPES.contains(input.changeType()),400,"资源变更类型无效");Class<?> type=switch(input.changeType()) { case "room.update" -> Requests.Room.class;case "seat.update" -> Requests.Seat.class;case "opening.save" -> Requests.OpeningChange.class;case "opening.delete_exception" -> Requests.OpeningDelete.class;case "block.create","block.update" -> Requests.Unavailability.class;case "block.cancel" -> Requests.ResourceDelete.class;default -> throw new IllegalStateException(); };
        Object body=convert(input.payload(),type);var normalized=json.convertValue(body,MAP);Api.require(normalized.keySet().containsAll(input.payload().keySet()),400,"变更包含不支持字段");
        for(var entry:normalized.entrySet()) if(entry.getValue() instanceof String text) { String trimmed=text.trim();entry.setValue(Set.of("building","floor","location","description").contains(entry.getKey())&&trimmed.isEmpty()?null:trimmed); }
        body=convert(normalized,type);validate(body);
        if(body instanceof Requests.OpeningChange request) { var windows=OpeningScheduleService.normalize(request.windows()).stream().map(OpeningScheduleService.Window::view).toList();normalized.put("windows",json.convertValue(windows,new TypeReference<List<Map<String,Object>>>() {})); }
        if(body instanceof Requests.Unavailability request) { normalized.put("start_at",OpeningScheduleService.local(request.startAt()).toString());normalized.put("end_at",OpeningScheduleService.local(request.endAt()).toString()); }
        body=convert(normalized,type);return new Normalized(new Requests.ResourceChangeProposal(input.changeType(),input.targetId(),normalized),body);
    }
    private Map<String,Object> coordination(Requests.Coordination input) { if(input==null) return Map.of();String note=input.note()==null?null:input.note().trim();Api.require(note==null||note.length()<=1000,400,"现场协调自报说明须在1000字以内");var result=new LinkedHashMap<String,Object>();result.put("confirmed",Boolean.TRUE.equals(input.confirmed()));result.put("note",note);return result; }
    private Object convert(Object source,Class<?> target) { try { return json.convertValue(source,target); } catch(IllegalArgumentException e) { throw Api.fail(400,"资源变更参数格式错误"); } }
    private void validate(Object value) { var errors=validator.validate(value);Api.require(errors.isEmpty(),400,errors.stream().map(error->error.getPropertyPath()+": "+error.getMessage()).sorted().findFirst().orElse("参数错误")); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(),BookingPolicy.CAMPUS_ZONE).truncatedTo(ChronoUnit.MICROS); }
    private void requireAdmin(long actor) { var user=auth.profile(actor);Api.require("admin".equals(user.get("role"))&&"active".equals(user.get("status")),403,"需要管理员权限"); }
    private String operationId(String raw) { Api.require(raw!=null&&raw.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),400,"operation_id 须为UUID");return UUID.fromString(raw).toString(); }
    private String hash(Object value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(write(canonical(json.convertValue(value,Object.class))).getBytes(StandardCharsets.UTF_8))); } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    private Object canonical(Object value) { if(value instanceof Map<?,?> map) { var sorted=new TreeMap<String,Object>();for(var entry:map.entrySet()) sorted.put(entry.getKey().toString(),canonical(entry.getValue()));return sorted; }if(value instanceof List<?> list) return list.stream().map(this::canonical).toList();return value; }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("资源变更记录不能序列化",e); } }
    private LinkedHashMap<String,Object> readResult(String value) { try { return json.readerFor(MAP).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(value); } catch(java.io.IOException e) { throw new IllegalStateException("已完成资源操作结果无法读取",e); } }
}
