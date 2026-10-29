package cn.studyroom;

import java.sql.Date;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.studyroom.Store.*;

/** 根据签到与结算时间聚合使用情况，不把未到场违约计入实际使用。 */
@Service
@Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class StatsService {
    private final Store db;
    private final Clock clock;
    private final OpeningScheduleService opening;
    public StatsService(Store db,Clock clock,OpeningScheduleService opening) { this.db=db;this.clock=clock;this.opening=opening; }
    private List<Map<String,Object>> used(LocalDate from,LocalDate until) {
        return db.list("SELECT rv.*,s.room_id,s.seat_no,r.name AS room_name FROM reservations rv JOIN seats s ON s.id=rv.seat_id JOIN rooms r ON r.id=s.room_id WHERE rv.res_date>=? AND rv.res_date<=? AND rv.status IN ('completed','checked_in','temp_leave') AND rv.checkin_at IS NOT NULL",Date.valueOf(from),Date.valueOf(until));
    }
    private LocalDateTime from(Map<String,Object> row) { return LocalDateTime.parse(text(row,"checkin_at")); }
    private LocalDateTime to(Map<String,Object> row) {
        LocalDateTime scheduled=Slot.endpoint(LocalDate.parse(text(row,"res_date")),text(row,"end_time"));
        LocalDateTime actual=row.get("checkout_at")==null?LocalDateTime.now(clock):LocalDateTime.parse(text(row,"checkout_at"));
        return actual.isBefore(scheduled)?actual:scheduled;
    }
    private double hours(Map<String,Object> row) { return Math.max(0,Duration.between(from(row),to(row)).toSeconds()/3600.0); }
    private List<OpeningScheduleService.Calendar> calendars() { return db.list("SELECT id FROM rooms ORDER BY id").stream().map(row->opening.calendar(id(row,"id"))).toList(); }
    private double capacity(LocalDate day,List<OpeningScheduleService.Calendar> calendars) {
        double minutes=0;
        for(var calendar:calendars) for(var seat:calendar.seats) for(var window:calendar.day(day,id(seat,"id")).windows()) minutes+=window.endMinute()-window.startMinute();
        return minutes/60.0;
    }
    public List<Map<String,Object>> trend(int days) {
        days=bound(days,1,90);LocalDate today=LocalDate.now(clock);var rows=used(today.minusDays(days),today.minusDays(1));
        var result=new ArrayList<Map<String,Object>>();var calendars=calendars();
        for(int d=days;d>0;d--) {
            double cap=capacity(today.minusDays(d),calendars);String date=today.minusDays(d).toString();var day=rows.stream().filter(x->date.equals(x.get("res_date"))).toList();
            result.add(Map.of("date",date,"count",day.size(),"utilization",cap==0?0:day.stream().mapToDouble(this::hours).sum()/cap));
        }
        return result;
    }
    public List<Map<String,Object>> peak(int days) {
        var rows=used(LocalDate.now(clock).minusDays(bound(days,1,90)),LocalDate.now(clock).minusDays(1));var result=new ArrayList<Map<String,Object>>();
        for(int h=0;h<24;h++) { final int hour=h;result.add(Map.of("hour",String.format("%02d:00",h),"count",rows.stream().filter(x->Slot.endpoint(LocalDate.parse(text(x,"res_date")),text(x,"start_time")).getHour()==hour).count())); }
        return result;
    }
    public List<Map<String,Object>> top(int days,int limit) {
        var rows=used(LocalDate.now(clock).minusDays(bound(days,1,90)),LocalDate.now(clock).minusDays(1));Map<Long,List<Map<String,Object>>> groups=new LinkedHashMap<>();
        for(var row:rows) groups.computeIfAbsent(id(row,"seat_id"),k->new ArrayList<>()).add(row);
        var result=new ArrayList<Map<String,Object>>();
        for(var group:groups.values()) result.add(Map.of("seat_no",group.get(0).get("seat_no"),"room_name",group.get(0).get("room_name"),"hours",group.stream().mapToDouble(this::hours).sum(),"count",group.size()));
        result.sort(Comparator.<Map<String,Object>>comparingDouble(x->((Number)x.get("hours")).doubleValue()).reversed());
        return result.subList(0,Math.min(bound(limit,1,50),result.size()));
    }
    public Map<String,Object> overview() {
        LocalDate today=LocalDate.now(clock);double cap=capacity(today,calendars()),hours=used(today,today).stream().mapToDouble(this::hours).sum();
        return Map.of("total_seats",db.count("SELECT count(*) FROM seats s JOIN rooms r ON r.id=s.room_id WHERE s.status='available' AND r.enabled"),
                "active_today",db.count("SELECT count(*) FROM reservations WHERE res_date=? AND status<>'cancelled'",Date.valueOf(today)),
                "in_use_now",db.count("SELECT count(*) FROM reservations WHERE res_date=? AND status IN ('checked_in','temp_leave')",Date.valueOf(today)),
                "today_utilization",cap==0?0:hours/cap,"trend_14",trend(14),"peak",peak(14),"top_seats",top(14,10));
    }
    public Map<String,Object> heatmap(long roomId,String date) {
        LocalDate day;try { day=date==null||date.isBlank()?LocalDate.now(clock):LocalDate.parse(date); }catch(DateTimeException e) { throw Api.fail(400,"日期格式错误"); }
        var calendar=opening.calendar(roomId);var room=new LinkedHashMap<>(calendar.room);room.remove("open_time");room.remove("close_time");room.put("opening_windows",calendar.day(day,null).view());room.put("campus_zone",OpeningScheduleService.ZONE);
        var seats=calendar.seats;var rows=used(day,day).stream().filter(x->id(x,"room_id")==roomId).toList();
        // 以正式窗口和座位临停边界切分，热力单元不会跨越闭馆段。
        var edges=new TreeSet<Integer>();
        for(var window:calendar.day(day,null).windows()) { edges.add(window.startMinute());edges.add(window.endMinute());for(int t=window.startMinute();t<window.endMinute();t+=60) edges.add(t); }
        for(var seat:seats) for(var window:calendar.day(day,id(seat,"id")).windows()) { edges.add(window.startMinute());edges.add(window.endMinute()); }
        var points=new ArrayList<>(edges);var times=new ArrayList<LocalDateTime>();var ends=new ArrayList<LocalDateTime>();
        for(int i=0;i+1<points.size();i++) {
            var left=day.atStartOfDay().plusMinutes(points.get(i));var right=day.atStartOfDay().plusMinutes(points.get(i+1));
            if(calendar.day(day,null).contains(day,left,right)) { times.add(left);ends.add(right); }
        }
        var cells=new ArrayList<List<Integer>>();double[] hourly=new double[times.size()];long[] eligible=new long[times.size()];
        for(var seat:seats) {
            var cell=new ArrayList<Integer>();var seatDay=calendar.day(day,id(seat,"id"));
            for(int h=0;h<times.size();h++) {
                LocalDateTime left=times.get(h),right=ends.get(h);boolean legal=seatDay.contains(day,left,right);
                boolean occupied=rows.stream().anyMatch(x->id(x,"seat_id")==id(seat,"id")&&from(x).isBefore(right)&&to(x).isAfter(left));
                cell.add(occupied?1:0);if(legal) { eligible[h]++;if(occupied) hourly[h]++; }
            }
            cells.add(cell);
        }
        for(int i=0;i<hourly.length;i++) hourly[i]=eligible[i]==0?0:hourly[i]/eligible[i];
        return Map.of("room",room,"seats",seats,"hours",times.stream().map(x->x.toLocalTime().toString()).toList(),"cells",cells,"hourly_usage",hourly);
    }
    private int bound(int n,int min,int max) { return Math.max(min,Math.min(n,max)); }
}
