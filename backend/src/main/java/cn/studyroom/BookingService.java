package cn.studyroom;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.studyroom.Store.*;

@Service
@Transactional
public class BookingService {
    private static final String ACTIVE="('pending','checked_in')";
    private static final String VIEW="SELECT rv.*,s.seat_no,s.zone,s.has_power,s.room_id,r.name AS room_name,u.username,u.real_name FROM reservations rv JOIN seats s ON s.id=rv.seat_id JOIN rooms r ON r.id=s.room_id JOIN users u ON u.id=rv.user_id ";
    private final Store db;
    private final Clock clock;
    private final OpeningScheduleService opening;
    private final BookingPolicy policy;
    private final AuditService audit;
    private final ViolationService violations;
    private final SeatCodeService codes;
    private final UsageService usage;
    public BookingService(Store db,Clock clock,OpeningScheduleService opening,BookingPolicy policy,AuditService audit,ViolationService violations,SeatCodeService codes,UsageService usage) { this.db=db;this.clock=clock;this.opening=opening;this.policy=policy;this.audit=audit;this.violations=violations;this.codes=codes;this.usage=usage; }
    public List<Map<String,Object>> mine(long uid) {
        db.lockBookings();LocalDateTime now=now();reconcileUser(uid,now);
        var rows=db.list(VIEW+"WHERE rv.user_id=? ORDER BY rv.res_date DESC,rv.start_time DESC,rv.id DESC",uid);
        for(var row:rows) decorate(row,now);return rows;
    }
    public Map<String,Object> detail(long uid,long resId) {
        db.lockBookings();requireOrdinary(uid);var row=db.one(VIEW+"WHERE rv.id=?",resId);
        Api.require(id(row,"user_id")==uid,403,"无权查看他人的预约");LocalDateTime now=now();
        reconcileRows(List.of(row),now);row=db.one(VIEW+"WHERE rv.id=?",resId);decorate(row,now);return row;
    }
    public List<Map<String,Object>> violationsMine(long uid) {
        db.lockBookings();reconcileUser(uid,now());return violations.mine(uid);
    }
    public Map<String,Object> create(long uid,Requests.Reservation req) {
        db.lockBookings();return createInternal(uid,req,"manual");
    }
    private Map<String,Object> createInternal(long uid,Requests.Reservation req,String source) {
        Slot slot=Slot.parse(req.date(),req.startTime(),req.endTime()); policy.validate(slot);eligible(uid);
        var seat=db.one("SELECT * FROM seats WHERE id=?",req.seatId());
        Api.require("available".equals(seat.get("status")),409,"座位当前不可预约");
        var room=db.one("SELECT * FROM rooms WHERE id=?",id(seat,"room_id"));opening.requireSlot(id(seat,"room_id"),req.seatId(),slot);
        reconcileRows(db.list("SELECT * FROM reservations WHERE status IN "+ACTIVE+" AND (user_id=? OR seat_id=?) ORDER BY id",uid,req.seatId()),now());
        Api.require(!conflict("seat_id",req.seatId(),slot),409,"该座位此时段已被预约");
        Api.require(!conflict("user_id",uid,slot),409,"您在同一时段已有其他预约");
        long resId=db.insert("INSERT INTO reservations(user_id,seat_id,res_date,start_time,end_time,source) VALUES(?,?,?,CAST(? AS TIME),CAST(? AS TIME),?)",
                uid,req.seatId(),Date.valueOf(slot.date()),slot.startText(),slot.endText(),source);
        notify(uid,"reservation_success","预约成功",room.get("name")+" "+seat.get("seat_no")+" "+req.date()+" "+req.startTime()+"—"+req.endTime()+"，请按时签到。");
        audit.record(uid,"reservation.create","reservation",Long.toString(resId),"用户预约",Map.of(),Map.of("seat_id",req.seatId(),"date",slot.date().toString(),"start_time",slot.startText(),"end_time",slot.endText(),"source",source));
        var created=db.one(VIEW+"WHERE rv.id=?",resId);decorate(created,now());return created;
    }
    private boolean conflict(String column,long id,Slot slot) {
        return db.count("SELECT count(*) FROM reservations WHERE "+column+"=? AND res_date=? AND status IN "+ACTIVE+" AND start_time<CAST(? AS TIME) AND end_time>CAST(? AS TIME)",
                id,Date.valueOf(slot.date()),slot.endText(),slot.startText())>0;
    }
    private void eligible(long uid) {
        var user=db.one("SELECT * FROM users WHERE id=?",uid);
        Api.require(AuthService.isOrdinary(user.get("role")),403,"仅学生和教职工可以预约");
        Api.require("active".equals(user.get("status")),403,"账号已被禁用");
        Api.require(!legacyBanActive(user,now()),403,"历史限制尚未到原截止，当前不能预约");
    }
    public Map<String,Object> legacyCredit(long uid) {
        var user=db.one("SELECT credit_score,credit_banned_until FROM users WHERE id=?",uid);var result=new LinkedHashMap<String,Object>();
        Object until=user.get("credit_banned_until");result.put("score",user.get("credit_score"));result.put("banned_until",until);
        result.put("legacy_banned_until",until==null?null:LocalDateTime.parse(until.toString()).atZone(BookingPolicy.CAMPUS_ZONE).toOffsetDateTime().toString());
        result.put("legacy_ban_active",legacyBanActive(user,now()));result.put("history_only",true);result.put("read_only",true);result.put("server_now",clock.instant().atZone(BookingPolicy.CAMPUS_ZONE).toOffsetDateTime().toString());result.put("campus_zone",BookingPolicy.CAMPUS_ZONE.getId());
        result.put("logs",db.list("SELECT * FROM credit_logs WHERE user_id=? ORDER BY id DESC LIMIT 50",uid));return result;
    }
    private boolean legacyBanActive(Map<String,Object> user,LocalDateTime now) { return user.get("credit_banned_until")!=null&&LocalDateTime.parse(user.get("credit_banned_until").toString()).isAfter(now); }
    public record ActionResult(int status,String message,Map<String,Object> data,Object details) {}
    public ActionResult action(long uid,long resId,String action) {
        if(Set.of("checkin","leave","return").contains(action)) {
            db.lockBookings();requireOrdinary(uid);var row=db.one("SELECT user_id FROM reservations WHERE id=?",resId);
            Api.require(id(row,"user_id")==uid,403,"无权操作他人的预约");if(!"checkin".equals(action)) throw Api.fail(410,"暂离与返回已退出，请直接使用或签退");throw Api.fail(400,"签到需要座位码，请使用专用签到接口");
        }
        return performAction(uid,resId,action,null);
    }
    public ActionResult checkin(long uid,long resId,String seatCode) { return performAction(uid,resId,"checkin",seatCode); }
    private ActionResult performAction(long uid,long resId,String action,String seatCode) {
        db.lockBookings();requireOrdinary(uid);var res=db.one("SELECT * FROM reservations WHERE id=?",resId);
        Api.require(id(res,"user_id")==uid,403,"无权操作他人的预约");
        Api.require(Set.of("cancel","abandon","checkin","checkout").contains(action),404,"操作不存在");
        LocalDateTime now=now(),start=starts(res),end=ends(res);String state=text(res,"status");res.put("usage_record",usage.view(resId,now));
        if(ReservationDeadline.expiredPending(res,now)) {
            endUnused(res,"no_show",now);
            var actual=db.one("SELECT id,status,termination_reason,ended_at FROM reservations WHERE id=?",resId);
            var details=new LinkedHashMap<String,Object>(actual);details.put("reservation_id",resId);details.put("allowed_actions",List.of());
            return new ActionResult(409,"签到期限已到，预约已按未签到结束，请刷新记录",Map.of(),details);
        }
        if(state.equals("checked_in")&&!now.isBefore(end)) {
            complete(res,end,"auto_end",null,now);var actual=db.one("SELECT id,status,termination_reason,ended_at FROM reservations WHERE id=?",resId);decorate(actual,now);
            actual.put("reservation_id",resId);return new ActionResult(409,"预约已到期并完成使用结算，请刷新记录",Map.of(),actual);
        }
        switch(action) {
            case "cancel" -> {
                Api.require(state.equals("pending"),409,"当前状态不能取消预约");Api.require(now.isBefore(start),409,"预约已经开始，请使用放弃操作");
                db.update("UPDATE reservations SET status='cancelled',ended_at=?,termination_reason='user_cancelled',updated_at=CURRENT_TIMESTAMP WHERE id=?",Timestamp.valueOf(now),resId);
                notify(uid,"reservation_cancelled","预约已取消","您在开始前取消了预约，本次不登记违规。");
                auditReservation(uid,"reservation.cancel",res,"用户开始前取消预约");
            }
            case "abandon" -> {
                Api.require(state.equals("pending"),409,"当前状态不能放弃预约");Api.require(!now.isBefore(start),409,"开始前请使用取消操作");
                endUnused(res,"abandon",now);
            }
            case "checkin" -> {
                Api.require(state.equals("pending"),409,"当前状态不能签到");
                Api.require(!now.isBefore(start)&&now.isBefore(ReservationDeadline.at(res))&&now.isBefore(end),409,"签到窗口为预约开始至开始后15分钟之前");
                codes.requireValid(id(res,"seat_id"),seatCode);
                var seat=db.one("SELECT * FROM seats WHERE id=?",id(res,"seat_id"));
                var calendar=opening.calendar(id(seat,"room_id"));
                Api.require(calendar.day(LocalDate.parse(text(res,"res_date")),id(res,"seat_id")).contains(LocalDate.parse(text(res,"res_date")),now,end),409,"预约资源当前不可使用，请联系管理员");
                usage.start(resId,now,"seat_code");db.update("UPDATE reservations SET status='checked_in',updated_at=CURRENT_TIMESTAMP WHERE id=?",resId);notify(uid,"reservation_checkin","签到成功","座位码核验成功，预约已进入使用中。");
                auditReservation(uid,"reservation.checkin",res,"用户核验当前有效座位码后签到");
            }
            case "checkout" -> {
                Api.require(state.equals("checked_in"),409,"当前状态不能签退");complete(res,now,"early_checkout",uid,now);
            }
            default -> throw new IllegalStateException("未覆盖的预约动作");
        }
        var actual=db.one("SELECT id,status,ended_at,termination_reason FROM reservations WHERE id=?",resId);
        actual.put("action",actual.get("status"));decorate(actual,now);actual.put("allowed_actions",allowedActions(db.one("SELECT * FROM reservations WHERE id=?",resId),now));
        if(action.equals("cancel")) actual.put("cancelled",resId);
        return new ActionResult(200,"ok",actual,null);
    }
    private List<String> allowedActions(Map<String,Object> row,LocalDateTime now) {
        String state=text(row,"status");LocalDateTime start=starts(row),end=ends(row);
        if(state.equals("pending")) {
            if(now.isBefore(start)) return List.of("cancel");
            if(now.isBefore(ReservationDeadline.at(row))&&now.isBefore(end)) return List.of("checkin","abandon");
        }
        if(state.equals("checked_in")) return List.of("checkout");
        return List.of();
    }
    private void reconcileUser(long uid,LocalDateTime now) { reconcileRows(db.list("SELECT * FROM reservations WHERE user_id=? AND status IN "+ACTIVE+" ORDER BY id",uid),now); }
    public void reconcileRoom(long roomId,LocalDate date) {
        db.lockBookings();db.one("SELECT id FROM rooms WHERE id=?",roomId);
        reconcileRows(db.list("SELECT rv.* FROM reservations rv JOIN seats s ON s.id=rv.seat_id WHERE s.room_id=? AND rv.res_date=? AND rv.status IN "+ACTIVE+" ORDER BY rv.id",roomId,Date.valueOf(date)),now());
    }
    private void reconcileRows(List<Map<String,Object>> rows,LocalDateTime now) {
        for(var row:rows) {
            if(ReservationDeadline.expiredPending(row,now)) endUnused(row,"no_show",now);
            else if("checked_in".equals(row.get("status"))&&!now.isBefore(ends(row))) complete(row,ends(row),"auto_end",null,now);
        }
    }
    private void endUnused(Map<String,Object> res,String type,LocalDateTime now) {
        long reservation=id(res,"id"),uid=id(res,"user_id");LocalDateTime occurred=type.equals("no_show")?ReservationDeadline.at(res):now;
        String reason=type.equals("no_show")?"签到期限到达仍未签到":"预约开始后主动放弃且未签到";
        db.update("UPDATE reservations SET status='unused_ended',ended_at=?,termination_reason=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",Timestamp.valueOf(occurred),type,reservation);
        long violation=violations.recordUnused(uid,reservation,type,occurred,now,reason);
        notify(uid,"violation","未使用预约已结束",reason+"，已释放时段并登记一条独立违规，可在本人违规记录查看。");
        auditReservation(type.equals("abandon")?uid:null,"reservation.unused_end",res,reason);
        audit.record(type.equals("abandon")?uid:null,"violation.register","violation",Long.toString(violation),reason,Map.of(),Map.of("reservation_id",reservation,"user_id",uid,"type",type,"occurred_at",occurred.toString(),"registered_at",now.toString(),"source","automatic","status","active"));
    }
    private void complete(Map<String,Object> res,LocalDateTime actual,String reason,Long actor,LocalDateTime now) {
        long reservation=id(res,"id");res.put("usage_record",usage.view(reservation,now));
        if(!usage.finish(reservation,ends(res),actual,reason,now)) return;
        var record=usage.view(reservation,now);
        db.update("UPDATE reservations SET status='completed',ended_at=?,termination_reason=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",Timestamp.valueOf(LocalDateTime.parse(record.get("ended_at").toString())),reason,reservation);
        notify(id(res,"user_id"),"reservation_completed","使用已结束",reason.equals("auto_end")?"预约已到期，使用记录已结算，无新增违规或信用奖惩。":"已提前签退并释放剩余时段，使用记录已结算。");
        auditReservation(actor,"reservation.complete",res,"预约使用结束，无信用奖惩");
    }
    private void decorate(Map<String,Object> row,LocalDateTime now) {
        // 动作结果的精简行无日期字段，其allowed_actions由完整预约统一计算。
        if(row.containsKey("res_date")) row.put("allowed_actions",allowedActions(row,now));else row.put("allowed_actions",List.of());
        row.put("usage_record",usage.view(id(row,"id"),now));
    }
    private void auditReservation(Long actor,String action,Map<String,Object> before,String reason) {
        var after=db.one("SELECT * FROM reservations WHERE id=?",id(before,"id"));after.put("usage_record",usage.view(id(before,"id"),now()));
        audit.record(actor,action,"reservation",Long.toString(id(before,"id")),reason,reservationSnapshot(before),reservationSnapshot(after));
    }
    private Map<String,Object> reservationSnapshot(Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>();for(String field:List.of("user_id","seat_id","res_date","start_time","end_time","source","status","checkin_at","checkout_at","leave_at","ended_at","termination_reason","usage_record")) result.put(field,row.get(field));return result;
    }
    void notify(long uid,String type,String title,String content) {
        db.update("INSERT INTO notifications(user_id,type,title,content) VALUES(?,?,?,?)",uid,type,title,content);
    }
    public Map<String,Object> allocate(long uid,Requests.Allocation req) { requireOrdinary(uid);throw Api.fail(410,"旧偏好自动分配已退出，请手动选择座位预约"); }
    public Map<String,Object> join(long uid,Requests.Wait req) { requireOrdinary(uid);throw Api.fail(410,"新增候补已退出，历史候补可查看或退出"); }
    public List<Map<String,Object>> waitMine(long uid) {
        return db.list("SELECT w.*,r.name AS room_name,CASE WHEN w.status='waiting' THEN (SELECT count(*) FROM waitlist q WHERE q.room_id=w.room_id AND q.res_date=w.res_date AND q.start_time=w.start_time AND q.end_time=w.end_time AND q.status='waiting' AND q.id<=w.id) ELSE 0 END AS position FROM waitlist w JOIN rooms r ON r.id=w.room_id WHERE w.user_id=? ORDER BY w.id DESC",uid);
    }
    public void cancelWait(long uid,long waitId) {
        db.lockBookings();requireOrdinary(uid);var row=db.one("SELECT * FROM waitlist WHERE id=?",waitId);
        Api.require(id(row,"user_id")==uid,403,"无权操作他人的候补");Api.require("waiting".equals(row.get("status")),409,"候补已结束");
        db.update("UPDATE waitlist SET status='cancelled',updated_at=CURRENT_TIMESTAMP WHERE id=?",waitId);
    }
    public void tick() {
        db.lockBookings();LocalDateTime now=now();
        reconcileRows(db.list("SELECT * FROM reservations WHERE status IN "+ACTIVE+" ORDER BY id"),now);
        // 已退出候补递补；历史记录保持原状，只有本人主动退出才修改waiting。
    }
    private void requireOrdinary(long uid) {
        Api.require(AuthService.isOrdinary(db.one("SELECT role FROM users WHERE id=?",uid).get("role")),403,"仅学生和教职工可操作本人预约或候补");
    }
    private LocalDateTime now() { return LocalDateTime.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    private LocalDateTime starts(Map<String,Object> r) { return Slot.endpoint(LocalDate.parse(text(r,"res_date")),text(r,"start_time")); }
    private LocalDateTime ends(Map<String,Object> r) { return Slot.endpoint(LocalDate.parse(text(r,"res_date")),text(r,"end_time")); }
}
