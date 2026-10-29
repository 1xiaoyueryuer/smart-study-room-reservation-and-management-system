package cn.studyroom;

import java.sql.Date;
import java.sql.Time;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResourceService {
    private final Store db;
    public ResourceService(Store db) { this.db=db; }
    public List<Map<String,Object>> rooms() { return db.list("SELECT * FROM rooms ORDER BY id"); }
    public Map<String,Object> room(long id) { return db.one("SELECT * FROM rooms WHERE id=?",id); }
    public Map<String,Object> seatMap(long roomId,String date,String start,String end) {
        var room=room(roomId); Slot slot=Slot.parse(date,start,end);
        var seats=db.list("SELECT s.*,EXISTS(SELECT 1 FROM reservations rv WHERE rv.seat_id=s.id AND rv.res_date=? AND rv.status IN ('pending','checked_in','temp_leave') AND rv.start_time<? AND rv.end_time>?) AS occupied FROM seats s WHERE room_id=? ORDER BY row_no,col_no",
                Date.valueOf(slot.date()),Time.valueOf(slot.end()),Time.valueOf(slot.start()),roomId);
        return Map.of("room",room,"seats",seats);
    }
    @Transactional public Map<String,Object> save(Long id,Requests.Room req) {
        db.lockBookings();
        LocalTime start=time(req.openTime()),end=time(req.closeTime());
        Api.require(start.isBefore(end),400,"关闭时间必须晚于开放时间");
        if(id==null) id=db.insert("INSERT INTO rooms(name,location,open_time,close_time,seat_rows,seat_cols,description) VALUES(?,?,?,?,?,?,?)",
                req.name().trim(),value(req.location()),Time.valueOf(start),Time.valueOf(end),req.seatRows(),req.seatCols(),value(req.description()));
        else {
            room(id);
            Api.require(db.count("SELECT count(*) FROM reservations rv JOIN seats s ON s.id=rv.seat_id WHERE s.room_id=? AND rv.status IN ('pending','checked_in','temp_leave') AND (rv.start_time<? OR rv.end_time>?)",id,Time.valueOf(start),Time.valueOf(end))==0,409,"开放时间变更影响有效预约，请先处理预约");
            Api.require(db.count("SELECT count(*) FROM seats WHERE room_id=? AND (row_no>? OR col_no>?)",id,req.seatRows(),req.seatCols())==0,409,"布局不能小于现有座位范围");
            db.update("UPDATE rooms SET name=?,location=?,open_time=?,close_time=?,seat_rows=?,seat_cols=?,description=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    req.name().trim(),value(req.location()),Time.valueOf(start),Time.valueOf(end),req.seatRows(),req.seatCols(),value(req.description()),id);
        }
        return room(id);
    }
    @Transactional public int batch(long id,Requests.Batch req) {
        db.lockBookings();room(id);
        Api.require(db.count("SELECT count(*) FROM seats WHERE room_id=?",id)==0,409,"已有座位，不能重复批量生成");
        db.update("UPDATE rooms SET seat_rows=?,seat_cols=? WHERE id=?",req.seatRows(),req.seatCols(),id);
        for(int row=1;row<=req.seatRows();row++) for(int col=1;col<=req.seatCols();col++)
            db.update("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,?,?,?)",id,String.format("%02d-%02d",row,col),row,col);
        return req.seatRows()*req.seatCols();
    }
    @Transactional public void updateSeat(long id,Requests.Seat req) {
        db.lockBookings();db.one("SELECT id FROM seats WHERE id=?",id);
        String status=req.status()==null?"available":req.status();
        if(!status.equals("available")) ensureNoActive("rv.seat_id=?",id);
        db.update("UPDATE seats SET zone=?,has_power=COALESCE(CAST(? AS BOOLEAN),has_power),near_window=COALESCE(CAST(? AS BOOLEAN),near_window),status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                req.zone(),req.hasPower(),req.nearWindow(),status,id);
    }
    @Transactional public void deleteSeat(long id) {
        db.lockBookings();db.one("SELECT id FROM seats WHERE id=?",id);
        Api.require(db.count("SELECT count(*) FROM reservations WHERE seat_id=?",id)==0,409,"座位存在预约历史，请改为停用");
        db.update("DELETE FROM seats WHERE id=?",id);
    }
    @Transactional public void deleteRoom(long id) {
        db.lockBookings();room(id);
        Api.require(db.count("SELECT count(*) FROM reservations rv JOIN seats s ON s.id=rv.seat_id WHERE s.room_id=?",id)==0&&db.count("SELECT count(*) FROM waitlist WHERE room_id=?",id)==0,409,"空间存在历史记录，不能删除");
        db.update("DELETE FROM seats WHERE room_id=?",id);db.update("DELETE FROM rooms WHERE id=?",id);
    }
    private void ensureNoActive(String condition,long id) {
        Api.require(db.count("SELECT count(*) FROM reservations rv WHERE "+condition+" AND rv.status IN ('pending','checked_in','temp_leave')",id)==0,409,"资源存在有效预约，请先处理预约");
    }
    static LocalTime time(String value) { try { return LocalTime.parse(value); }catch(Exception e) { throw Api.fail(400,"时间格式错误"); } }
    private String value(String s) { return s==null?"":s; }
}
