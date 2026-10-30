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
    private static final String VIEW="SELECT rv.*,s.seat_no,s.zone,s.has_power,s.room_id,r.name AS room_name,r.building,r.floor,r.location,u.username,u.real_name FROM reservations rv JOIN seats s ON s.id=rv.seat_id JOIN rooms r ON r.id=s.room_id JOIN users u ON u.id=rv.user_id ";
    private final Store db;
    private final Clock clock;
    private final OpeningScheduleService opening;
    private final BookingPolicy policy;
    private final AuditService audit;
    private final ViolationService violations;
    private final SeatCodeService codes;
    private final UsageService usage;
    private final RestrictionPolicy restrictions;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    public BookingService(Store db,Clock clock,OpeningScheduleService opening,BookingPolicy policy,AuditService audit,ViolationService violations,SeatCodeService codes,UsageService usage,RestrictionPolicy restrictions,com.fasterxml.jackson.databind.ObjectMapper json) { this.db=db;this.clock=clock;this.opening=opening;this.policy=policy;this.audit=audit;this.violations=violations;this.codes=codes;this.usage=usage;this.restrictions=restrictions;this.json=json; }
    public List<Map<String,Object>> mine(long uid) {return RecordPage.items(records(uid,RecordPage.legacy(Map.of(),RecordPage.Domain.RESERVATION,false),false));}
    public Map<String,Object> records(long actor,Map<String,String> parameters,boolean admin){return records(actor,RecordPage.parse(parameters,RecordPage.Domain.RESERVATION,admin),admin);}
    private Map<String,Object> records(long actor,RecordPage query,boolean admin){
        db.lockBookings();if(admin)correctionAdmin(actor);LocalDateTime snapshot=now();Long user=admin?query.user():Long.valueOf(actor);if(user==null)settleRestrictions(snapshot);else if(db.count("SELECT count(*) FROM users WHERE id=?",user)>0)enforceRestrictions(user,snapshot,null,null);var selected=query.select(db,RecordPage.Domain.RESERVATION,user,snapshot);var rows=selected.ids().isEmpty()?new ArrayList<Map<String,Object>>():db.list(VIEW+"WHERE rv.id IN ("+RecordPage.marks(selected.ids())+") ORDER BY rv.res_date DESC,rv.start_time DESC,rv.id DESC",selected.ids().toArray());for(var row:rows)decorate(row,snapshot);return query.result(rows,selected.total(),snapshot);
    }
    public Map<String,Object> violationRecords(long actor,Map<String,String> parameters,boolean admin){
        var query=RecordPage.parse(parameters,RecordPage.Domain.VIOLATION,admin);db.lockBookings();if(admin)correctionAdmin(actor);var snapshot=now();Long user=admin?query.user():Long.valueOf(actor);if(user==null)settleRestrictions(snapshot);else if(db.count("SELECT count(*) FROM users WHERE id=?",user)>0)enforceRestrictions(user,snapshot,null,null);return violations.records(query,user,snapshot,admin);
    }
    public Map<String,Object> violationDetail(long actor,long id){db.lockBookings();requireOrdinary(actor);Api.require(Store.id(db.one("SELECT user_id FROM violations WHERE id=?",id),"user_id")==actor,403,"无权读取他人违规");enforceRestrictions(actor,now(),null,null);return ViolationService.safeOne(db,id);}
    public Map<String,Object> detail(long uid,long resId) {
        db.lockBookings();requireOrdinary(uid);var row=db.one(VIEW+"WHERE rv.id=?",resId);
        Api.require(id(row,"user_id")==uid,403,"无权查看他人的预约");LocalDateTime now=now();
        enforceRestrictions(uid,now,null,null);row=db.one(VIEW+"WHERE rv.id=?",resId);decorate(row,now);return row;
    }
    public List<Map<String,Object>> violationsMine(long uid) {
        db.lockBookings();enforceRestrictions(uid,now(),null,null);return violations.mine(uid);
    }
    /** 成功调用的兼容适配；HTTP使用typed入口以在代理提交后映射已落实限制的403。 */
    public Map<String,Object> create(long uid,Requests.Reservation req) {var result=createForClient(uid,req);if(result.status()!=200)throw Api.fail(result.status(),result.message(),result.details());return result.data();}
    public ActionResult createForClient(long uid,Requests.Reservation req) {
        db.lockBookings();Slot slot=Slot.parse(req.date(),req.startTime(),req.endTime());policy.validate(slot);requireBookingAccount(uid);var now=now();enforceRestrictions(uid,now,null,null);var qualification=restrictions.eligibility(uid,now);
        if(!Boolean.TRUE.equals(qualification.get("can_book")))return new ActionResult(403,"当前存在有效预约限制，请查看每条原因与截止",Map.of(),restrictions.denial(qualification));return new ActionResult(200,"ok",createInternal(uid,req,"manual",now),null);
    }
    private Map<String,Object> createInternal(long uid,Requests.Reservation req,String source,LocalDateTime snapshot) {
        Slot slot=Slot.parse(req.date(),req.startTime(),req.endTime()); policy.validate(slot);eligible(uid,snapshot);
        var seat=db.one("SELECT * FROM seats WHERE id=?",req.seatId());
        Api.require("available".equals(seat.get("status")),409,"座位当前不可预约");
        var room=db.one("SELECT * FROM rooms WHERE id=?",id(seat,"room_id"));opening.requireSlot(id(seat,"room_id"),req.seatId(),slot);
        reconcileScope(db.list("SELECT * FROM reservations WHERE status IN "+ACTIVE+" AND (user_id=? OR seat_id=?) ORDER BY id",uid,req.seatId()),snapshot,uid);
        Api.require(!conflict("seat_id",req.seatId(),slot),409,"该座位此时段已被预约");
        Api.require(!conflict("user_id",uid,slot),409,"您在同一时段已有其他预约");
        long resId=db.insert("INSERT INTO reservations(user_id,seat_id,res_date,start_time,end_time,source) VALUES(?,?,?,CAST(? AS TIME),CAST(? AS TIME),?)",
                uid,req.seatId(),Date.valueOf(slot.date()),slot.startText(),slot.endText(),source);
        notify(uid,"reservation_success","预约成功",room.get("name")+" "+seat.get("seat_no")+" "+req.date()+" "+req.startTime()+"—"+req.endTime()+"，请按时签到。");
        audit.record(uid,"reservation.create","reservation",Long.toString(resId),"用户预约",Map.of(),Map.of("seat_id",req.seatId(),"date",slot.date().toString(),"start_time",slot.startText(),"end_time",slot.endText(),"source",source));
        var created=db.one(VIEW+"WHERE rv.id=?",resId);decorate(created,snapshot);return created;
    }
    private boolean conflict(String column,long id,Slot slot) {
        return db.count("SELECT count(*) FROM reservations WHERE "+column+"=? AND res_date=? AND status IN "+ACTIVE+" AND start_time<CAST(? AS TIME) AND end_time>CAST(? AS TIME)",
                id,Date.valueOf(slot.date()),slot.endText(),slot.startText())>0;
    }
    private void eligible(long uid,LocalDateTime snapshot) {requireBookingAccount(uid);var qualification=restrictions.eligibility(uid,snapshot);Api.require(Boolean.TRUE.equals(qualification.get("can_book")),403,"当前存在有效预约限制，请查看每条原因与截止");}
    private void requireBookingAccount(long uid) {
        var user=db.one("SELECT * FROM users WHERE id=?",uid);
        Api.require(AuthService.isOrdinary(user.get("role")),403,"仅学生和教职工可以预约");
        Api.require("active".equals(user.get("status")),403,"账号已被禁用");
    }
    public Map<String,Object> legacyCredit(long uid) {
        var user=db.one("SELECT credit_score,credit_banned_until FROM users WHERE id=?",uid);var result=new LinkedHashMap<String,Object>();
        Object until=user.get("credit_banned_until");result.put("score",user.get("credit_score"));result.put("banned_until",until);
        result.put("legacy_banned_until",until==null?null:LocalDateTime.parse(until.toString()).atZone(BookingPolicy.CAMPUS_ZONE).toOffsetDateTime().toString());
        result.put("legacy_ban_active",restrictions.legacyActive(uid,now()));result.put("history_only",true);result.put("read_only",true);result.put("server_now",clock.instant().atZone(BookingPolicy.CAMPUS_ZONE).toOffsetDateTime().toString());result.put("campus_zone",BookingPolicy.CAMPUS_ZONE.getId());
        result.put("logs",db.list("SELECT * FROM credit_logs WHERE user_id=? ORDER BY id DESC LIMIT 50",uid));return result;
    }
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
        LocalDateTime now=now();String previousState=text(res,"status");enforceRestrictions(uid,now,null,null);res=db.one("SELECT * FROM reservations WHERE id=?",resId);
        if(!previousState.equals(res.get("status"))) {
            var actual=db.one("SELECT id,status,termination_reason,termination_note,ended_at FROM reservations WHERE id=?",resId);decorate(actual,now);actual.put("reservation_id",resId);
            String message=switch(text(res,"status")){case "unused_ended"->"签到期限已到，预约已按未签到结束，请刷新记录";case "completed"->"预约已到期并完成使用结算，请刷新记录";default->"预约已因当前有效限制管理取消，未执行本次动作，请刷新记录";};return new ActionResult(409,message,Map.of(),actual);
        }
        LocalDateTime start=starts(res),end=ends(res);String state=text(res,"status");res.put("usage_record",usage.view(resId,now));
        switch(action) {
            case "cancel" -> {
                Api.require(state.equals("pending"),409,"当前状态不能取消预约");Api.require(now.isBefore(start),409,"预约已经开始，请使用放弃操作");
                db.update("UPDATE reservations SET status='cancelled',ended_at=?,termination_reason='user_cancelled',updated_at=CURRENT_TIMESTAMP WHERE id=?",Timestamp.valueOf(now),resId);
                notify(uid,"reservation_cancelled","预约已取消","您在开始前取消了预约，本次不登记违规。");
                auditReservation(uid,"reservation.cancel",res,"用户开始前取消预约");
            }
            case "abandon" -> {
                Api.require(state.equals("pending"),409,"当前状态不能放弃预约");Api.require(!now.isBefore(start),409,"开始前请使用取消操作");
                endUnused(res,"abandon",now);enforceRestrictions(uid,now,null,null);
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
        reconcileScope(db.list("SELECT rv.* FROM reservations rv JOIN seats s ON s.id=rv.seat_id WHERE s.room_id=? AND rv.res_date=? AND rv.status IN "+ACTIVE+" ORDER BY rv.id",roomId,Date.valueOf(date)),now(),null);
    }
    public void reconcileAvailability(long uid,long roomId,LocalDate date,LocalDateTime snapshot) {
        db.lockBookings();requireOrdinary(uid);
        reconcileScope(db.list("SELECT rv.* FROM reservations rv JOIN seats s ON s.id=rv.seat_id WHERE rv.res_date=? AND rv.status IN "+ACTIVE+" AND (s.room_id=? OR rv.user_id=?) ORDER BY rv.id",Date.valueOf(date),roomId,uid),snapshot,uid);
    }
    private void reconcileScope(List<Map<String,Object>> rows,LocalDateTime snapshot,Long caller){
        reconcileRows(rows,snapshot);var users=new TreeSet<Long>();if(caller!=null)users.add(caller);for(var row:rows)users.add(id(row,"user_id"));for(long user:users)enforceRestrictions(user,snapshot,null,null);
    }
    private void reconcileRows(List<Map<String,Object>> rows,LocalDateTime now) {
        for(var row:rows) {
            if(ReservationDeadline.expiredPending(row,now)) endUnused(row,"no_show",now);
            else if("checked_in".equals(row.get("status"))&&!now.isBefore(ends(row))) complete(row,ends(row),"auto_end",null,now);
        }
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Map<String,Object> cancelForResource(long actor,long reservation,String publicReason,LocalDateTime snapshot) {
        db.lockBookings();var admin=db.one("SELECT role,status FROM users WHERE id=?",actor);Api.require("admin".equals(admin.get("role"))&&"active".equals(admin.get("status")),403,"需要管理员权限");
        var before=db.one("SELECT * FROM reservations WHERE id=?",reservation);Api.require(OpeningScheduleService.remaining(before,snapshot)!=null,409,"预约状态已变化，请重新预览");return cancelManaged(actor,before,publicReason,"admin_resource_change",snapshot,null);
    }
    private Map<String,Object> cancelManaged(Long actor,Map<String,Object> before,String reason,String tag,LocalDateTime snapshot,Long restrictionId) {
        long reservation=id(before,"id");before.put("usage_record",usage.view(reservation,snapshot));UsageService.requireReservationState(text(before,"status"),usage.view(reservation,snapshot),snapshot);
        if("checked_in".equals(before.get("status"))){Api.require(restrictionId==null,409,"使用中预约不由预约限制提前结束");usage.finish(reservation,ends(before),snapshot,tag,snapshot);}
        LocalDateTime end=snapshot.isBefore(ends(before))?snapshot:ends(before);db.update("UPDATE reservations SET status='cancelled',ended_at=?,termination_reason=?,termination_note=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",Timestamp.valueOf(end),tag,reason,reservation);
        if(restrictionId==null)notify(id(before,"user_id"),"management_cancelled","预约因资源管理已取消","管理原因："+reason+"。受影响预约已立即取消，已用时长按实际确认时刻结算，本次不登记用户违规或信用奖惩。");
        else db.update("INSERT INTO notifications(user_id,type,title,content,restriction_id,created_at) VALUES(?,'restriction_cancelled','待签到预约因限制取消',?,?,?)",id(before,"user_id"),"预约"+reservation+"因当前有效限制取消。\n公开原因：\n"+reason+"\n本次管理取消不另登记用户违约。",restrictionId,Timestamp.valueOf(snapshot));
        if(restrictionId==null)auditReservation(actor,"reservation.admin_cancel",before,reason);else {var after=db.one("SELECT * FROM reservations WHERE id=?",reservation);after.put("usage_record",usage.view(reservation,snapshot));var values=reservationSnapshot(after);values.put("restriction_id",restrictionId);audit.record(actor,"reservation.restriction_cancel","reservation",Long.toString(reservation),reason,reservationSnapshot(before),values);}
        var result=db.one("SELECT id AS reservation_id,status,termination_reason,termination_note,ended_at FROM reservations WHERE id=?",reservation);result.put("usage_record",usage.view(reservation,snapshot));return result;
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Map<String,Object> reconcileForRestriction(long user,LocalDateTime snapshot) {
        db.lockBookings();var before=db.list("SELECT id FROM reservations WHERE user_id=? AND status='pending'",user).stream().map(row->row.get("id")).toArray();reconcileUser(user,snapshot);long unused=0;
        if(before.length>0){String marks=String.join(",",Collections.nCopies(before.length,"?"));unused=db.count("SELECT count(*) FROM reservations WHERE id IN ("+marks+") AND status='unused_ended' AND termination_reason='no_show'",before);}
        var result=new LinkedHashMap<String,Object>();result.put("cancelled_pending",new ArrayList<Map<String,Object>>());result.put("settled_unused_count",unused);result.put("continued_checked_in_count",db.count("SELECT count(*) FROM reservations WHERE user_id=? AND status='checked_in'",user));return result;
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Map<String,Object> enforceRestrictions(long user,LocalDateTime snapshot,Long actor,Long preferred) {
        var result=reconcileForRestriction(user,snapshot);restrictions.assess(user,snapshot);var active=new ArrayList<>(restrictions.activeRaw(user,snapshot));if(preferred!=null)active.sort(Comparator.comparingInt(row->id(row,"id")==preferred?0:1));@SuppressWarnings("unchecked") var cancelled=(List<Map<String,Object>>)result.get("cancelled_pending");
        for(var restriction:active){var own=new ArrayList<Map<String,Object>>();for(var booking:db.list("SELECT * FROM reservations WHERE user_id=? AND status='pending' ORDER BY id",user)){if(OpeningScheduleService.remaining(booking,snapshot)==null)continue;var change=cancelManaged(actor,booking,text(restriction,"reason"),"admin_restriction",snapshot,id(restriction,"id"));change.remove("usage_record");change.put("ended_at",RestrictionPolicy.offset(change.get("ended_at")));own.add(change);cancelled.add(change);}var effects=new LinkedHashMap<String,Object>(result);effects.put("cancelled_pending",own);restrictions.applied(restriction,effects,actor,snapshot);}
        return result;
    }
    private void endUnused(Map<String,Object> res,String type,LocalDateTime now) {
        long reservation=id(res,"id"),uid=id(res,"user_id");LocalDateTime occurred=type.equals("no_show")?ReservationDeadline.at(res):now;
        String reason=type.equals("no_show")?"签到期限到达仍未签到":"预约开始后主动放弃且未签到";
        db.update("UPDATE reservations SET status='unused_ended',ended_at=?,termination_reason=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",Timestamp.valueOf(occurred),type,reservation);
        long violation=violations.recordUnused(uid,reservation,type,occurred,now,reason);
        violations.notifyUnused(uid,violation,reason,now);
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
        var result=new LinkedHashMap<String,Object>();for(String field:List.of("user_id","seat_id","res_date","start_time","end_time","source","status","checkin_at","checkout_at","leave_at","ended_at","termination_reason","termination_note","usage_record")) result.put(field,row.get(field));return result;
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
        db.lockBookings();settleRestrictions(now());
        // 已退出候补递补；历史记录保持原状，只有本人主动退出才修改waiting。
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void settleRestrictions(LocalDateTime snapshot){
        db.lockBookings();var rows=db.list("SELECT * FROM reservations WHERE status IN "+ACTIVE+" ORDER BY id");reconcileRows(rows,snapshot);
        var users=new TreeSet<>(restrictions.candidateUsers(snapshot));for(var row:rows)users.add(id(row,"user_id"));for(long user:users)enforceRestrictions(user,snapshot,null,null);
    }
    private void requireOrdinary(long uid) {
        Api.require(AuthService.isOrdinary(db.one("SELECT role FROM users WHERE id=?",uid).get("role")),403,"仅学生和教职工可操作本人预约或候补");
    }
    private void correctionAdmin(long actor){var user=db.one("SELECT role,status FROM users WHERE id=?",actor);Api.require("admin".equals(user.get("role"))&&"active".equals(user.get("status")),403,"需要启用的管理员权限");}
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> correctionDetail(long actor,long reservation){correctionAdmin(actor);return correctionView(reservation,now());}
    private Map<String,Object> correctionView(long reservation,LocalDateTime snapshot){
        var row=db.one(VIEW+"WHERE rv.id=?",reservation);decorate(row,snapshot);var result=new LinkedHashMap<String,Object>();result.put("reservation",row);result.put("server_now",AttachmentService.offset(snapshot));String blocked=correctionBlocked(row,snapshot);result.put("can_correct",blocked==null);result.put("blocked_reason",blocked);result.put("deadline",AttachmentService.offset(ReservationDeadline.at(row)));
        var facts=db.list("SELECT correction_note,correction_operation,correction_actor,corrected_at FROM usage_records WHERE reservation_id=? AND origin='admin_correction'",reservation);result.put("management_correction",facts.isEmpty()?null:facts.get(0));return result;
    }
    private String correctionBlocked(Map<String,Object> row,LocalDateTime snapshot){
        if(!"pending".equals(row.get("status")))return "仅待签到预约允许补签；终止或已有使用记录不可恢复或覆盖";
        if(snapshot.isBefore(starts(row)))return "预约尚未开始";
        if(ReservationDeadline.expiredPending(row,snapshot)||!snapshot.isBefore(ends(row)))return "原签到期限已到，不能补签或倒填恢复";
        var user=db.one("SELECT role,status FROM users WHERE id=?",row.get("user_id"));if(!AuthService.isOrdinary(user.get("role"))||!"active".equals(user.get("status")))return "预约主体账号不可使用";
        if(!Boolean.TRUE.equals(restrictions.eligibility(id(row,"user_id"),snapshot).get("can_book")))return "当前仍存在预约限制";
        var seat=db.one("SELECT status,room_id FROM seats WHERE id=?",row.get("seat_id"));if(!"available".equals(seat.get("status")))return "座位当前不可使用";
        if(!opening.calendar(id(seat,"room_id")).day(LocalDate.parse(text(row,"res_date")),id(row,"seat_id")).contains(LocalDate.parse(text(row,"res_date")),snapshot,ends(row)))return "剩余预约不在当前开放范围";
        var candidates=db.list("SELECT * FROM reservations WHERE id<>? AND res_date=? AND status IN "+ACTIVE+" AND (seat_id=? OR user_id=?) AND start_time<CAST(? AS TIME) AND end_time>CAST(? AS TIME)",row.get("id"),Date.valueOf(text(row,"res_date")),row.get("seat_id"),row.get("user_id"),row.get("end_time"),row.get("start_time"));
        if(candidates.stream().anyMatch(other->!ReservationDeadline.expiredPending(other,snapshot)&&snapshot.isBefore(ends(other))))return "存在同座位或本人时段冲突";
        UsageService.requireReservationState("pending",usage.view(id(row,"id"),snapshot),snapshot);return null;
    }
    public ActionResult correctCheckin(long actor,long reservation,String rawOperation,String rawBusiness,String rawReason,String rawNote){
        db.lockBookings();correctionAdmin(actor);String operation=AttachmentService.uuid(rawOperation,"operation_id"),reason=correctionText(rawReason,1000),note=correctionText(rawNote,2000);LocalDateTime business;
        try{business=OffsetDateTime.parse(rawBusiness).atZoneSameInstant(BookingPolicy.CAMPUS_ZONE).toLocalDateTime();Api.require(business.getNano()%1000==0,400,"补签时刻最多六位小数");}catch(java.time.format.DateTimeParseException|NullPointerException e){throw Api.fail(400,"业务签到时刻须明确时区offset");}
        String request;try{request=json.writeValueAsString(Map.of("actor",actor,"reservation",reservation,"business",business.toString(),"reason",reason,"note",note));}catch(Exception e){throw new IllegalStateException(e);}
        var old=db.list("SELECT reservation_id,correction_request=CAST(? AS JSONB) AS same FROM usage_records WHERE correction_operation=CAST(? AS UUID)",request,operation);LocalDateTime snapshot=now();
        if(!old.isEmpty()){Api.require(Boolean.TRUE.equals(old.get(0).get("same"))&&id(old.get(0),"reservation_id")==reservation,409,"操作标识已用于其他补签内容，请保留原请求核对");var replay=correctionView(reservation,snapshot);replay.put("operation_id",operation);replay.put("replayed",true);return new ActionResult(200,"ok",replay,null);}
        var before=db.one("SELECT * FROM reservations WHERE id=?",reservation);Api.require(!business.isBefore(starts(before))&&!business.isAfter(snapshot),400,"业务签到时刻须在预约开始至当前操作时刻内");
        enforceRestrictions(id(before,"user_id"),snapshot,actor,null);var current=db.one("SELECT * FROM reservations WHERE id=?",reservation);String blocked=correctionBlocked(current,snapshot);
        if(blocked!=null)return new ActionResult(409,blocked,Map.of(),correctionView(reservation,snapshot));
        usage.startCorrection(reservation,business,snapshot,actor,operation,reason,note,request);db.update("UPDATE reservations SET status='checked_in',updated_at=CURRENT_TIMESTAMP WHERE id=?",reservation);
        notify(id(before,"user_id"),"reservation_corrected_checkin","管理员已核实补签","预约#"+reservation+"已补签。公开依据："+reason+"。业务签到："+AttachmentService.offset(business)+"；实际操作："+AttachmentService.offset(snapshot)+"。这是管理员核实自报记录。");audit.record(actor,"reservation.correct_checkin","reservation",Long.toString(reservation),reason,Map.of("status",before.get("status")),Map.of("status","checked_in","operation_id",operation,"business_checkin_at",AttachmentService.offset(business),"operated_at",AttachmentService.offset(snapshot),"verification_source","admin_self_report"));
        var result=correctionView(reservation,snapshot);result.put("operation_id",operation);result.put("replayed",false);return new ActionResult(200,"ok",result,null);
    }
    private String correctionText(String raw,int limit){String value=raw==null?"":raw.trim();Api.require(!value.isEmpty()&&value.length()<=limit,400,"公开理由及私有核实依据必填且须在长度限制内");return value;}
    private LocalDateTime now() { return LocalDateTime.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    private LocalDateTime starts(Map<String,Object> r) { return Slot.endpoint(LocalDate.parse(text(r,"res_date")),text(r,"start_time")); }
    private LocalDateTime ends(Map<String,Object> r) { return Slot.endpoint(LocalDate.parse(text(r,"res_date")),text(r,"end_time")); }
}
