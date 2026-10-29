package cn.studyroom;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 开放与停用的唯一判定源；Calendar一次批量读取后可服务多日期、多座位。 */
@Service
public class OpeningScheduleService {
    public static final String ZONE="Asia/Shanghai";
    public record Window(int startMinute,int endMinute) {
        public Requests.OpeningWindow view() { return new Requests.OpeningWindow(format(startMinute),format(endMinute)); }
    }
    public record Day(List<Window> windows,List<String> closedReasons) {
        public List<Requests.OpeningWindow> view() { return windows.stream().map(Window::view).toList(); }
        public boolean contains(LocalDate date,LocalDateTime start,LocalDateTime end) {
            return windows.stream().anyMatch(w->!start.isBefore(date.atStartOfDay().plusMinutes(w.startMinute()))&&!end.isAfter(date.atStartOfDay().plusMinutes(w.endMinute())));
        }
        public boolean contains(Slot slot) { return contains(slot.date(),slot.startsAt(),slot.endsAt()); }
    }
    private record Schedule(Integer weekday,LocalDate date,String reason,List<Window> windows) {}
    public static final class Calendar {
        public final Map<String,Object> room;
        public final List<Map<String,Object>> seats;
        private final List<Schedule> schedules;
        private final List<Map<String,Object>> blocks;
        Calendar(Map<String,Object> room,List<Map<String,Object>> seats,List<Schedule> schedules,List<Map<String,Object>> blocks) { this.room=room;this.seats=seats;this.schedules=schedules;this.blocks=blocks; }
        public Day day(LocalDate date,Long seatId) {
            var reasons=new LinkedHashSet<String>();
            if(!Boolean.TRUE.equals(room.get("enabled"))) return new Day(List.of(),List.of(nonblank(room.get("resource_reason"),"空间已停用")));
            if(seatId!=null) {
                var seat=seats.stream().filter(s->Store.id(s,"id")==seatId).findFirst().orElseThrow(()->Api.fail(404,"座位不属于该空间"));
                if(!"available".equals(seat.get("status"))) return new Day(List.of(),List.of(nonblank(seat.get("resource_reason"),"座位维护或停用")));
            }
            Schedule selected=schedules.stream().filter(s->date.equals(s.date())).findFirst().orElseGet(()->schedules.stream().filter(s->Objects.equals(date.getDayOfWeek().getValue(),s.weekday())).findFirst().orElse(null));
            if(selected==null) return new Day(List.of(),List.of("该日尚未配置开放安排"));
            if(selected.windows().isEmpty()) return new Day(List.of(),List.of(nonblank(selected.reason(),"当日闭馆")));
            List<Window> closed=new ArrayList<>();LocalDateTime left=date.atStartOfDay(),right=date.plusDays(1).atStartOfDay();
            for(var block:blocks) {
                if(block.get("seat_id")!=null&&(seatId==null||Store.id(block,"seat_id")!=seatId)) continue;
                LocalDateTime start=LocalDateTime.parse(Store.text(block,"start_at")),end=LocalDateTime.parse(Store.text(block,"end_at"));
                if(!start.isBefore(right)||!end.isAfter(left)) continue;
                int a=start.isBefore(left)?0:(int)Duration.between(left,start).toMinutes();
                int b=end.isAfter(right)?1440:(int)Duration.between(left,end).toMinutes();
                Window clipped=new Window(a,b);closed.add(clipped);
                if(selected.windows().stream().anyMatch(w->w.startMinute()<b&&a<w.endMinute())) reasons.add(Store.text(block,"reason"));
            }
            return new Day(subtract(selected.windows(),closed),List.copyOf(reasons));
        }
    }
    private final Store db;
    private final AuthService auth;
    private final AuditService audit;
    private final Clock clock;
    private final Validator validator;
    public OpeningScheduleService(Store db,AuthService auth,AuditService audit,Clock clock,Validator validator) { this.db=db;this.auth=auth;this.audit=audit;this.clock=clock;this.validator=validator; }
    public Calendar calendar(long roomId) {
        var room=db.one("SELECT * FROM rooms WHERE id=?",roomId);
        var seats=db.list("SELECT * FROM seats WHERE room_id=? ORDER BY row_no,col_no",roomId);
        var records=db.list("SELECT o.id,o.weekday,o.schedule_date,o.reason,w.start_minute,w.end_minute FROM opening_schedules o LEFT JOIN opening_windows w ON w.schedule_id=o.id WHERE o.room_id=? ORDER BY o.id,w.start_minute",roomId);
        Map<Long,List<Window>> groups=new LinkedHashMap<>();Map<Long,Map<String,Object>> heads=new LinkedHashMap<>();
        for(var row:records) { long id=Store.id(row,"id");heads.put(id,row);groups.computeIfAbsent(id,k->new ArrayList<>());if(row.get("start_minute")!=null) groups.get(id).add(new Window(((Number)row.get("start_minute")).intValue(),((Number)row.get("end_minute")).intValue())); }
        List<Schedule> schedules=new ArrayList<>();
        for(var entry:heads.entrySet()) { var row=entry.getValue();schedules.add(new Schedule(row.get("weekday")==null?null:((Number)row.get("weekday")).intValue(),row.get("schedule_date")==null?null:LocalDate.parse(Store.text(row,"schedule_date")),Store.text(row,"reason"),List.copyOf(groups.get(entry.getKey())))); }
        return new Calendar(room,seats,schedules,db.list("SELECT seat_id,start_at,end_at,reason FROM resource_unavailability WHERE room_id=? AND NOT cancelled ORDER BY id",roomId));
    }
    @Transactional public void initializeDefaults(long roomId) {
        for(int weekday=1;weekday<=7;weekday++) {
            var ids=db.list("SELECT id FROM opening_schedules WHERE room_id=? AND weekday=?",roomId,weekday);
            if(!ids.isEmpty()) continue;
            long id=db.insert("INSERT INTO opening_schedules(room_id,weekday,reason) VALUES(?,?,'新空间默认开放08:00—22:00，可由管理员维护')",roomId,weekday);
            db.update("INSERT INTO opening_windows(schedule_id,start_minute,end_minute) VALUES(?,480,1320)",id);
        }
    }
    public Map<String,Object> publicDay(long roomId,String date,Long seatId) {
        LocalDate day=date(date);var calendar=calendar(roomId);Day result=calendar.day(day,seatId);
        return Map.of("room_id",roomId,"date",day.toString(),"campus_zone",ZONE,"version",Store.id(calendar.room,"version"),"windows",result.view(),"closed_reasons",result.closedReasons());
    }
    public Map<String,Object> management(long roomId) {
        var calendar=calendar(roomId);List<Map<String,Object>> weekly=new ArrayList<>(),exceptions=new ArrayList<>();
        for(var schedule:calendar.schedules) {
            var item=new LinkedHashMap<String,Object>();item.put(schedule.weekday()==null?"date":"weekday",schedule.weekday()==null?schedule.date().toString():schedule.weekday());item.put("windows",schedule.windows().stream().map(Window::view).toList());item.put("reason",schedule.reason());
            (schedule.weekday()==null?exceptions:weekly).add(item);
        }
        weekly.sort(Comparator.comparingInt(x->((Number)x.get("weekday")).intValue()));exceptions.sort(Comparator.comparing(x->x.get("date").toString()));
        return Map.of("room_version",Store.id(calendar.room,"version"),"weekly",weekly,"exceptions",exceptions);
    }
    @Transactional public Map<String,Object> save(long actor,long roomId,Requests.OpeningChange req) {
        db.lockBookings();requireAdmin(actor);validate(req);version(roomId,req.version());
        Api.require((req.weekday()==null)!=(req.date()==null),400,"weekday与date必须且只能提供一个");
        if(req.weekday()!=null) Api.require(req.weekday()>=1&&req.weekday()<=7,400,"weekday须为ISO星期1至7");
        LocalDate date=req.date()==null?null:date(req.date());List<Window> windows=normalize(req.windows());
        var before=management(roomId);var protectedUses=protectedUses(roomId);
        var existing=req.weekday()!=null?db.list("SELECT id FROM opening_schedules WHERE room_id=? AND weekday=?",roomId,req.weekday()):db.list("SELECT id FROM opening_schedules WHERE room_id=? AND schedule_date=?",roomId,java.sql.Date.valueOf(date));
        long id;
        if(existing.isEmpty()) id=db.insert("INSERT INTO opening_schedules(room_id,weekday,schedule_date,reason) VALUES(?,?,?,?)",roomId,req.weekday(),date==null?null:java.sql.Date.valueOf(date),req.reason().trim());
        else { id=Store.id(existing.get(0),"id");db.update("DELETE FROM opening_windows WHERE schedule_id=?",id);db.update("UPDATE opening_schedules SET reason=? WHERE id=?",req.reason().trim(),id); }
        for(Window window:windows) db.update("INSERT INTO opening_windows(schedule_id,start_minute,end_minute) VALUES(?,?,?)",id,window.startMinute(),window.endMinute());
        ensureProtected(roomId,protectedUses);bump(roomId);
        var after=management(roomId);audit.record(actor,"opening.update","room",Long.toString(roomId),req.reason().trim(),before,after);return after;
    }
    @Transactional public Map<String,Object> deleteException(long actor,long roomId,Requests.OpeningDelete req) {
        db.lockBookings();requireAdmin(actor);validate(req);version(roomId,req.version());LocalDate day=date(req.date());
        var before=management(roomId);var protectedUses=protectedUses(roomId);
        var record=db.one("SELECT id FROM opening_schedules WHERE room_id=? AND schedule_date=?",roomId,java.sql.Date.valueOf(day));
        db.update("DELETE FROM opening_schedules WHERE id=?",Store.id(record,"id"));
        ensureProtected(roomId,protectedUses);bump(roomId);var after=management(roomId);audit.record(actor,"opening.exception.delete","room",Long.toString(roomId),req.reason().trim(),before,after);return after;
    }
    public Map<String,Object> blocks(long roomId) {
        long version=Store.id(db.one("SELECT version FROM rooms WHERE id=?",roomId),"version");
        return Map.of("room_version",version,"items",db.list("SELECT id,room_id,seat_id,start_at,end_at,reason,actor_id,version,cancelled,cancel_reason,cancelled_at,created_at,updated_at FROM resource_unavailability WHERE room_id=? ORDER BY start_at,id",roomId));
    }
    @Transactional public Map<String,Object> saveBlock(long actor,long roomId,Long id,Requests.Unavailability req) {
        db.lockBookings();requireAdmin(actor);validate(req);version(roomId,req.version());
        LocalDateTime start=local(req.startAt()),end=local(req.endAt());Api.require(start.isBefore(end),400,"停用结束必须晚于开始");
        if(req.seatId()!=null) db.one("SELECT id FROM seats WHERE id=? AND room_id=?",req.seatId(),roomId);
        var before=id==null?Map.<String,Object>of():db.one("SELECT id,seat_id,start_at,end_at,reason,version,cancelled FROM resource_unavailability WHERE id=? AND room_id=?",id,roomId);
        if(id!=null) Api.require(!Boolean.TRUE.equals(before.get("cancelled")),409,"已解除的停用记录不可编辑");
        var protectedUses=protectedUses(roomId);
        if(id==null) id=db.insert("INSERT INTO resource_unavailability(room_id,seat_id,start_at,end_at,reason,actor_id) VALUES(?,?,?,?,?,?)",roomId,req.seatId(),Timestamp.valueOf(start),Timestamp.valueOf(end),req.reason().trim(),actor);
        else db.update("UPDATE resource_unavailability SET seat_id=?,start_at=?,end_at=?,reason=?,actor_id=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",req.seatId(),Timestamp.valueOf(start),Timestamp.valueOf(end),req.reason().trim(),actor,id);
        ensureProtected(roomId,protectedUses);bump(roomId);
        var after=db.one("SELECT id,seat_id,start_at,end_at,reason,version,cancelled FROM resource_unavailability WHERE id=?",id);
        audit.record(actor,before.isEmpty()?"unavailability.create":"unavailability.update","resource_unavailability",Long.toString(id),req.reason().trim(),before,after);return blocks(roomId);
    }
    @Transactional public Map<String,Object> cancelBlock(long actor,long roomId,long id,Requests.ResourceDelete req) {
        db.lockBookings();requireAdmin(actor);validate(req);version(roomId,req.version());
        var before=db.one("SELECT id,seat_id,start_at,end_at,reason,version,cancelled FROM resource_unavailability WHERE id=? AND room_id=?",id,roomId);Api.require(!Boolean.TRUE.equals(before.get("cancelled")),409,"该停用记录已解除");
        db.update("UPDATE resource_unavailability SET cancelled=true,cancel_reason=?,cancelled_at=?,actor_id=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",req.reason().trim(),Timestamp.valueOf(LocalDateTime.now(clock)),actor,id);bump(roomId);
        var after=db.one("SELECT id,seat_id,start_at,end_at,reason,version,cancelled,cancel_reason,cancelled_at FROM resource_unavailability WHERE id=?",id);
        audit.record(actor,"unavailability.cancel","resource_unavailability",Long.toString(id),req.reason().trim(),before,after);return blocks(roomId);
    }
    private record ProtectedUse(long seatId,LocalDate date,LocalDateTime start,LocalDateTime end) {}
    private List<ProtectedUse> protectedUses(long roomId) {
        LocalDateTime now=LocalDateTime.now(clock);var calendar=calendar(roomId);List<ProtectedUse> result=new ArrayList<>();
        for(var booking:db.list("SELECT rv.* FROM reservations rv JOIN seats s ON s.id=rv.seat_id WHERE s.room_id=? AND rv.status IN ('pending','checked_in')",roomId)) {
            LocalDate day=LocalDate.parse(Store.text(booking,"res_date"));LocalDateTime start=Slot.endpoint(day,Store.text(booking,"start_time")),end=Slot.endpoint(day,Store.text(booking,"end_time"));
            if(!end.isAfter(now)||ReservationDeadline.expiredPending(booking,now)) continue;if(!"pending".equals(booking.get("status"))&&start.isBefore(now)) start=now;
            long seat=Store.id(booking,"seat_id");
            for(Window window:calendar.day(day,seat).windows()) {
                LocalDateTime a=day.atStartOfDay().plusMinutes(window.startMinute()),b=day.atStartOfDay().plusMinutes(window.endMinute());if(a.isBefore(start)) a=start;if(b.isAfter(end)) b=end;
                if(a.isBefore(b)) result.add(new ProtectedUse(seat,day,a,b));
            }
        }
        return result;
    }
    private void ensureProtected(long roomId,List<ProtectedUse> protectedUses) {
        var calendar=calendar(roomId);
        for(var use:protectedUses) Api.require(calendar.day(use.date(),use.seatId()).contains(use.date(),use.start(),use.end()),409,"变更影响有效预约的剩余开放时段，请先处理预约");
    }
    public void requireSlot(long roomId,Long seatId,Slot slot) { var result=calendar(roomId).day(slot.date(),seatId);Api.require(result.contains(slot),409,"所选时段不在资源合法开放窗口内"+(result.closedReasons().isEmpty()?"":"："+String.join("；",result.closedReasons()))); }
    private void version(long roomId,Long expected) { Api.require(expected!=null&&Store.id(db.one("SELECT version FROM rooms WHERE id=?",roomId),"version")==expected,409,"空间已变化，请刷新后重试"); }
    private void bump(long roomId) { db.update("UPDATE rooms SET version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",roomId); }
    private void requireAdmin(long actor) { var user=auth.profile(actor);Api.require("admin".equals(user.get("role"))&&"active".equals(user.get("status")),403,"需要管理员权限"); }
    private void validate(Object req) { var errors=validator.validate(req);Api.require(errors.isEmpty(),400,errors.stream().map(x->x.getPropertyPath()+": "+x.getMessage()).sorted().findFirst().orElse("参数错误")); }
    public static LocalDate date(String text) { try { return LocalDate.parse(text); } catch(Exception e) { throw Api.fail(400,"日期格式错误"); } }
    public static LocalDateTime local(String text) { try { var time=LocalDateTime.parse(text);Api.require(time.getSecond()==0&&time.getNano()==0,400,"停用起止须为校园时间的整分钟");return time; } catch(java.time.format.DateTimeParseException|NullPointerException e) { throw Api.fail(400,"停用时间须为Asia/Shanghai本地ISO日期时间"); } }
    public static List<Window> normalize(List<Requests.OpeningWindow> input) {
        Api.require(input!=null,400,"windows必填");List<Window> result=new ArrayList<>();
        for(var entry:input) { Api.require(entry!=null,400,"开放区间不能为空");int start=minute(entry.start(),false),end=minute(entry.end(),true);Api.require(start<end,400,"开放区间须同日且开始早于结束");result.add(new Window(start,end)); }
        result.sort(Comparator.comparingInt(Window::startMinute));List<Window> merged=new ArrayList<>();
        for(Window window:result) {
            if(!merged.isEmpty()) { Window previous=merged.get(merged.size()-1);Api.require(window.startMinute()>=previous.endMinute(),400,"开放区间不能重叠");if(window.startMinute()==previous.endMinute()) { merged.set(merged.size()-1,new Window(previous.startMinute(),window.endMinute()));continue; } }
            merged.add(window);
        }
        return List.copyOf(merged);
    }
    public static List<Window> subtract(List<Window> open,List<Window> closed) {
        List<Window> result=new ArrayList<>();
        for(Window window:open) {
            int cursor=window.startMinute();List<Window> cuts=closed.stream().filter(c->c.startMinute()<window.endMinute()&&c.endMinute()>window.startMinute()).sorted(Comparator.comparingInt(Window::startMinute)).toList();
            for(Window cut:cuts) { if(cut.startMinute()>cursor) result.add(new Window(cursor,Math.min(cut.startMinute(),window.endMinute())));cursor=Math.max(cursor,cut.endMinute());if(cursor>=window.endMinute()) break; }
            if(cursor<window.endMinute()) result.add(new Window(cursor,window.endMinute()));
        }
        return List.copyOf(result);
    }
    private static int minute(String text,boolean end) { Api.require(text!=null&&text.matches("\\d{2}:\\d{2}"),400,"开放时间须为HH:mm");if(end&&"24:00".equals(text)) return 1440;try { var value=LocalTime.parse(text);return value.getHour()*60+value.getMinute(); } catch(Exception e) { throw Api.fail(400,"开放时间无效，24:00仅可作结束"); } }
    private static String format(int minute) { return minute==1440?"24:00":String.format("%02d:%02d",minute/60,minute%60); }
    private static String nonblank(Object text,String fallback) { return text==null||text.toString().isBlank()?fallback:text.toString(); }
}
