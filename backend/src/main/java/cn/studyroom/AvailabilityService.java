package cn.studyroom;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 连续空档唯一差集核心；查询是同一时刻快照，最终预约仍重新校验。 */
@Service
@Transactional
public class AvailabilityService {
    private static final List<String> ROOM_FIELDS=List.of("id","name","building","floor","location","enabled","seat_rows","seat_cols","description","version","resource_reason","seat_count","capacity","bookable_capacity","campus_zone","campus_today","opening_date","opening_windows","closed_reasons");
    private static final List<String> SEAT_FIELDS=List.of("id","room_id","seat_no","row_no","col_no","zone","has_power","near_window","status","version","resource_reason");
    private static final Comparator<Map<String,Object>> EARLIEST=Comparator.comparing((Map<String,Object> item)->Store.text(item,"available_start"))
        .thenComparing(item->Store.text(item,"building")).thenComparing(item->Store.text(item,"floor")).thenComparing(item->Store.text(item,"location")).thenComparing(item->Store.text(item,"room_name"))
        .thenComparing(item->Store.text(item,"seat_no")).thenComparingLong(item->Store.id(item,"seat_id")).thenComparing(item->Store.text(item,"available_end"));
    private record Interval(LocalDateTime start,LocalDateTime end) {}
    private final Store db;
    private final AuthService auth;
    private final Clock clock;
    private final BookingPolicy policy;
    private final BookingService bookings;
    private final OpeningScheduleService opening;
    public AvailabilityService(Store db,AuthService auth,Clock clock,BookingPolicy policy,BookingService bookings,OpeningScheduleService opening) { this.db=db;this.auth=auth;this.clock=clock;this.policy=policy;this.bookings=bookings;this.opening=opening; }
    public Map<String,Object> find(long uid,long roomId,String date,String strategy) { return find(uid,roomId,date,strategy,null); }
    public Map<String,Object> find(long uid,long roomId,String date,String strategy,String requiredMinutes) {
        db.lockBookings();var user=auth.profile(uid);Api.require(AuthService.isOrdinary(user.get("role"))&&"active".equals(user.get("status")),403,"仅学生和教职工可查询本人找座方案");
        Api.require(roomId>0,400,"空间编号无效");Api.require(strategy!=null&&Set.of("earliest","longest","duration").contains(strategy),400,"找座方案须为 earliest、longest 或 duration");
        Integer required=parseRequired(strategy,requiredMinutes);int suggested=required==null?Slot.MIN_DURATION_MINUTES:required;
        Comparator<Map<String,Object>> order=strategy.equals("longest")?Comparator.<Map<String,Object>>comparingLong(item->((Number)item.get("duration_minutes")).longValue()).reversed().thenComparing(EARLIEST):EARLIEST;
        LocalDate day;try { day=LocalDate.parse(date); } catch(DateTimeException|NullPointerException e) { throw Api.fail(400,"校园日期格式错误"); }
        LocalDateTime snapshot=LocalDateTime.ofInstant(clock.instant(),BookingPolicy.CAMPUS_ZONE).truncatedTo(ChronoUnit.MICROS);policy.validateDate(day,snapshot);
        var calendar=opening.calendar(roomId,day);bookings.reconcileAvailability(uid,roomId,day,snapshot);
        // 一批读取目标空间座位预约及本人当天全部空间预约，后者不能被room过滤。
        var active=db.list("SELECT rv.seat_id,rv.user_id,rv.res_date,rv.start_time,rv.end_time FROM reservations rv JOIN seats s ON s.id=rv.seat_id WHERE rv.res_date=? AND rv.status IN('pending','checked_in') AND (s.room_id=? OR rv.user_id=?) ORDER BY rv.id",java.sql.Date.valueOf(day),roomId,uid);
        List<Interval> own=new ArrayList<>();Map<Long,List<Interval>> bySeat=new HashMap<>();
        for(var row:active) {
            var range=new Interval(Slot.endpoint(day,Store.text(row,"start_time")),Slot.endpoint(day,Store.text(row,"end_time")));
            bySeat.computeIfAbsent(Store.id(row,"seat_id"),unused->new ArrayList<>()).add(range);if(Store.id(row,"user_id")==uid) own.add(range);
        }
        var publicRoom=new LinkedHashMap<>(calendar.room);ResourceService.decoratePublicRoom(publicRoom,calendar,day,snapshot.toLocalDate());publicRoom=copy(publicRoom,ROOM_FIELDS);
        List<Map<String,Object>> seats=new ArrayList<>(),results=new ArrayList<>();
        for(var seat:calendar.seats) {
            long seatId=Store.id(seat,"id");var legal=calendar.day(day,seatId);var blocks=new ArrayList<>(own);blocks.addAll(bySeat.getOrDefault(seatId,List.of()));var occupied=union(blocks);
            var windows=new ArrayList<Map<String,Object>>();
            for(var window:legal.windows()) {
                var start=day.atStartOfDay().plusMinutes(window.startMinute());var end=day.atStartOfDay().plusMinutes(window.endMinute());
                for(var gap:subtract(new Interval(start,end),occupied)) {
                    var left=ceilMinute(gap.start());if(day.equals(snapshot.toLocalDate())&&left.isBefore(ceilMinute(snapshot))) left=ceilMinute(snapshot);
                    var right=gap.end().truncatedTo(ChronoUnit.MINUTES);long duration=Duration.between(left,right).toMinutes();if(duration<suggested) continue;
                    var item=result(publicRoom,seat,day,left,right,snapshot,duration,suggested);windows.add(item);results.add(item);
                }
            }
            windows.sort(order);var safe=copy(seat,SEAT_FIELDS);safe.put("opening_windows",legal.view());safe.put("closed_reasons",legal.closedReasons());safe.put("available_windows",windows);seats.add(safe);
        }
        results.sort(order);
        var response=new LinkedHashMap<String,Object>();String now=snapshot.atZone(BookingPolicy.CAMPUS_ZONE).toOffsetDateTime().toString();
        response.put("room",publicRoom);response.put("date",day.toString());response.put("strategy",strategy);response.put("required_minutes",required);response.put("server_now",now);response.put("campus_today",snapshot.toLocalDate().toString());response.put("computed_at",now);response.put("campus_zone",BookingPolicy.CAMPUS_ZONE.getId());response.put("seats",seats);response.put("results",results);return response;
    }
    private Map<String,Object> result(Map<String,Object> room,Map<String,Object> seat,LocalDate day,LocalDateTime start,LocalDateTime end,LocalDateTime now,long duration,int suggested) {
        var item=new LinkedHashMap<String,Object>();item.put("seat_id",seat.get("id"));item.put("seat_no",seat.get("seat_no"));item.put("room_id",room.get("id"));item.put("room_name",room.get("name"));
        for(String field:List.of("building","floor","location")) item.put(field,room.get(field));item.put("row_no",seat.get("row_no"));item.put("col_no",seat.get("col_no"));
        item.put("available_start",Slot.format((int)Duration.between(day.atStartOfDay(),start).toMinutes()));item.put("available_end",Slot.format((int)Duration.between(day.atStartOfDay(),end).toMinutes()));item.put("duration_minutes",duration);item.put("suggested_start",item.get("available_start"));item.put("suggested_end",Slot.format((int)Duration.between(day.atStartOfDay(),start).toMinutes()+suggested));
        Duration waiting=Duration.between(now,start);if(waiting.isNegative()) throw new IllegalStateException("空档开始早于计算快照");item.put("wait_minutes",waiting.getSeconds()/60+(waiting.getSeconds()%60!=0||waiting.getNano()!=0?1:0));return item;
    }
    private Integer parseRequired(String strategy,String raw) {
        if(!strategy.equals("duration")) { Api.require(raw==null,400,"仅所需时长方案可提供 required_minutes");return null; }
        Api.require(raw!=null&&raw.matches("[0-9]+"),400,"required_minutes 须为至少30的十进制整数");
        try { int value=Integer.parseInt(raw);Api.require(value>=Slot.MIN_DURATION_MINUTES,400,"所需时长须至少30分钟");return value; }
        catch(NumberFormatException e) { throw Api.fail(400,"required_minutes 超出整数解析范围"); }
    }
    private List<Interval> union(List<Interval> ranges) {
        ranges.sort(Comparator.comparing(Interval::start).thenComparing(Interval::end));var result=new ArrayList<Interval>();
        for(var range:ranges) {
            if(result.isEmpty()||range.start().isAfter(result.get(result.size()-1).end())) result.add(range);
            else { var last=result.remove(result.size()-1);result.add(new Interval(last.start(),range.end().isAfter(last.end())?range.end():last.end())); }
        }
        return result;
    }
    private List<Interval> subtract(Interval window,List<Interval> occupied) {
        var result=new ArrayList<Interval>();var cursor=window.start();
        for(var range:occupied) {
            if(!range.end().isAfter(cursor)) continue;if(!range.start().isBefore(window.end())) break;
            if(range.start().isAfter(cursor)) result.add(new Interval(cursor,range.start().isBefore(window.end())?range.start():window.end()));
            if(range.end().isAfter(cursor)) cursor=range.end();if(!cursor.isBefore(window.end())) break;
        }
        if(cursor.isBefore(window.end())) result.add(new Interval(cursor,window.end()));return result;
    }
    private LocalDateTime ceilMinute(LocalDateTime time) { var floor=time.truncatedTo(ChronoUnit.MINUTES);return floor.equals(time)?floor:floor.plusMinutes(1); }
    private static LinkedHashMap<String,Object> copy(Map<String,Object> source,List<String> fields) { var result=new LinkedHashMap<String,Object>();for(String field:fields) result.put(field,source.get(field));return result; }
}
