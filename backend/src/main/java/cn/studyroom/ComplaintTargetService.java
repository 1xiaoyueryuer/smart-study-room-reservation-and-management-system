package cn.studyroom;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 目标快照只在提交/保真迁移时建立；完成读原生命周期，唯一超时事件与审计同事务。 */
@Service
public class ComplaintTargetService {
    private final Store db;private final OpeningScheduleService opening;private final AuditService audit;private final Clock clock;
    public ComplaintTargetService(Store db,OpeningScheduleService opening,AuditService audit,Clock clock){this.db=db;this.opening=opening;this.audit=audit;this.clock=clock;}
    @Transactional(propagation=Propagation.MANDATORY)
    public Map<String,Object> initialize(long id,String type,Long room,LocalDateTime submitted) {
        String kind,basis,status,reason;Long version=null;LocalDateTime anchor=null,due=null;
        if(type.equals("service")){kind="first_feedback";basis="service_24h";status="calculated";anchor=submitted;due=anchor.plusHours(24);reason="一般服务投诉：从提交时刻起24小时内首次正式反馈；不等于现场问题已解决";}
        else {kind="acceptance";if(room==null)throw new IllegalStateException("被占投诉缺少所属空间");var calendar=opening.calendar(room);version=Store.id(calendar.room,"version");anchor=calendar.nextOpening(submitted);if(anchor==null){basis="next_room_opening";status="pending";reason=Boolean.TRUE.equals(calendar.room.get("enabled"))?"提交时安排未能确定下次空间开放时间，受理目标截止待定；不使用虚构期限":"提交时空间已停用，无法确定下次开放时间，受理目标截止待定";}else {status="calculated";basis=anchor.equals(submitted)?"open_at_submission":"next_room_opening";due=anchor.plusMinutes(15);reason=anchor.equals(submitted)?"开放期间提交：从提交时刻起15经过分钟接单开始核查，跨闭馆不暂停；不是到场或解决承诺":"非开放期间提交：从提交时安排的下次空间开放起15经过分钟接单开始核查；期限按原快照冻结";}}
        db.update("INSERT INTO complaint_targets(complaint_id,kind,calculation_status,anchor_at,due_at,reason,room_version,calculated_at,basis,source) VALUES(?,?,?,?,?,?,?,?,?,'new_submission')",id,kind,status,stamp(anchor),stamp(due),reason,version,Timestamp.valueOf(submitted),basis);
        var snapshot=new LinkedHashMap<String,Object>();snapshot.put("kind",kind);snapshot.put("calculation_status",status);snapshot.put("anchor_at",anchor==null?null:anchor.toString());snapshot.put("due_at",due==null?null:due.toString());snapshot.put("room_version",version);snapshot.put("basis",basis);return snapshot;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public int recordScope(String where,List<?> args,LocalDateTime now) {var values=new ArrayList<Object>(args);values.add(Timestamp.valueOf(now));String active=" AND t.due_at<=? AND e.complaint_id IS NULL AND c.status<>'withdrawn' AND ((t.kind='acceptance' AND c.accepted_at IS NULL) OR (t.kind='first_feedback' AND f.created_at IS NULL))";int count=0;for(var row:read("("+where+")"+active,values.toArray()))if(record(row,now))count++;return count;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void recordOne(long id,LocalDateTime now){recordScope("c.id=?",List.of(id),now);}
    @Transactional
    public int settle(){db.lockBookings();var now=LocalDateTime.ofInstant(clock.instant(),BookingPolicy.CAMPUS_ZONE).truncatedTo(java.time.temporal.ChronoUnit.MICROS);return recordScope("1=1",List.of(),now);}
    void decorate(List<Map<String,Object>> complaints,LocalDateTime now) {
        if(complaints.isEmpty())return;var ids=complaints.stream().map(row->row.get("id")).toArray();String marks=String.join(",",Collections.nCopies(ids.length,"?"));var targets=new HashMap<Long,Map<String,Object>>();for(var row:read("c.id IN ("+marks+")",ids))targets.put(Store.id(row,"complaint_id"),view(row,now));
        for(var complaint:complaints){var target=targets.get(Store.id(complaint,"id"));if(target==null)throw new IllegalStateException("投诉缺少处理目标快照，请核查迁移或提交事务");complaint.put("service_target",target);}
    }
    private List<Map<String,Object>> read(String where,Object...args){return db.list("SELECT t.*,c.status AS complaint_status,c.accepted_at,f.created_at AS first_feedback_at,w.created_at AS withdrawn_at,e.occurred_at AS overdue_occurred_at,e.detected_at AS overdue_detected_at,e.source AS overdue_source FROM complaint_targets t JOIN complaints c ON c.id=t.complaint_id LEFT JOIN LATERAL(SELECT created_at FROM complaint_events WHERE complaint_id=c.id AND event_type='feedback' ORDER BY id LIMIT 1) f ON true LEFT JOIN LATERAL(SELECT created_at FROM complaint_events WHERE complaint_id=c.id AND event_type='withdrawn' ORDER BY id LIMIT 1) w ON true LEFT JOIN complaint_target_events e ON e.complaint_id=c.id WHERE "+where,args);}
    private boolean record(Map<String,Object> row,LocalDateTime now) {
        if(row.get("due_at")==null||completed(row)!=null||row.get("withdrawn_at")!=null||row.get("overdue_detected_at")!=null)return false;var due=LocalDateTime.parse(Store.text(row,"due_at"));if(now.isBefore(due))return false;long id=Store.id(row,"complaint_id");
        db.update("INSERT INTO complaint_target_events(complaint_id,occurred_at,detected_at,source) VALUES(?,?,?,?)",id,Timestamp.valueOf(due),Timestamp.valueOf(now),row.get("source"));audit.record(null,"complaint.target_overdue","complaint",Long.toString(id),"处理目标到期时首次实际检测留痕，不登记用户违规或发送普通通知",Map.of(),Map.of("kind",row.get("kind"),"due_at",due.toString(),"detected_at",now.toString(),"source",row.get("source")));return true;
    }
    private Map<String,Object> view(Map<String,Object> row,LocalDateTime now){
        var result=new LinkedHashMap<String,Object>();for(String key:List.of("kind","calculation_status","reason","room_version","basis"))result.put(key,row.get(key));for(String key:List.of("anchor_at","due_at","calculated_at"))result.put(key,AttachmentService.offset(row.get(key)));
        LocalDateTime completed=completed(row),stopped=local(row.get("withdrawn_at")),due=local(row.get("due_at"));Boolean late=completed==null||due==null?null:!completed.isBefore(due);String status;
        if(completed!=null)status=Boolean.TRUE.equals(late)?"late_completed":"completed";else if(stopped!=null)status="withdrawn";else if(due==null)status=Store.text(row,"calculation_status");else status=now.isBefore(due)?"running":"overdue";
        result.put("status",status);result.put("completed_at",AttachmentService.offset(completed));result.put("stopped_at",AttachmentService.offset(stopped));result.put("was_late",late);result.put("remaining_seconds",status.equals("running")?seconds(Duration.between(now,due)):status.equals("overdue")?BigDecimal.ZERO:null);
        result.put("overdue_event",row.get("overdue_detected_at")==null?null:Map.of("occurred_at",AttachmentService.offset(row.get("overdue_occurred_at")),"detected_at",AttachmentService.offset(row.get("overdue_detected_at")),"source",row.get("overdue_source")));return result;
    }
    private LocalDateTime completed(Map<String,Object> row){if("withdrawn".equals(row.get("complaint_status"))&&row.get("withdrawn_at")==null||"first_feedback".equals(row.get("kind"))&&Set.of("awaiting_confirmation","closed").contains(Store.text(row,"complaint_status"))&&row.get("first_feedback_at")==null)throw new IllegalStateException("投诉阶段与目标完成事实缺失或矛盾，请核查数据");return local(row.get(Store.text(row,"kind").equals("acceptance")?"accepted_at":"first_feedback_at"));}
    private LocalDateTime local(Object value){return value==null?null:LocalDateTime.parse(value.toString());}
    private Timestamp stamp(LocalDateTime value){return value==null?null:Timestamp.valueOf(value);}
    private BigDecimal seconds(Duration d){return BigDecimal.valueOf(d.getSeconds()).add(BigDecimal.valueOf(d.getNano(),9));}
}
