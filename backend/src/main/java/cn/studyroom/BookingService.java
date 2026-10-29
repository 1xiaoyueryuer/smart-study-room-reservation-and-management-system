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
    private static final String ACTIVE="('pending','checked_in','temp_leave')";
    private static final String VIEW="SELECT rv.*,s.seat_no,s.zone,s.has_power,s.room_id,r.name AS room_name,u.username,u.real_name FROM reservations rv JOIN seats s ON s.id=rv.seat_id JOIN rooms r ON r.id=s.room_id JOIN users u ON u.id=rv.user_id ";
    private final Store db;
    private final Clock clock;
    private final OpeningScheduleService opening;
    private final BookingPolicy policy;
    private final AuditService audit;
    private final ViolationService violations;
    private final SeatCodeService codes;
    public BookingService(Store db,Clock clock,OpeningScheduleService opening,BookingPolicy policy,AuditService audit,ViolationService violations,SeatCodeService codes) { this.db=db;this.clock=clock;this.opening=opening;this.policy=policy;this.audit=audit;this.violations=violations;this.codes=codes; }
    public List<Map<String,Object>> mine(long uid) {
        db.lockBookings();LocalDateTime now=LocalDateTime.now(clock);reconcileUnused(uid,now);
        var rows=db.list(VIEW+"WHERE rv.user_id=? ORDER BY rv.res_date DESC,rv.start_time DESC,rv.id DESC",uid);
        for(var row:rows) row.put("allowed_actions",allowedActions(row,now));return rows;
    }
    public Map<String,Object> detail(long uid,long resId) {
        db.lockBookings();requireOrdinary(uid);var row=db.one(VIEW+"WHERE rv.id=?",resId);
        Api.require(id(row,"user_id")==uid,403,"无权查看他人的预约");LocalDateTime now=LocalDateTime.now(clock);
        reconcilePending(List.of(row),now);row=db.one(VIEW+"WHERE rv.id=?",resId);row.put("allowed_actions",allowedActions(row,now));return row;
    }
    public List<Map<String,Object>> violationsMine(long uid) {
        db.lockBookings();reconcileUnused(uid,LocalDateTime.now(clock));return violations.mine(uid);
    }
    public Map<String,Object> create(long uid,Requests.Reservation req) {
        db.lockBookings();return createInternal(uid,req,"manual");
    }
    private Map<String,Object> createInternal(long uid,Requests.Reservation req,String source) {
        Slot slot=Slot.parse(req.date(),req.startTime(),req.endTime()); policy.validate(slot);eligible(uid);
        var seat=db.one("SELECT * FROM seats WHERE id=?",req.seatId());
        Api.require("available".equals(seat.get("status")),409,"座位当前不可预约");
        var room=db.one("SELECT * FROM rooms WHERE id=?",id(seat,"room_id"));opening.requireSlot(id(seat,"room_id"),req.seatId(),slot);
        reconcilePending(db.list("SELECT * FROM reservations WHERE status='pending' AND (user_id=? OR seat_id=?) ORDER BY id",uid,req.seatId()),LocalDateTime.now(clock));
        Api.require(!conflict("seat_id",req.seatId(),slot),409,"该座位此时段已被预约");
        Api.require(!conflict("user_id",uid,slot),409,"您在同一时段已有其他预约");
        long resId=db.insert("INSERT INTO reservations(user_id,seat_id,res_date,start_time,end_time,source) VALUES(?,?,?,CAST(? AS TIME),CAST(? AS TIME),?)",
                uid,req.seatId(),Date.valueOf(slot.date()),slot.startText(),slot.endText(),source);
        notify(uid,"reservation_success","预约成功",room.get("name")+" "+seat.get("seat_no")+" "+req.date()+" "+req.startTime()+"—"+req.endTime()+"，请按时签到。");
        audit.record(uid,"reservation.create","reservation",Long.toString(resId),"用户预约",Map.of(),Map.of("seat_id",req.seatId(),"date",slot.date().toString(),"start_time",slot.startText(),"end_time",slot.endText(),"source",source));
        return db.one(VIEW+"WHERE rv.id=?",resId);
    }
    private boolean conflict(String column,long id,Slot slot) {
        return db.count("SELECT count(*) FROM reservations WHERE "+column+"=? AND res_date=? AND status IN "+ACTIVE+" AND start_time<CAST(? AS TIME) AND end_time>CAST(? AS TIME)",
                id,Date.valueOf(slot.date()),slot.endText(),slot.startText())>0;
    }
    private void eligible(long uid) {
        var user=db.one("SELECT * FROM users WHERE id=?",uid);
        Api.require(AuthService.isOrdinary(user.get("role")),403,"仅学生和教职工可以预约");
        Api.require("active".equals(user.get("status")),403,"账号已被禁用");
        Api.require(!legacyBanActive(user,LocalDateTime.now(clock)),403,"历史限制尚未到原截止，当前不能预约");
    }
    public Map<String,Object> legacyCredit(long uid) {
        var user=db.one("SELECT credit_score,credit_banned_until FROM users WHERE id=?",uid);var result=new LinkedHashMap<String,Object>();
        Object until=user.get("credit_banned_until");result.put("score",user.get("credit_score"));result.put("banned_until",until);
        result.put("legacy_banned_until",until==null?null:LocalDateTime.parse(until.toString()).atZone(BookingPolicy.CAMPUS_ZONE).toOffsetDateTime().toString());
        result.put("legacy_ban_active",legacyBanActive(user,LocalDateTime.now(clock)));result.put("history_only",true);result.put("read_only",true);result.put("server_now",clock.instant().atZone(BookingPolicy.CAMPUS_ZONE).toOffsetDateTime().toString());result.put("campus_zone",BookingPolicy.CAMPUS_ZONE.getId());
        result.put("logs",db.list("SELECT * FROM credit_logs WHERE user_id=? ORDER BY id DESC LIMIT 50",uid));return result;
    }
    private boolean legacyBanActive(Map<String,Object> user,LocalDateTime now) { return user.get("credit_banned_until")!=null&&LocalDateTime.parse(user.get("credit_banned_until").toString()).isAfter(now); }
    public record ActionResult(int status,String message,Map<String,Object> data,Object details) {}
    public ActionResult action(long uid,long resId,String action) {
        if("checkin".equals(action)) {
            db.lockBookings();requireOrdinary(uid);var row=db.one("SELECT user_id FROM reservations WHERE id=?",resId);
            Api.require(id(row,"user_id")==uid,403,"无权操作他人的预约");throw Api.fail(400,"签到需要座位码，请使用专用签到接口");
        }
        return performAction(uid,resId,action,null);
    }
    public ActionResult checkin(long uid,long resId,String seatCode) { return performAction(uid,resId,"checkin",seatCode); }
    private ActionResult performAction(long uid,long resId,String action,String seatCode) {
        db.lockBookings();requireOrdinary(uid);var res=db.one("SELECT * FROM reservations WHERE id=?",resId);
        Api.require(id(res,"user_id")==uid,403,"无权操作他人的预约");
        Api.require(Set.of("cancel","abandon","checkin","leave","return","checkout").contains(action),404,"操作不存在");
        LocalDateTime now=LocalDateTime.now(clock),start=starts(res),end=ends(res);String state=text(res,"status");
        if(ReservationDeadline.expiredPending(res,now)) {
            endUnused(res,"no_show",now);
            var actual=db.one("SELECT id,status,termination_reason,ended_at FROM reservations WHERE id=?",resId);
            var details=new LinkedHashMap<String,Object>(actual);details.put("reservation_id",resId);details.put("allowed_actions",List.of());
            return new ActionResult(409,"签到期限已到，预约已按未签到结束，请刷新记录",Map.of(),details);
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
                change(resId,"checked_in","checkin_at",now);notify(uid,"reservation_checkin","签到成功","座位码核验成功，预约已进入使用中。");
                auditReservation(uid,"reservation.checkin",res,"用户核验当前有效座位码后签到");
            }
            case "leave" -> {
                Api.require(state.equals("checked_in")&&now.isBefore(end),409,"当前状态不能暂离");change(resId,"temp_leave","leave_at",now);auditReservation(uid,"reservation.leave",res,"用户暂离（旧流程，待S10退出）");
            }
            case "return" -> {
                Api.require(state.equals("temp_leave"),409,"当前状态不能返回");
                Api.require(res.get("leave_at")!=null,409,"暂离时间缺失，不能执行返回，请签退或核实");
                Api.require(now.isBefore(end)&&!now.isAfter(LocalDateTime.parse(text(res,"leave_at")).plusMinutes(30)),400,"暂离已超时");
                change(resId,"checked_in",null,null);auditReservation(uid,"reservation.return",res,"用户从暂离返回（旧流程）");
            }
            case "checkout" -> {
                Api.require(state.equals("checked_in")||state.equals("temp_leave"),409,"当前状态不能签退");complete(res,now.isAfter(end)?end:now,now.isBefore(end)?"early_checkout":"auto_end",uid);
            }
            default -> throw new IllegalStateException("未覆盖的预约动作");
        }
        var actual=db.one("SELECT id,status,ended_at,termination_reason FROM reservations WHERE id=?",resId);
        actual.put("action",actual.get("status"));actual.put("allowed_actions",allowedActions(db.one("SELECT * FROM reservations WHERE id=?",resId),now));
        if(action.equals("cancel")) actual.put("cancelled",resId);
        return new ActionResult(200,"ok",actual,null);
    }
    private List<String> allowedActions(Map<String,Object> row,LocalDateTime now) {
        String state=text(row,"status");LocalDateTime start=starts(row),end=ends(row);
        if(state.equals("pending")) {
            if(now.isBefore(start)) return List.of("cancel");
            if(now.isBefore(ReservationDeadline.at(row))&&now.isBefore(end)) return List.of("checkin","abandon");
        }
        if(state.equals("checked_in")) return now.isBefore(end)?List.of("leave","checkout"):List.of("checkout");
        if(state.equals("temp_leave")) return row.get("leave_at")!=null&&now.isBefore(end)&&!now.isAfter(LocalDateTime.parse(text(row,"leave_at")).plusMinutes(30))?List.of("return","checkout"):List.of("checkout");
        return List.of();
    }
    private void reconcileUnused(long uid,LocalDateTime now) { reconcilePending(db.list("SELECT * FROM reservations WHERE user_id=? AND status='pending' ORDER BY id",uid),now); }
    public void reconcileRoom(long roomId,LocalDate date) {
        db.lockBookings();db.one("SELECT id FROM rooms WHERE id=?",roomId);
        reconcilePending(db.list("SELECT rv.* FROM reservations rv JOIN seats s ON s.id=rv.seat_id WHERE s.room_id=? AND rv.res_date=? AND rv.status='pending' ORDER BY rv.id",roomId,Date.valueOf(date)),LocalDateTime.now(clock));
    }
    private void reconcilePending(List<Map<String,Object>> rows,LocalDateTime now) {
        for(var row:rows) if(ReservationDeadline.expiredPending(row,now)) endUnused(row,"no_show",now);
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
    private void change(long resId,String state,String timeColumn,LocalDateTime time) {
        if(timeColumn==null) db.update("UPDATE reservations SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",state,resId);
        else db.update("UPDATE reservations SET status=?,"+timeColumn+"=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",state,Timestamp.valueOf(time),resId);
    }
    private void complete(Map<String,Object> res,LocalDateTime time,String reason,Long actor) {
        db.update("UPDATE reservations SET status='completed',checkout_at=?,ended_at=?,termination_reason=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",Timestamp.valueOf(time),Timestamp.valueOf(time),reason,id(res,"id"));
        auditReservation(actor,"reservation.complete",res,"预约使用结束，无信用奖励");
    }
    private void auditReservation(Long actor,String action,Map<String,Object> before,String reason) {
        var after=db.one("SELECT * FROM reservations WHERE id=?",id(before,"id"));
        audit.record(actor,action,"reservation",Long.toString(id(before,"id")),reason,reservationSnapshot(before),reservationSnapshot(after));
    }
    private Map<String,Object> reservationSnapshot(Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>();for(String field:List.of("user_id","seat_id","res_date","start_time","end_time","source","status","checkin_at","checkout_at","leave_at","ended_at","termination_reason")) result.put(field,row.get(field));return result;
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
        db.lockBookings();LocalDateTime now=LocalDateTime.now(clock);
        for(var res:db.list("SELECT * FROM reservations WHERE status IN "+ACTIVE+" ORDER BY id")) {
            String state=text(res,"status");
            if(ReservationDeadline.expiredPending(res,now)) {
                endUnused(res,"no_show",now);
            } else if(!state.equals("pending")&&!now.isBefore(ends(res))) complete(res,ends(res),"auto_end",null);
            else if(state.equals("temp_leave")&&res.get("leave_at")!=null&&now.isAfter(LocalDateTime.parse(text(res,"leave_at")).plusMinutes(30))) complete(res,now,"temp_leave_timeout",null);
        }
        // 已退出候补递补；历史记录保持原状，只有本人主动退出才修改waiting。
    }
    private void requireOrdinary(long uid) {
        Api.require(AuthService.isOrdinary(db.one("SELECT role FROM users WHERE id=?",uid).get("role")),403,"仅学生和教职工可操作本人预约或候补");
    }
    private LocalDateTime starts(Map<String,Object> r) { return Slot.endpoint(LocalDate.parse(text(r,"res_date")),text(r,"start_time")); }
    private LocalDateTime ends(Map<String,Object> r) { return Slot.endpoint(LocalDate.parse(text(r,"res_date")),text(r,"end_time")); }
}
