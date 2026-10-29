package cn.studyroom;

import java.sql.Date;
import java.sql.Time;
import java.time.*;
import java.util.*;
import javax.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResourceService {
    private static final String ACTIVE="('pending','checked_in','temp_leave')";
    private static final String ROOMS="SELECT r.*,(SELECT count(*) FROM seats s WHERE s.room_id=r.id) AS seat_count,(SELECT count(*) FROM seats s WHERE s.room_id=r.id AND s.status='available') AS capacity,CASE WHEN r.enabled THEN (SELECT count(*) FROM seats s WHERE s.room_id=r.id AND s.status='available') ELSE 0 END AS bookable_capacity FROM rooms r";
    private static final List<String> ROOM_FIELDS=List.of("name","building","floor","location","enabled","open_time","close_time","seat_rows","seat_cols","description","version","resource_reason");
    private static final List<String> SEAT_FIELDS=List.of("room_id","seat_no","row_no","col_no","zone","has_power","near_window","status","version","resource_reason");
    private final Store db;
    private final AuthService auth;
    private final AuditService audit;
    private final Validator validator;
    public ResourceService(Store db,AuthService auth,AuditService audit,Validator validator) { this.db=db;this.auth=auth;this.audit=audit;this.validator=validator; }
    public List<Map<String,Object>> rooms() { return db.list(ROOMS+" ORDER BY r.id"); }
    public Map<String,Object> room(long id) { return db.one(ROOMS+" WHERE r.id=?",id); }
    public Map<String,Object> managementSeats(long roomId) { return Map.of("room",room(roomId),"seats",db.list("SELECT * FROM seats WHERE room_id=? ORDER BY row_no,col_no",roomId)); }
    public Map<String,Object> seatMap(long roomId,String date,String start,String end) {
        var room=room(roomId); Slot slot=Slot.parse(date,start,end);
        var seats=db.list("SELECT s.*,EXISTS(SELECT 1 FROM reservations rv WHERE rv.seat_id=s.id AND rv.res_date=? AND rv.status IN "+ACTIVE+" AND rv.start_time<? AND rv.end_time>?) AS occupied FROM seats s WHERE room_id=? ORDER BY row_no,col_no",
                Date.valueOf(slot.date()),Time.valueOf(slot.end()),Time.valueOf(slot.start()),roomId);
        return Map.of("room",room,"seats",seats);
    }
    @Transactional public Map<String,Object> save(long actor,Long id,Requests.Room req) {
        db.lockBookings();requireAdmin(actor);validate(req);
        LocalTime start=time(req.openTime()),end=time(req.closeTime());
        Api.require(start.isBefore(end),400,"关闭时间必须晚于开放时间");
        Map<String,Object> before=Map.of();
        if(id==null) {
            id=db.insert("INSERT INTO rooms(name,location,open_time,close_time,seat_rows,seat_cols,description,building,floor,enabled,resource_reason) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                req.name().trim(),value(req.location()),Time.valueOf(start),Time.valueOf(end),req.seatRows(),req.seatCols(),value(req.description()),value(req.building()),value(req.floor()),req.enabled(),req.reason().trim());
        } else {
            before=room(id);version(before,req.version());
            boolean moved=!same(before,"building",req.building())||!same(before,"floor",req.floor())||!same(before,"location",req.location());
            if(!req.enabled()||moved) ensureNoActive("rv.seat_id IN (SELECT id FROM seats WHERE room_id=?)",id);
            Api.require(db.count("SELECT count(*) FROM reservations rv JOIN seats s ON s.id=rv.seat_id WHERE s.room_id=? AND rv.status IN "+ACTIVE+" AND (rv.start_time<? OR rv.end_time>?)",id,Time.valueOf(start),Time.valueOf(end))==0,409,"开放时间变更影响有效预约，请先处理预约");
            Api.require(db.count("SELECT count(*) FROM seats WHERE room_id=? AND (row_no>? OR col_no>?)",id,req.seatRows(),req.seatCols())==0,409,"布局不能小于现有座位范围");
            db.update("UPDATE rooms SET name=?,location=?,open_time=?,close_time=?,seat_rows=?,seat_cols=?,description=?,building=?,floor=?,enabled=?,resource_reason=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                req.name().trim(),value(req.location()),Time.valueOf(start),Time.valueOf(end),req.seatRows(),req.seatCols(),value(req.description()),value(req.building()),value(req.floor()),req.enabled(),req.reason().trim(),id);
        }
        var after=room(id);record(actor,before.isEmpty()?"room.create":"room.update","room",id,req.reason(),before,after,ROOM_FIELDS);return after;
    }
    @Transactional public int batch(long actor,long id,Requests.Batch req) {
        db.lockBookings();requireAdmin(actor);validate(req);var before=room(id);version(before,req.version());
        Api.require(db.count("SELECT count(*) FROM seats WHERE room_id=?",id)==0,409,"已有座位，不能重复批量生成");
        db.update("UPDATE rooms SET seat_rows=?,seat_cols=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",req.seatRows(),req.seatCols(),id);
        for(int row=1;row<=req.seatRows();row++) for(int col=1;col<=req.seatCols();col++)
            db.update("INSERT INTO seats(room_id,seat_no,row_no,col_no,resource_reason) VALUES(?,?,?,?,?)",id,String.format("%02d-%02d",row,col),row,col,req.reason().trim());
        record(actor,"room.seats.batch","room",id,req.reason(),before,room(id),ROOM_FIELDS);return req.seatRows()*req.seatCols();
    }
    @Transactional public Map<String,Object> createSeat(long actor,long roomId,Requests.Seat req) {
        db.lockBookings();requireAdmin(actor);validate(req);var room=room(roomId);version(room,req.version());bounds(room,req);
        long id=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no,zone,has_power,near_window,status,resource_reason) VALUES(?,?,?,?,?,?,?,?,?)",roomId,req.seatNo().trim(),req.rowNo(),req.colNo(),req.zone(),req.hasPower(),req.nearWindow(),req.status(),req.reason().trim());
        bumpRoom(roomId);var after=seat(id);record(actor,"seat.create","seat",id,req.reason(),Map.of(),after,SEAT_FIELDS);return after;
    }
    @Transactional public Map<String,Object> updateSeat(long actor,long id,Requests.Seat req) {
        db.lockBookings();requireAdmin(actor);validate(req);var before=seat(id);version(before,req.version());
        long roomId=Store.id(before,"room_id");bounds(room(roomId),req);
        boolean moved=!same(before,"seat_no",req.seatNo())||((Number)before.get("row_no")).intValue()!=req.rowNo()||((Number)before.get("col_no")).intValue()!=req.colNo();
        if(!req.status().equals("available")||moved) ensureNoActive("rv.seat_id=?",id);
        db.update("UPDATE seats SET seat_no=?,row_no=?,col_no=?,zone=?,has_power=?,near_window=?,status=?,resource_reason=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
            req.seatNo().trim(),req.rowNo(),req.colNo(),req.zone(),req.hasPower(),req.nearWindow(),req.status(),req.reason().trim(),id);
        bumpRoom(roomId);var after=seat(id);record(actor,"seat.update","seat",id,req.reason(),before,after,SEAT_FIELDS);return after;
    }
    @Transactional public void deleteSeat(long actor,long id,Requests.ResourceDelete req) {
        db.lockBookings();requireAdmin(actor);validate(req);var before=seat(id);version(before,req.version());
        Api.require(db.count("SELECT count(*) FROM reservations WHERE seat_id=?",id)==0,409,"座位存在预约历史，请改为停用");
        db.update("DELETE FROM seats WHERE id=?",id);bumpRoom(Store.id(before,"room_id"));record(actor,"seat.delete","seat",id,req.reason(),before,Map.of(),SEAT_FIELDS);
    }
    @Transactional public void deleteRoom(long actor,long id,Requests.ResourceDelete req) {
        db.lockBookings();requireAdmin(actor);validate(req);var before=room(id);version(before,req.version());
        Api.require(db.count("SELECT count(*) FROM reservations rv JOIN seats s ON s.id=rv.seat_id WHERE s.room_id=?",id)==0&&db.count("SELECT count(*) FROM waitlist WHERE room_id=?",id)==0,409,"空间存在历史记录，不能删除");
        db.update("DELETE FROM seats WHERE room_id=?",id);db.update("DELETE FROM rooms WHERE id=?",id);record(actor,"room.delete","room",id,req.reason(),before,Map.of(),ROOM_FIELDS);
    }
    private Map<String,Object> seat(long id) { return db.one("SELECT * FROM seats WHERE id=?",id); }
    private void bumpRoom(long id) { db.update("UPDATE rooms SET version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",id); }
    private void bounds(Map<String,Object> room,Requests.Seat req) { Api.require(req.rowNo()<=((Number)room.get("seat_rows")).intValue()&&req.colNo()<=((Number)room.get("seat_cols")).intValue(),400,"座位行列超出空间布局范围"); }
    private void ensureNoActive(String condition,long id) { Api.require(db.count("SELECT count(*) FROM reservations rv WHERE "+condition+" AND rv.status IN "+ACTIVE,id)==0,409,"资源变更影响有效预约，请先处理预约"); }
    private void version(Map<String,Object> before,Long expected) { Api.require(expected!=null,400,"资源版本必填");Api.require(Store.id(before,"version")==expected,409,"资源已变化，请刷新后重试"); }
    private void requireAdmin(long actor) { var user=auth.profile(actor);Api.require("admin".equals(user.get("role"))&&"active".equals(user.get("status")),403,"需要管理员权限"); }
    private void validate(Object req) { var errors=validator.validate(req);Api.require(errors.isEmpty(),400,errors.stream().map(x->x.getPropertyPath()+": "+x.getMessage()).sorted().findFirst().orElse("参数错误")); }
    private void record(long actor,String action,String type,long id,String reason,Map<String,Object> before,Map<String,Object> after,List<String> fields) { audit.record(actor,action,type,Long.toString(id),reason.trim(),snapshot(before,fields),snapshot(after,fields)); }
    private Map<String,Object> snapshot(Map<String,Object> source,List<String> fields) { var result=new LinkedHashMap<String,Object>();for(String field:fields) if(source.containsKey(field)) result.put(field,source.get(field));return result; }
    static LocalTime time(String value) { try { return LocalTime.parse(value); }catch(Exception e) { throw Api.fail(400,"时间格式错误"); } }
    private boolean same(Map<String,Object> source,String key,String proposed) { return Objects.equals(value(Objects.toString(source.get(key),null)),value(proposed)); }
    private String value(String s) { return AuthService.blankNull(s); }
}
