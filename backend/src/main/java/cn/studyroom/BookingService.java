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
    public BookingService(Store db,Clock clock,OpeningScheduleService opening,BookingPolicy policy,AuditService audit) { this.db=db;this.clock=clock;this.opening=opening;this.policy=policy;this.audit=audit; }
    public List<Map<String,Object>> mine(long uid) { return db.list(VIEW+"WHERE rv.user_id=? ORDER BY rv.res_date DESC,rv.start_time DESC,rv.id DESC",uid); }
    public Map<String,Object> create(long uid,Requests.Reservation req) {
        db.lockBookings();return createInternal(uid,req,"manual");
    }
    private Map<String,Object> createInternal(long uid,Requests.Reservation req,String source) {
        Slot slot=Slot.parse(req.date(),req.startTime(),req.endTime()); policy.validate(slot);eligible(uid);
        var seat=db.one("SELECT * FROM seats WHERE id=?",req.seatId());
        Api.require("available".equals(seat.get("status")),409,"座位当前不可预约");
        var room=db.one("SELECT * FROM rooms WHERE id=?",id(seat,"room_id"));opening.requireSlot(id(seat,"room_id"),req.seatId(),slot);
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
        var until=user.get("credit_banned_until");
        Api.require(until==null||!LocalDateTime.parse(until.toString()).isAfter(LocalDateTime.now(clock)),403,"信用限制期间不能预约");
    }
    public void action(long uid,long resId,String action) {
        db.lockBookings();requireOrdinary(uid);var res=db.one("SELECT * FROM reservations WHERE id=?",resId);
        Api.require(id(res,"user_id")==uid,403,"无权操作他人的预约");
        String state=text(res,"status"); LocalDateTime now=LocalDateTime.now(clock),start=starts(res),end=ends(res);
        switch(action) {
            case "cancel" -> {
                Api.require(state.equals("pending"),409,"当前状态不能取消预约");
                change(resId,"cancelled",null,null);
                if(Duration.between(now,start).toMinutes()<30) credit(res,-2,"开始前30分钟内取消");
            }
            case "checkin" -> {
                Api.require(state.equals("pending"),409,"当前状态不能签到");
                Api.require(!now.isBefore(start.minusMinutes(15))&&!now.isAfter(start.plusMinutes(15))&&now.isBefore(end),400,"不在签到窗口内（开始前后15分钟）");
                change(resId,"checked_in","checkin_at",now);
            }
            case "leave" -> {
                Api.require(state.equals("checked_in")&&now.isBefore(end),409,"当前状态不能暂离");change(resId,"temp_leave","leave_at",now);
            }
            case "return" -> {
                Api.require(state.equals("temp_leave"),409,"当前状态不能返回");
                Api.require(now.isBefore(end)&&!now.isAfter(LocalDateTime.parse(text(res,"leave_at")).plusMinutes(30)),400,"暂离已超时");
                change(resId,"checked_in",null,null);
            }
            case "checkout" -> {
                Api.require(state.equals("checked_in")||state.equals("temp_leave"),409,"当前状态不能签退");
                complete(res,now.isAfter(end)?end:now);
            }
            default -> throw Api.fail(404,"操作不存在");
        }
    }
    private void change(long resId,String state,String timeColumn,LocalDateTime time) {
        if(timeColumn==null) db.update("UPDATE reservations SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",state,resId);
        else db.update("UPDATE reservations SET status=?,"+timeColumn+"=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",state,Timestamp.valueOf(time),resId);
    }
    private void complete(Map<String,Object> res,LocalDateTime time) {
        change(id(res,"id"),"completed","checkout_at",time);credit(res,1,"履约完成");
    }
    private void credit(Map<String,Object> res,int delta,String reason) {
        long uid=id(res,"user_id");
        var user=db.one("SELECT * FROM users WHERE id=?",uid);int old=((Number)user.get("credit_score")).intValue();
        int score=Math.max(0,Math.min(100,old+delta));
        LocalDateTime ban=null;
        if(user.get("credit_banned_until")!=null) ban=LocalDateTime.parse(text(user,"credit_banned_until"));
        // 已到期限制不会因为旧的低分重复触发；只有新的扣分才开始新限制。
        if(score<60&&delta<0&&(ban==null||!ban.isAfter(LocalDateTime.now(clock)))) ban=LocalDateTime.now(clock).plusDays(3);
        if(score>=60&&ban!=null&&!ban.isAfter(LocalDateTime.now(clock))) ban=null;
        db.update("UPDATE users SET credit_score=?,credit_banned_until=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",score,ban==null?null:Timestamp.valueOf(ban),uid);
        db.update("INSERT INTO credit_logs(user_id,delta,reason,reservation_id) VALUES(?,?,?,?)",uid,score-old,reason,id(res,"id"));
        notify(uid,delta<0?"violation":"credit_change",delta<0?"信用变动提醒":"履约记录",reason+"，当前信用分："+score);
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
            if(state.equals("pending")&&now.isAfter(starts(res).plusMinutes(15))) {
                change(id(res,"id"),"violation",null,null);credit(res,-8,"超时未签到");
            } else if(!state.equals("pending")&&!now.isBefore(ends(res))) complete(res,ends(res));
            else if(state.equals("temp_leave")&&now.isAfter(LocalDateTime.parse(text(res,"leave_at")).plusMinutes(30))) complete(res,now);
        }
        // 已退出候补递补；历史记录保持原状，只有本人主动退出才修改waiting。
    }
    private void requireOrdinary(long uid) {
        Api.require(AuthService.isOrdinary(db.one("SELECT role FROM users WHERE id=?",uid).get("role")),403,"仅学生和教职工可操作本人预约或候补");
    }
    private LocalDateTime starts(Map<String,Object> r) { return Slot.endpoint(LocalDate.parse(text(r,"res_date")),text(r,"start_time")); }
    private LocalDateTime ends(Map<String,Object> r) { return Slot.endpoint(LocalDate.parse(text(r,"res_date")),text(r,"end_time")); }
}
