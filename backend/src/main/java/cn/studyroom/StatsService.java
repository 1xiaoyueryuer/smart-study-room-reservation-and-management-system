package cn.studyroom;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static cn.studyroom.Store.*;

/** One read-only aggregation of persisted reservation, Usage and policy facts. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class StatsService {
    private final Store db;
    private final Clock clock;
    private final OpeningScheduleService opening;
    private final AuthService auth;

    public StatsService(Store db,Clock clock,OpeningScheduleService opening,AuthService auth) {
        this.db=db;this.clock=clock;this.opening=opening;this.auth=auth;
    }

    private record Snapshot(Instant instant,LocalDateTime local,LocalDate selected,Long room) {
        LocalDate today(){return local.toLocalDate();}
        LocalDate trendFrom(){return today().minusDays(6);}
        String offset(){return instant.atZone(BookingPolicy.CAMPUS_ZONE).toOffsetDateTime().toString();}
    }
    private record Used(long seat,long room,String seatNo,String roomName,LocalDateTime start,
                        LocalDateTime end,LocalDateTime plannedEnd,boolean unfinished) {}

    public Map<String,Object> overview(long actor,Map<String,String> parameters) {
        Snapshot snapshot=snapshot(actor,parameters,false);
        var usage=used(snapshot);
        var metrics=new TreeMap<LocalDate,Map<String,Object>>();
        for(int i=0;i<7;i++)metrics.put(snapshot.trendFrom().plusDays(i),emptyDay());
        metrics.putIfAbsent(snapshot.selected(),emptyDay());
        counts(metrics,snapshot,"created_count","r.created_at","reservations r JOIN seats s ON s.id=r.seat_id","s.room_id","");
        counts(metrics,snapshot,"cancelled_count","r.ended_at","reservations r JOIN seats s ON s.id=r.seat_id","s.room_id"," AND r.status='cancelled'");
        counts(metrics,snapshot,"active_violation_count","v.occurred_at","violations v LEFT JOIN reservations r ON r.id=v.reservation_id LEFT JOIN seats s ON s.id=r.seat_id","s.room_id"," AND v.status='active'");
        for(var entry:metrics.entrySet()) {
            LocalDateTime left=entry.getKey().atStartOfDay(),right=left.plusDays(1);
            long checkins=0;BigDecimal duration=BigDecimal.ZERO;
            for(var row:usage) {
                if(!row.start().isBefore(left)&&row.start().isBefore(right))checkins++;
                duration=duration.add(overlap(row,left,right));
            }
            entry.getValue().put("checkin_count",checkins);
            entry.getValue().put("usage_seconds",duration);
        }
        var trend=new ArrayList<Map<String,Object>>();
        for(int i=0;i<7;i++) {
            LocalDate date=snapshot.trendFrom().plusDays(i);
            var item=new LinkedHashMap<>(metrics.get(date));item.put("date",date.toString());trend.add(item);
        }
        var scope=new LinkedHashMap<String,Object>();
        scope.put("room_id",snapshot.room());scope.put("selected_date",snapshot.selected().toString());
        scope.put("trend_from",snapshot.trendFrom().toString());scope.put("trend_to",snapshot.today().toString());
        return Map.of("snapshot_at",snapshot.offset(),"campus_zone",OpeningScheduleService.ZONE,
            "scope",scope,"day",metrics.get(snapshot.selected()),"current",current(snapshot,usage),
            "trend",trend,"peak",peak(snapshot,usage),"top_seats",top(snapshot,usage));
    }

    public Map<String,Object> heatmap(long actor,Map<String,String> parameters) {
        Snapshot snapshot=snapshot(actor,parameters,true);
        var room=db.one("SELECT id,name FROM rooms WHERE id=?",snapshot.room());
        var seats=db.list("SELECT id,seat_no,row_no,col_no FROM seats WHERE room_id=? ORDER BY row_no,col_no,id",snapshot.room());
        var usage=used(snapshot);
        var hours=new ArrayList<String>();var ends=new ArrayList<String>();
        for(int h=0;h<24;h++){hours.add(String.format("%02d:00",h));ends.add(String.format("%02d:00",h+1));}
        var cells=new ArrayList<List<Integer>>();
        for(var seat:seats) {
            var row=new ArrayList<Integer>();long seatId=id(seat,"id");
            for(int h=0;h<24;h++) {
                LocalDateTime left=snapshot.selected().atStartOfDay().plusHours(h),right=left.plusHours(1);
                row.add(usage.stream().anyMatch(value->value.seat()==seatId&&overlap(value,left,right).signum()>0)?1:0);
            }
            cells.add(row);
        }
        return Map.of("snapshot_at",snapshot.offset(),"campus_zone",OpeningScheduleService.ZONE,
            "date",snapshot.selected().toString(),"room",room,"seats",seats,"hours",hours,"ends",ends,"cells",cells);
    }

    private Snapshot snapshot(long actor,Map<String,String> parameters,boolean requireRoom) {
        var user=auth.profile(actor);
        Api.require("admin".equals(user.get("role"))&&"active".equals(user.get("status")),403,"仅启用管理员可查询统计");
        Api.require(Set.of("room_id","date").containsAll(parameters.keySet()),400,"统计查询包含不允许的参数");
        Instant instant=clock.instant().truncatedTo(ChronoUnit.MICROS);
        LocalDateTime local=LocalDateTime.ofInstant(instant,BookingPolicy.CAMPUS_ZONE);
        Long room=parameters.containsKey("room_id")?positive(parameters.get("room_id")):null;
        Api.require(!requireRoom||room!=null,400,"热力图必须选择空间");
        if(room!=null)db.one("SELECT id FROM rooms WHERE id=?",room);
        LocalDate selected=parameters.containsKey("date")?date(parameters.get("date")):local.toLocalDate();
        return new Snapshot(instant,local,selected,room);
    }

    private void counts(Map<LocalDate,Map<String,Object>> metrics,Snapshot snapshot,String key,
                        String time,String from,String roomColumn,String extra) {
        var args=periodArgs(snapshot);
        String sql="SELECT CAST("+time+" AS DATE) AS metric_date,count(*) AS amount FROM "+from+
            " WHERE "+period(time)+extra;
        if(snapshot.room()!=null){sql+=" AND "+roomColumn+"=?";args.add(snapshot.room());}
        sql+=" GROUP BY CAST("+time+" AS DATE)";
        for(var row:db.list(sql,args.toArray())) {
            var target=metrics.get(LocalDate.parse(text(row,"metric_date")));
            if(target!=null)target.put(key,id(row,"amount"));
        }
    }
    private String period(String time) {
        return "(("+time+">=CAST(? AS DATE)::timestamp AND "+time+"<(CAST(? AS DATE)+INTERVAL '1 day')) OR ("+
            time+">=CAST(? AS DATE)::timestamp AND "+time+"<(CAST(? AS DATE)+INTERVAL '1 day')))";
    }
    private ArrayList<Object> periodArgs(Snapshot snapshot) {
        return new ArrayList<>(List.of(snapshot.trendFrom().toString(),snapshot.today().toString(),
            snapshot.selected().toString(),snapshot.selected().toString()));
    }

    private List<Used> used(Snapshot snapshot) {
        // Select by actual Usage intervals, including check-ins before reservation midnight.
        String sql="SELECT u.checkin_at,u.ended_at,r.res_date,r.end_time,s.id AS seat_id,s.room_id,s.seat_no,rm.name AS room_name "+
            "FROM usage_records u JOIN reservations r ON r.id=u.reservation_id JOIN seats s ON s.id=r.seat_id JOIN rooms rm ON rm.id=s.room_id "+
            "WHERE ((u.checkin_at<(CAST(? AS DATE)+INTERVAL '1 day') AND (u.ended_at IS NULL OR u.ended_at>=CAST(? AS DATE)::timestamp OR u.ended_at<u.checkin_at)) "+
            "OR (u.checkin_at<(CAST(? AS DATE)+INTERVAL '1 day') AND (u.ended_at IS NULL OR u.ended_at>=CAST(? AS DATE)::timestamp OR u.ended_at<u.checkin_at)))";
        var args=new ArrayList<Object>(List.of(snapshot.today().toString(),snapshot.trendFrom().toString(),
            snapshot.selected().toString(),snapshot.selected().toString()));
        if(snapshot.room()!=null){sql+=" AND s.room_id=?";args.add(snapshot.room());}
        var result=new ArrayList<Used>();
        for(var row:db.list(sql,args.toArray())) {
            LocalDateTime start=LocalDateTime.parse(text(row,"checkin_at"));
            LocalDateTime planned=Slot.endpoint(LocalDate.parse(text(row,"res_date")),text(row,"end_time"));
            boolean unfinished=row.get("ended_at")==null;
            LocalDateTime actual=unfinished?snapshot.local():LocalDateTime.parse(text(row,"ended_at"));
            LocalDateTime end=actual.isBefore(planned)?actual:planned;
            if(end.isBefore(start))throw new IllegalStateException("使用统计存在反向时长，请核查使用事实");
            result.add(new Used(id(row,"seat_id"),id(row,"room_id"),text(row,"seat_no"),text(row,"room_name"),start,end,planned,unfinished));
        }
        return result;
    }

    private Map<String,Object> current(Snapshot snapshot,List<Used> usage) {
        var rooms=snapshot.room()==null?db.list("SELECT id FROM rooms ORDER BY id"):List.of(Map.<String,Object>of("id",snapshot.room()));
        var eligible=new HashSet<Long>();
        for(var room:rooms) {
            var calendar=opening.calendar(id(room,"id"));
            for(var seat:calendar.seats) {
                long seatId=id(seat,"id");
                boolean open=calendar.day(snapshot.today(),seatId).windows().stream().anyMatch(window->{
                    var left=snapshot.today().atStartOfDay().plusMinutes(window.startMinute());
                    var right=snapshot.today().atStartOfDay().plusMinutes(window.endMinute());
                    return !snapshot.local().isBefore(left)&&snapshot.local().isBefore(right);
                });
                if(open)eligible.add(seatId);
            }
        }
        long inUse=usage.stream().filter(row->eligible.contains(row.seat())&&row.unfinished()&&
            !row.start().isAfter(snapshot.local())&&row.plannedEnd().isAfter(snapshot.local())).map(Used::seat).distinct().count();
        var args=new ArrayList<Object>();String roomFilter="";
        if(snapshot.room()!=null){roomFilter=" AND room_id=?";args.add(snapshot.room());}
        long todo=db.count("SELECT count(*) FROM complaints WHERE status IN('submitted','processing')"+roomFilter,args.toArray());
        long awaiting=db.count("SELECT count(*) FROM complaints WHERE status='awaiting_confirmation'"+roomFilter,args.toArray());
        var result=new LinkedHashMap<String,Object>();result.put("open_available_seats",eligible.size());result.put("in_use_now",inUse);
        result.put("usage_ratio",eligible.isEmpty()?null:(double)inUse/eligible.size());result.put("complaint_todo_count",todo);result.put("complaint_awaiting_count",awaiting);
        return result;
    }

    private List<Map<String,Object>> peak(Snapshot snapshot,List<Used> usage) {
        var result=new ArrayList<Map<String,Object>>();
        for(int h=0;h<24;h++) {
            BigDecimal duration=BigDecimal.ZERO;long checkins=0;
            for(int d=0;d<7;d++) {
                var left=snapshot.trendFrom().plusDays(d).atStartOfDay().plusHours(h);var right=left.plusHours(1);
                for(var row:usage){duration=duration.add(overlap(row,left,right));if(!row.start().isBefore(left)&&row.start().isBefore(right))checkins++;}
            }
            result.add(Map.of("hour",String.format("%02d:00",h),"usage_seconds",duration,"checkin_count",checkins));
        }
        return result;
    }
    private List<Map<String,Object>> top(Snapshot snapshot,List<Used> usage) {
        var groups=new HashMap<Long,Map<String,Object>>();
        var left=snapshot.trendFrom().atStartOfDay();var right=snapshot.today().plusDays(1).atStartOfDay();
        for(var row:usage) {
            BigDecimal duration=overlap(row,left,right);long checkin=!row.start().isBefore(left)&&row.start().isBefore(right)?1:0;
            if(duration.signum()==0&&checkin==0)continue;
            var item=groups.computeIfAbsent(row.seat(),ignored->{var value=new LinkedHashMap<String,Object>();
                value.put("seat_id",row.seat());value.put("room_id",row.room());value.put("room_name",row.roomName());value.put("seat_no",row.seatNo());
                value.put("usage_seconds",BigDecimal.ZERO);value.put("checkin_count",0L);return value;});
            item.put("usage_seconds",((BigDecimal)item.get("usage_seconds")).add(duration));item.put("checkin_count",id(item,"checkin_count")+checkin);
        }
        return groups.values().stream().sorted(Comparator.<Map<String,Object>,BigDecimal>comparing(row->(BigDecimal)row.get("usage_seconds")).reversed()
            .thenComparingLong(row->id(row,"room_id")).thenComparing(row->text(row,"seat_no")).thenComparingLong(row->id(row,"seat_id"))).limit(10).toList();
    }
    private BigDecimal overlap(Used row,LocalDateTime left,LocalDateTime right) {
        LocalDateTime start=row.start().isAfter(left)?row.start():left,end=row.end().isBefore(right)?row.end():right;
        if(!end.isAfter(start))return BigDecimal.ZERO;
        Duration duration=Duration.between(start,end);
        return BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano(),9));
    }
    private Map<String,Object> emptyDay(){var result=new LinkedHashMap<String,Object>();for(String key:List.of("created_count","cancelled_count","checkin_count","active_violation_count"))result.put(key,0L);result.put("usage_seconds",BigDecimal.ZERO);return result;}
    private long positive(String value){try{Api.require(value!=null&&value.matches("[0-9]+"),400,"空间编号须为正整数");long id=Long.parseLong(value);Api.require(id>0,400,"空间编号须为正整数");return id;}catch(NumberFormatException e){throw Api.fail(400,"空间编号超出范围");}}
    private LocalDate date(String value){try{Api.require(value!=null&&value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"),400,"日期须为正四位年ISO自然日");LocalDate date=LocalDate.parse(value);Api.require(date.getYear()>0,400,"日期年份须为0001至9999");return date;}catch(java.time.format.DateTimeParseException e){throw Api.fail(400,"日期无效");}}
}
