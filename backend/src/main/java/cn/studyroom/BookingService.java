package cn.studyroom;

import java.sql.Date;
import java.sql.Time;
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
    public BookingService(Store db,Clock clock) { this.db=db;this.clock=clock; }
    public List<Map<String,Object>> mine(long uid) { return db.list(VIEW+"WHERE rv.user_id=? ORDER BY rv.res_date DESC,rv.start_time DESC,rv.id DESC",uid); }
    public Map<String,Object> create(long uid,Requests.Reservation req) {
        db.lockBookings();return createInternal(uid,req,"manual");
    }
    private Map<String,Object> createInternal(long uid,Requests.Reservation req,String source) {
        Slot slot=Slot.parse(req.date(),req.startTime(),req.endTime()); slot.validateFuture(clock);eligible(uid);
        var seat=db.one("SELECT * FROM seats WHERE id=?",req.seatId());
        Api.require("available".equals(seat.get("status")),409,"座位当前不可预约");
        var room=db.one("SELECT * FROM rooms WHERE id=?",id(seat,"room_id"));slot.validateRoom(room);
        Api.require(!conflict("seat_id",req.seatId(),slot),409,"该座位此时段已被预约");
        Api.require(!conflict("user_id",uid,slot),409,"您在同一时段已有其他预约");
        long resId=db.insert("INSERT INTO reservations(user_id,seat_id,res_date,start_time,end_time,source) VALUES(?,?,?,?,?,?)",
                uid,req.seatId(),Date.valueOf(slot.date()),Time.valueOf(slot.start()),Time.valueOf(slot.end()),source);
        notify(uid,"reservation_success","预约成功",room.get("name")+" "+seat.get("seat_no")+" "+req.date()+" "+req.startTime()+"—"+req.endTime()+"，请按时签到。");
        return db.one(VIEW+"WHERE rv.id=?",resId);
    }
    private boolean conflict(String column,long id,Slot slot) {
        return db.count("SELECT count(*) FROM reservations WHERE "+column+"=? AND res_date=? AND status IN "+ACTIVE+" AND start_time<? AND end_time>?",
                id,Date.valueOf(slot.date()),Time.valueOf(slot.end()),Time.valueOf(slot.start()))>0;
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
    public Map<String,Object> allocate(long uid,Requests.Allocation req) {
        db.lockBookings();Slot slot=Slot.parse(req.date(),req.startTime(),req.endTime());slot.validateFuture(clock);eligible(uid);
        slot.validateRoom(db.one("SELECT * FROM rooms WHERE id=?",req.roomId()));
        Api.require(!conflict("user_id",uid,slot),409,"您在同一时段已有预约");
        var candidates=candidates(req.roomId(),slot);var scored=new ArrayList<Map<String,Object>>();
        for(var seat:candidates) {
            double score=0;var reasons=new ArrayList<String>();
            if(req.zone()!=null&&req.zone().equals(seat.get("zone"))) { score+=30;reasons.add("匹配区域偏好"); }
            if(Boolean.TRUE.equals(req.hasPower())&&Boolean.TRUE.equals(seat.get("has_power"))) { score+=25;reasons.add("带电源插座"); }
            if(Boolean.TRUE.equals(req.nearWindow())&&Boolean.TRUE.equals(seat.get("near_window"))) { score+=15;reasons.add("靠窗采光好"); }
            long recent=db.count("SELECT count(*) FROM reservations WHERE seat_id=? AND res_date>=? AND status='completed'",id(seat,"id"),Date.valueOf(LocalDate.now(clock).minusDays(7)));
            score-=Math.min(20,recent);if(recent==0) reasons.add("近期使用率低");
            scored.add(Map.of("seat",seat,"score",score,"reasons",reasons));
        }
        scored.sort(Comparator.<Map<String,Object>>comparingDouble(x->((Number)x.get("score")).doubleValue()).reversed());
        Api.require(!scored.isEmpty(),409,"当前时段没有空座位，可尝试候补");
        var result=new LinkedHashMap<String,Object>();result.put("recommendations",scored.subList(0,Math.min(req.topN()==0?5:req.topN(),scored.size())));result.put("booked",false);
        if(req.autoBook()) {
            @SuppressWarnings("unchecked") var best=(Map<String,Object>)scored.get(0).get("seat");
            result.put("reservation",createInternal(uid,new Requests.Reservation(id(best,"id"),req.date(),req.startTime(),req.endTime()),"auto"));result.put("booked",true);
        }
        return result;
    }
    private List<Map<String,Object>> candidates(long roomId,Slot slot) {
        return db.list("SELECT s.* FROM seats s WHERE s.room_id=? AND s.status='available' AND NOT EXISTS(SELECT 1 FROM reservations rv WHERE rv.seat_id=s.id AND rv.res_date=? AND rv.status IN "+ACTIVE+" AND rv.start_time<? AND rv.end_time>?) ORDER BY row_no,col_no",
                roomId,Date.valueOf(slot.date()),Time.valueOf(slot.end()),Time.valueOf(slot.start()));
    }
    public Map<String,Object> join(long uid,Requests.Wait req) {
        db.lockBookings();Slot slot=Slot.parse(req.date(),req.startTime(),req.endTime());slot.validateFuture(clock);eligible(uid);
        slot.validateRoom(db.one("SELECT * FROM rooms WHERE id=?",req.roomId()));
        Api.require(!conflict("user_id",uid,slot),409,"您在同一时段已有预约");
        Api.require(db.count("SELECT count(*) FROM waitlist WHERE user_id=? AND room_id=? AND res_date=? AND start_time=? AND end_time=? AND status='waiting'",
                uid,req.roomId(),Date.valueOf(slot.date()),Time.valueOf(slot.start()),Time.valueOf(slot.end()))==0,409,"已在该时段候补队列中");
        long waitId=db.insert("INSERT INTO waitlist(user_id,room_id,res_date,start_time,end_time,zone,has_power,near_window) VALUES(?,?,?,?,?,?,?,?)",
                uid,req.roomId(),Date.valueOf(slot.date()),Time.valueOf(slot.start()),Time.valueOf(slot.end()),AuthService.blankNull(req.zone()),req.hasPower(),req.nearWindow());
        return waitMine(uid).stream().filter(x->id(x,"id")==waitId).findFirst().orElseThrow();
    }
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
        for(var wait:db.list("SELECT * FROM waitlist WHERE status='waiting' ORDER BY id")) {
            Slot slot=Slot.parse(text(wait,"res_date"),text(wait,"start_time"),text(wait,"end_time"));
            if(slot.startsAt().isBefore(now.plusMinutes(10))) { db.update("UPDATE waitlist SET status='expired',updated_at=CURRENT_TIMESTAMP WHERE id=?",id(wait,"id"));continue; }
            long uid=id(wait,"user_id");
            try { eligible(uid); } catch(Api.Failure e) { continue; }
            if(conflict("user_id",uid,slot)) continue;
            for(var seat:candidates(id(wait,"room_id"),slot)) {
                if(wait.get("zone")!=null&&!wait.get("zone").equals(seat.get("zone"))) continue;
                if(wait.get("has_power")!=null&&!wait.get("has_power").equals(seat.get("has_power"))) continue;
                if(wait.get("near_window")!=null&&!wait.get("near_window").equals(seat.get("near_window"))) continue;
                createInternal(uid,new Requests.Reservation(id(seat,"id"),text(wait,"res_date"),text(wait,"start_time"),text(wait,"end_time")),"waitlist");
                db.update("UPDATE waitlist SET status='promoted',updated_at=CURRENT_TIMESTAMP WHERE id=?",id(wait,"id"));
                notify(uid,"waitlist_promoted","候补成功","已为您预约 "+seat.get("seat_no")+"，请按时签到。");break;
            }
        }
    }
    public void setUserStatus(long actor,long uid,String status) {
        db.lockBookings();Api.require(actor!=uid,400,"不能禁用当前登录账号");
        db.one("SELECT id FROM users WHERE id=?",uid);db.update("UPDATE users SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",status,uid);
    }
    private void requireOrdinary(long uid) {
        Api.require(AuthService.isOrdinary(db.one("SELECT role FROM users WHERE id=?",uid).get("role")),403,"仅学生和教职工可操作本人预约或候补");
    }
    private LocalDateTime starts(Map<String,Object> r) { return LocalDate.parse(text(r,"res_date")).atTime(LocalTime.parse(text(r,"start_time"))); }
    private LocalDateTime ends(Map<String,Object> r) { return LocalDate.parse(text(r,"res_date")).atTime(LocalTime.parse(text(r,"end_time"))); }
}
