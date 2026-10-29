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
    public StatsService(Store db,Clock clock) { this.db=db;this.clock=clock; }
    private List<Map<String,Object>> used(LocalDate from,LocalDate until) {
        return db.list("SELECT rv.*,s.room_id,s.seat_no,r.name AS room_name FROM reservations rv JOIN seats s ON s.id=rv.seat_id JOIN rooms r ON r.id=s.room_id WHERE rv.res_date>=? AND rv.res_date<=? AND rv.status IN ('completed','checked_in','temp_leave') AND rv.checkin_at IS NOT NULL",Date.valueOf(from),Date.valueOf(until));
    }
    private LocalDateTime from(Map<String,Object> row) { return LocalDateTime.parse(text(row,"checkin_at")); }
    private LocalDateTime to(Map<String,Object> row) {
        LocalDateTime scheduled=LocalDate.parse(text(row,"res_date")).atTime(LocalTime.parse(text(row,"end_time")));
        LocalDateTime actual=row.get("checkout_at")==null?LocalDateTime.now(clock):LocalDateTime.parse(text(row,"checkout_at"));
        return actual.isBefore(scheduled)?actual:scheduled;
    }
    private double hours(Map<String,Object> row) { return Math.max(0,Duration.between(from(row),to(row)).toSeconds()/3600.0); }
    private double capacity() {
        double n=0;
        for(var room:db.list("SELECT r.*, (SELECT count(*) FROM seats s WHERE s.room_id=r.id AND s.status='available') AS seats FROM rooms r WHERE r.enabled"))
            n+=Duration.between(LocalTime.parse(text(room,"open_time")),LocalTime.parse(text(room,"close_time"))).toMinutes()/60.0*id(room,"seats");
        return n;
    }
    public List<Map<String,Object>> trend(int days) {
        days=bound(days,1,90);LocalDate today=LocalDate.now(clock);var rows=used(today.minusDays(days),today.minusDays(1));
        var result=new ArrayList<Map<String,Object>>();double cap=capacity();
        for(int d=days;d>0;d--) {
            String date=today.minusDays(d).toString();var day=rows.stream().filter(x->date.equals(x.get("res_date"))).toList();
            result.add(Map.of("date",date,"count",day.size(),"utilization",cap==0?0:day.stream().mapToDouble(this::hours).sum()/cap));
        }
        return result;
    }
    public List<Map<String,Object>> peak(int days) {
        var rows=used(LocalDate.now(clock).minusDays(bound(days,1,90)),LocalDate.now(clock).minusDays(1));var result=new ArrayList<Map<String,Object>>();
        for(int h=7;h<=22;h++) { final int hour=h;result.add(Map.of("hour",String.format("%02d:00",h),"count",rows.stream().filter(x->LocalTime.parse(text(x,"start_time")).getHour()==hour).count())); }
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
        LocalDate today=LocalDate.now(clock);double cap=capacity(),hours=used(today,today).stream().mapToDouble(this::hours).sum();
        return Map.of("total_seats",db.count("SELECT count(*) FROM seats s JOIN rooms r ON r.id=s.room_id WHERE s.status='available' AND r.enabled"),
                "active_today",db.count("SELECT count(*) FROM reservations WHERE res_date=? AND status<>'cancelled'",Date.valueOf(today)),
                "in_use_now",db.count("SELECT count(*) FROM reservations WHERE res_date=? AND status IN ('checked_in','temp_leave')",Date.valueOf(today)),
                "today_utilization",cap==0?0:hours/cap,"trend_14",trend(14),"peak",peak(14),"top_seats",top(14,10));
    }
    public Map<String,Object> heatmap(long roomId,String date) {
        LocalDate day;try { day=date==null||date.isBlank()?LocalDate.now(clock):LocalDate.parse(date); }catch(DateTimeException e) { throw Api.fail(400,"日期格式错误"); }
        var room=db.one("SELECT * FROM rooms WHERE id=?",roomId);var seats=db.list("SELECT * FROM seats WHERE room_id=? ORDER BY row_no,col_no",roomId);
        var rows=used(day,day).stream().filter(x->id(x,"room_id")==roomId).toList();
        LocalDateTime opening=day.atTime(LocalTime.parse(text(room,"open_time"))),closing=day.atTime(LocalTime.parse(text(room,"close_time")));
        var times=new ArrayList<LocalDateTime>();for(var t=opening;t.isBefore(closing);t=t.plusHours(1)) times.add(t);
        var cells=new ArrayList<List<Integer>>();double[] hourly=new double[times.size()];
        for(var seat:seats) {
            var cell=new ArrayList<Integer>();
            for(int h=0;h<times.size();h++) {
                LocalDateTime left=times.get(h),right=left.plusHours(1).isAfter(closing)?closing:left.plusHours(1);
                boolean occupied=rows.stream().anyMatch(x->id(x,"seat_id")==id(seat,"id")&&from(x).isBefore(right)&&to(x).isAfter(left));
                cell.add(occupied?1:0);if(occupied&&"available".equals(seat.get("status"))) hourly[h]++;
            }
            cells.add(cell);
        }
        long count=seats.stream().filter(x->"available".equals(x.get("status"))).count();for(int i=0;i<hourly.length;i++) hourly[i]=count==0?0:hourly[i]/count;
        return Map.of("room",room,"seats",seats,"hours",times.stream().map(x->x.toLocalTime().toString()).toList(),"cells",cells,"hourly_usage",hourly);
    }
    private int bound(int n,int min,int max) { return Math.max(min,Math.min(n,max)); }
}
