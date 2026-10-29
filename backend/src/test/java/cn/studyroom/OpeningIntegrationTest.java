package cn.studyroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named="STUDYROOM_INTEGRATION",matches="true")
@SpringBootTest(properties={"spring.datasource.url=${STUDYROOM_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5433/studyroom_java_test}","studyroom.seed=false","studyroom.scheduler-enabled=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class OpeningIntegrationTest {
    @TestConfiguration static class TimeConfig { @Bean @Primary Clock testClock() { var zone=ZoneId.of("Asia/Shanghai");return Clock.fixed(LocalDate.now(zone).minusDays(1).atTime(9,0).atZone(zone).toInstant(),zone); } }
    @Autowired Store db;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    @Autowired OpeningScheduleService opening;
    @Autowired ResourceService resources;
    @Autowired BookingService bookings;
    @Autowired TransactionTemplate transactions;
    @Autowired Clock clock;
    long room,seat1,seat2,student,admin;
    String remote;
    static final List<String> TABLES=List.of("resource_unavailability","opening_windows","opening_schedules","audit_events","credit_logs","notifications","waitlist","reservations","seats","rooms","users");
    @BeforeEach void setup() {
        assertEquals("studyroom_java_test",db.one("SELECT current_database() AS name").get("name"));
        TABLES.forEach(t->db.update("DELETE FROM "+t));remote="s06-test-"+UUID.randomUUID();
        String hash=new BCryptPasswordEncoder().encode("S02-test-only-password");
        admin=db.insert("INSERT INTO users(username,password_hash,role) VALUES('s06_admin',?,'admin')",hash);
        student=db.insert("INSERT INTO users(username,password_hash,role) VALUES('s06_student',?,'student')",hash);
        room=db.insert("INSERT INTO rooms(name,seat_rows,seat_cols) VALUES('S06模拟空间',1,2)");opening.initializeDefaults(room);
        seat1=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'01',1,1)",room);
        seat2=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'02',1,2)",room);
    }
    @AfterEach void cleanupRate() { redis.delete("studyroom-java:rate:login:"+remote); }
    String d(int offset) { return LocalDate.now(clock).plusDays(offset).toString(); }
    String at(int offset,String time) { return d(offset)+"T"+time; }
    long version() { return Store.id(db.one("SELECT version FROM rooms WHERE id=?",room),"version"); }
    String token(String name) throws Exception {
        var result=http.perform(post("/api/auth/login").with(r->{r.setRemoteAddr(remote);return r;}).contentType("application/json").content(json.writeValueAsString(Map.of("username",name,"password","S02-test-only-password"))))
            .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }
    MockHttpServletRequestBuilder payload(MockHttpServletRequestBuilder request,String token,Object body) throws Exception { return request.header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(body)); }
    Requests.OpeningWindow w(String a,String b) { return new Requests.OpeningWindow(a,b); }
    void day(String date,List<Requests.OpeningWindow> windows) { opening.save(admin,room,new Requests.OpeningChange(null,date,windows,version(),"测试日期安排")); }
    long block(Long seat,String start,String end,String reason) {
        opening.saveBlock(admin,room,null,new Requests.Unavailability(seat,start,end,version(),reason));
        return db.count("SELECT max(id) FROM resource_unavailability");
    }
    List<OpeningScheduleService.Window> windows(String date,Long seat) { return opening.calendar(room).day(LocalDate.parse(date),seat).windows(); }
    @Test void dateOverrideClosedDayAndDeletionRestoreWeeklyWithVersionedHttp() throws Exception {
        String adminToken=token("s06_admin"),studentToken=token("s06_student");
        http.perform(payload(put("/api/admin/rooms/"+room+"/opening-windows"),studentToken,Map.of("date",d(1),"windows",List.of(),"version",1,"reason","越权"))).andExpect(status().isForbidden());
        http.perform(payload(put("/api/admin/rooms/"+room+"/opening-windows"),adminToken,Map.of("date",d(1),"windows",List.of(),"version",1,"reason","模拟闭馆"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.room_version").value(2));
        http.perform(get("/api/rooms/"+room+"/opening-windows").param("date",d(1)).header("Authorization","Bearer "+studentToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.windows").isEmpty()).andExpect(jsonPath("$.data.closed_reasons[0]").value("模拟闭馆"));
        http.perform(payload(delete("/api/admin/rooms/"+room+"/opening-windows"),adminToken,Map.of("date",d(1),"version",1,"reason","过期更新"))).andExpect(status().isConflict());
        http.perform(payload(delete("/api/admin/rooms/"+room+"/opening-windows"),adminToken,Map.of("date",d(1),"version",2,"reason","恢复星期安排"))).andExpect(status().isOk());
        assertEquals(List.of(new OpeningScheduleService.Window(480,1320)),windows(d(1),null));
        http.perform(payload(put("/api/admin/rooms/"+room+"/opening-windows"),adminToken,Map.of("weekday",1,"date",d(4),"windows",List.of(),"version",3,"reason","错误选择器"))).andExpect(status().isBadRequest());
    }
    @Test void weeklyChangeHonorsOverrideButDeletingLongOverrideCannotInvalidateBooking() {
        day(d(1),List.of(w("10:00","12:00")));
        long id=Store.id(bookings.create(student,new Requests.Reservation(seat1,d(1),"10:00","11:00")),"id");
        opening.save(admin,room,new Requests.OpeningChange(LocalDate.now(clock).plusDays(1).getDayOfWeek().getValue(),null,List.of(w("14:00","16:00")),version(),"星期安排被该日例外覆盖"));
        long version=version(),audit=db.count("SELECT count(*) FROM audit_events");
        assertEquals(409,assertThrows(Api.Failure.class,()->opening.deleteException(admin,room,new Requests.OpeningDelete(d(1),version,"删除例外将影响预约"))).status);
        assertEquals(version,version());assertEquals(audit,db.count("SELECT count(*) FROM audit_events"));assertEquals("pending",db.one("SELECT status FROM reservations WHERE id=?",id).get("status"));
    }
    @Test void overlappingBlocksUnionCrossMidnightClipAndIndependentCancellationKeepHistory() {
        long a=block(null,at(-1,"23:00"),at(0,"09:00"),"跨日空间维护");
        long b=block(seat1,at(0,"08:30"),at(0,"10:00"),"座位维护");
        assertEquals(List.of(new OpeningScheduleService.Window(600,1320)),windows(d(0),seat1));
        assertEquals(List.of(new OpeningScheduleService.Window(540,1320)),windows(d(0),seat2));
        assertEquals(List.of(new OpeningScheduleService.Window(480,1320)),windows(d(-1),seat1));
        opening.cancelBlock(admin,room,a,new Requests.ResourceDelete(version(),"解除空间维护"));
        assertEquals(List.of(new OpeningScheduleService.Window(480,510),new OpeningScheduleService.Window(600,1320)),windows(d(0),seat1));
        assertEquals(List.of(new OpeningScheduleService.Window(480,1320)),windows(d(0),seat2));
        opening.cancelBlock(admin,room,b,new Requests.ResourceDelete(version(),"解除座位维护"));
        assertEquals(2,db.count("SELECT count(*) FROM resource_unavailability WHERE cancelled"));
        block(null,at(0,"23:00"),at(1,"00:00"),"午夜结束");
        assertEquals(List.of(new OpeningScheduleService.Window(480,1320)),windows(d(1),seat1));
    }
    @Test void touchingBlocksDoNotAffectBookingAndIntersectionsRejectAtomically() {
        long reservation=Store.id(bookings.create(student,new Requests.Reservation(seat1,d(1),"10:00","11:00")),"id");
        block(seat1,at(1,"09:00"),at(1,"10:00"),"前段维护相接");
        block(seat1,at(1,"11:00"),at(1,"12:00"),"后段维护相接");
        long version=version(),audits=db.count("SELECT count(*) FROM audit_events");
        assertEquals(409,assertThrows(Api.Failure.class,()->block(seat1,at(1,"10:30"),at(1,"11:30"),"影响有效预约")).status);
        assertEquals(409,assertThrows(Api.Failure.class,()->day(d(1),List.of())).status);
        assertEquals(version,version());assertEquals(audits,db.count("SELECT count(*) FROM audit_events"));assertEquals(2,db.count("SELECT count(*) FROM resource_unavailability"));
        assertEquals("pending",db.one("SELECT status FROM reservations WHERE id=?",reservation).get("status"));assertEquals(0,db.count("SELECT count(*) FROM credit_logs"));
    }
    @Test void inUseProtectsOnlyRemainingTimeAndExpiredPendingDoesNotBlockChanges() {
        long id=db.insert("INSERT INTO reservations(user_id,seat_id,res_date,start_time,end_time,status,checkin_at) VALUES(?,?,?,'08:00','10:00','checked_in',?)",student,seat1,java.sql.Date.valueOf(d(0)),java.sql.Timestamp.valueOf(LocalDate.now(clock).atTime(8,0)));
        db.update("INSERT INTO reservations(user_id,seat_id,res_date,start_time,end_time,status) VALUES(?,?,?,'07:00','08:00','pending')",student,seat2,java.sql.Date.valueOf(d(0)));
        day(d(0),List.of(w("09:00","22:00"))); // 删除过去开放片段不终止正在使用。
        assertEquals(409,assertThrows(Api.Failure.class,()->day(d(0),List.of(w("09:30","22:00")))).status);
        assertEquals("checked_in",db.one("SELECT status FROM reservations WHERE id=?",id).get("status"));
        assertEquals(List.of(new OpeningScheduleService.Window(540,1320)),windows(d(0),seat1));
    }
    @Test void querySubmissionAndLegacyCandidateFlowsUseSameAuthoritativeWindows() throws Exception {
        day(d(1),List.of(w("08:00","12:00"),w("14:00","18:00")));
        block(seat1,at(1,"10:00"),at(1,"11:00"),"仅座位1临停");
        String studentToken=token("s06_student");
        http.perform(get("/api/rooms/"+room+"/seats").param("date",d(1)).param("start","10:00").param("end","11:00").header("Authorization","Bearer "+studentToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.seats[0].occupied").value(false)).andExpect(jsonPath("$.data.seats[0].bookable_for_slot").value(false)).andExpect(jsonPath("$.data.seats[1].bookable_for_slot").value(true));
        assertEquals(409,assertThrows(Api.Failure.class,()->bookings.create(student,new Requests.Reservation(seat1,d(1),"10:00","11:00"))).status);
        var allocated=bookings.allocate(student,new Requests.Allocation(room,d(1),"10:00","11:00",null,null,null,0,true));
        @SuppressWarnings("unchecked") var reservation=(Map<String,Object>)allocated.get("reservation");assertEquals(seat2,Store.id(reservation,"seat_id"));
        assertEquals(409,assertThrows(Api.Failure.class,()->bookings.join(student,new Requests.Wait(room,d(1),"12:00","13:00",null,null,null))).status);
        bookings.join(student,new Requests.Wait(room,d(1),"15:00","16:00",null,null,null));
        block(seat1,at(1,"15:00"),at(1,"16:00"),"座位1待处理");long waitingBlock=block(seat2,at(1,"15:00"),at(1,"16:00"),"座位2待处理");
        bookings.tick();assertEquals("waiting",db.one("SELECT status FROM waitlist WHERE user_id=?",student).get("status"));
        opening.cancelBlock(admin,room,waitingBlock,new Requests.ResourceDelete(version(),"座位2恢复"));
        bookings.tick();assertEquals("promoted",db.one("SELECT status FROM waitlist WHERE user_id=?",student).get("status"));
        assertEquals(1,db.count("SELECT count(*) FROM reservations WHERE seat_id=? AND start_time=TIME '15:00' AND source='waitlist'",seat2));
        var heat=http.perform(get("/api/stats/heatmap").param("room_id",""+room).param("date",d(1)).header("Authorization","Bearer "+token("s06_admin"))).andExpect(status().isOk()).andReturn();
        var hours=json.readTree(heat.getResponse().getContentAsString()).path("data").path("hours");assertFalse(hours.toString().contains("12:00"));assertTrue(hours.toString().contains("14:00"));
    }
    @Test void blockHttpEditCancelAndForeignSeatValidationPreserveIndependentHistory() throws Exception {
        String token=token("s06_admin");
        var created=http.perform(payload(post("/api/admin/rooms/"+room+"/unavailability"),token,Map.of("seat_id",seat1,"start_at",at(1,"09:00"),"end_at",at(1,"10:00"),"version",1,"reason","第一座位维护"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.room_version").value(2)).andReturn();
        long id=json.readTree(created.getResponse().getContentAsString()).path("data").path("items").get(0).path("id").asLong();
        http.perform(payload(put("/api/admin/rooms/"+room+"/unavailability/"+id),token,Map.of("seat_id",seat2,"start_at",at(1,"11:00"),"end_at",at(1,"12:00"),"version",2,"reason","改为第二座位维护"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].version").value(2));
        assertEquals(List.of(new OpeningScheduleService.Window(480,1320)),windows(d(1),seat1));
        assertEquals(List.of(new OpeningScheduleService.Window(480,660),new OpeningScheduleService.Window(720,1320)),windows(d(1),seat2));
        http.perform(payload(delete("/api/admin/rooms/"+room+"/unavailability/"+id),token,Map.of("version",3,"reason","维护完成解除"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].cancelled").value(true)).andExpect(jsonPath("$.data.items[0].reason").value("改为第二座位维护")).andExpect(jsonPath("$.data.items[0].cancel_reason").value("维护完成解除"));
        http.perform(payload(put("/api/admin/rooms/"+room+"/unavailability/"+id),token,Map.of("start_at",at(1,"11:00"),"end_at",at(1,"12:00"),"version",4,"reason","已解除不可编辑"))).andExpect(status().isConflict());
        http.perform(payload(post("/api/admin/rooms/"+room+"/unavailability"),token,Map.of("seat_id",999999999,"start_at",at(1,"11:00"),"end_at",at(1,"12:00"),"version",4,"reason","不存在的座位"))).andExpect(status().isNotFound());
        http.perform(payload(post("/api/admin/rooms/"+room+"/unavailability"),token,Map.of("start_at",at(1,"11:00")+"Z","end_at",at(1,"12:00"),"version",4,"reason","拒绝不明确时区转换"))).andExpect(status().isBadRequest());
        assertEquals(1,db.count("SELECT count(*) FROM resource_unavailability"));assertEquals(4,version());
    }
    @Test void auditFailureRollsBackScheduleAndBlockAndResourceRejectsOldTimeFields() throws Exception {
        String adminToken=token("s06_admin");
        var oldRoom=Map.of("name","S06模拟空间","seat_rows",1,"seat_cols",2,"enabled",true,"version",1,"reason","旧字段不得继续写","open_time","09:00","close_time","18:00");
        http.perform(payload(put("/api/admin/rooms/"+room),adminToken,oldRoom)).andExpect(status().isBadRequest());
        db.update("ALTER TABLE audit_events ADD CONSTRAINT s06_reject_audit CHECK (false) NOT VALID");
        try {
            http.perform(payload(put("/api/admin/rooms/"+room+"/opening-windows"),adminToken,Map.of("date",d(1),"windows",List.of(),"version",1,"reason","故障注入"))).andExpect(status().isInternalServerError());
            http.perform(payload(post("/api/admin/rooms/"+room+"/unavailability"),adminToken,Map.of("start_at",at(1,"10:00"),"end_at",at(1,"11:00"),"version",1,"reason","故障注入"))).andExpect(status().isInternalServerError());
            assertEquals(1,version());assertEquals(0,db.count("SELECT count(*) FROM resource_unavailability"));assertEquals(0,db.count("SELECT count(*) FROM opening_schedules WHERE schedule_date IS NOT NULL"));
        } finally { db.update("ALTER TABLE audit_events DROP CONSTRAINT s06_reject_audit"); }
    }
    @Test void newRoomAndRealSeedUseCanonicalDefaultsWithoutSequenceAssumptions() {
        var created=resources.save(admin,null,new Requests.Room("新默认空间",null,null,null,1,1,null,null,null,true,null,"默认安排测试"));
        long id=Store.id(created,"id");assertEquals(7,db.count("SELECT count(*) FROM opening_schedules WHERE room_id=?",id));
        assertEquals(List.of(new OpeningScheduleService.Window(480,1320)),opening.calendar(id).day(LocalDate.now(clock).plusDays(1),null).windows());
        TABLES.forEach(t->db.update("DELETE FROM "+t));
        transactions.executeWithoutResult(tx->new SeedData(db,clock,true,opening).run(null));
        assertEquals(3,db.count("SELECT count(*) FROM rooms"));assertEquals(21,db.count("SELECT count(*) FROM opening_schedules"));assertEquals(21,db.count("SELECT count(*) FROM opening_windows WHERE start_minute=480 AND end_minute=1320"));assertTrue(db.count("SELECT count(*) FROM reservations")>0);
    }
}
