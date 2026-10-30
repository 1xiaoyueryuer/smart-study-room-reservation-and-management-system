package cn.studyroom;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 真实使用事实的唯一运行时写源；调用方持有预约互斥锁并管理通知审计。 */
@Service
public class UsageService {
    private final Store db;
    public UsageService(Store db) { this.db=db; }
    @Transactional(propagation=Propagation.MANDATORY)
    public void start(long reservation,LocalDateTime checkin,String origin) {
        checkin=micros(checkin);
        Api.require(Set.of("seat_code","demo_seed","legacy").contains(origin),400,"使用记录来源无效");
        db.update("INSERT INTO usage_records(reservation_id,checkin_at,origin,created_at,updated_at) VALUES(?,?,?,?,?)",reservation,Timestamp.valueOf(checkin),origin,Timestamp.valueOf(checkin),Timestamp.valueOf(checkin));
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean finish(long reservation,LocalDateTime plannedEnd,LocalDateTime actual,String reason,LocalDateTime settled) {
        plannedEnd=micros(plannedEnd);actual=micros(actual);settled=micros(settled);
        var row=required(reservation);if(row.get("ended_at")!=null) return false;
        LocalDateTime end=actual.isBefore(plannedEnd)?actual:plannedEnd;
        if(end.isBefore(LocalDateTime.parse(Store.text(row,"checkin_at")))) throw new IllegalStateException("使用记录结束早于真实签到，拒绝结算");
        db.update("UPDATE usage_records SET ended_at=?,end_reason=?,settled_at=?,updated_at=? WHERE reservation_id=? AND ended_at IS NULL",Timestamp.valueOf(end),reason,Timestamp.valueOf(settled),Timestamp.valueOf(settled),reservation);return true;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void startCorrection(long reservation,LocalDateTime business,LocalDateTime operated,long actor,String operation,String reason,String note,String request) {
        db.update("INSERT INTO usage_records(reservation_id,checkin_at,origin,created_at,updated_at,correction_operation,correction_actor,corrected_at,correction_reason,correction_note,correction_request) VALUES(?,?,'admin_correction',?,?,CAST(? AS UUID),?,?,?,?,CAST(? AS JSONB))",reservation,Timestamp.valueOf(business),Timestamp.valueOf(operated),Timestamp.valueOf(operated),operation,actor,Timestamp.valueOf(operated),reason,note,request);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> view(long reservation,LocalDateTime now) {
        now=micros(now);
        var rows=db.list("SELECT u.*,r.res_date,r.end_time FROM usage_records u JOIN reservations r ON r.id=u.reservation_id WHERE u.reservation_id=?",reservation);
        if(rows.isEmpty()) return null;var row=rows.get(0);var result=new LinkedHashMap<String,Object>();
        for(String field:List.of("id","checkin_at","ended_at","end_reason","settled_at","origin")) result.put(field,row.get(field));
        if("admin_correction".equals(row.get("origin")))result.put("correction",Map.of("operated_at",AttachmentService.offset(row.get("corrected_at")),"reason",row.get("correction_reason"),"actor_display_name",db.one("SELECT COALESCE(NULLIF(real_name,''),username) AS name FROM users WHERE id=?",row.get("correction_actor")).get("name"),"source","admin_self_report"));
        LocalDateTime start=LocalDateTime.parse(Store.text(row,"checkin_at")),end=row.get("ended_at")==null?Slot.endpoint(LocalDate.parse(Store.text(row,"res_date")),Store.text(row,"end_time")):LocalDateTime.parse(Store.text(row,"ended_at"));
        if(row.get("ended_at")==null&&now.isBefore(end)) end=now;
        if(end.isBefore(start)) throw new IllegalStateException("使用记录存在反向时长，拒绝伪造显示");
        Duration duration=Duration.between(start,end);result.put("duration_seconds",BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano(),9)));result.put("observed_at",now.atZone(BookingPolicy.CAMPUS_ZONE).toOffsetDateTime().toString());return result;
    }
    static void requireReservationState(String status,Map<String,Object> record,LocalDateTime now) {
        boolean checked="checked_in".equals(status);
        if(checked&&(record==null||record.get("checkin_at")==null||record.get("ended_at")!=null||LocalDateTime.parse(record.get("checkin_at").toString()).isAfter(now))||"pending".equals(status)&&record!=null) throw new IllegalStateException("预约状态与真实使用事实缺失或矛盾，请核查数据");
    }
    private LocalDateTime micros(LocalDateTime value) { return value.truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    private Map<String,Object> required(long reservation) { var rows=db.list("SELECT * FROM usage_records WHERE reservation_id=?",reservation);if(rows.isEmpty()) throw new IllegalStateException("使用中的预约缺少使用记录，请核查数据");return rows.get(0); }
}
