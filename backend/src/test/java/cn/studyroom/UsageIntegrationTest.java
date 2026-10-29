package cn.studyroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.io.ByteArrayInputStream;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named="STUDYROOM_INTEGRATION",matches="true")
@SpringBootTest(properties={"spring.datasource.url=${STUDYROOM_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5433/studyroom_java_test}","studyroom.seed=false","studyroom.scheduler-enabled=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class UsageIntegrationTest {
    @TestConfiguration static class TimeConfig { @Bean @Primary MinuteBookingIntegrationTest.TestClock testClock() { var c=new MinuteBookingIntegrationTest.TestClock();c.set(LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1).atTime(9,0));return c; } }
    @Autowired Store db;
    @Autowired BookingService bookings;
    @Autowired SeatCodeService codes;
    @Autowired OpeningScheduleService opening;
    @Autowired ResourceService resources;
    @Autowired UsageService usage;
    @Autowired StatsService stats;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired MinuteBookingIntegrationTest.TestClock clock;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    long admin,student,staff,room,seat1,seat2;
    LocalDate base;
    String remote;
    @BeforeEach void fixture() {
        assertEquals("studyroom_java_test",db.one("SELECT current_database() AS name").get("name"));
        for(String table:List.of("usage_records","seat_codes","violations","resource_unavailability","opening_windows","opening_schedules","audit_events","credit_logs","notifications","waitlist","reservations","seats","rooms","users")) db.update("DELETE FROM "+table);
        base=LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1);clock.set(base.atTime(9,0));remote="s10-test-"+UUID.randomUUID();
        String hash=new BCryptPasswordEncoder().encode("S02-test-only-password");
        admin=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s10_admin',?,'admin','S09-admin')",hash);
        student=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s10_student',?,'student','S09-student')",hash);
        staff=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s10_staff',?,'staff','S09-staff')",hash);
        room=db.insert("INSERT INTO rooms(name,seat_rows,seat_cols) VALUES('S10模拟空间',1,2)");opening.initializeDefaults(room);
        seat1=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'01',1,1)",room);seat2=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'02',1,2)",room);
    }
    @AfterEach void clearRate() { redis.delete("studyroom-java:rate:login:"+remote); }
    void at(int h,int m,int s) { clock.set(base.atTime(h,m,s)); }
    String token(String role) throws Exception {
        var result=http.perform(post("/api/auth/login").with(r->{r.setRemoteAddr(remote);return r;}).contentType("application/json").content(json.writeValueAsString(Map.of("username","s10_"+role,"password","S02-test-only-password")))).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }
    long reservation(long user,long seat) { return Store.id(bookings.create(user,new Requests.Reservation(seat,base.toString(),"10:00","11:00")),"id"); }
    String code(long seat) { codes.generate(admin,seat,new Requests.SeatCodeChange(0L,"测试实际生成座位码"));return current(seat); }
    String current(long seat) { return Store.text(db.one("SELECT code_value FROM seat_codes WHERE seat_id=?",seat),"code_value"); }
    String path(long seat) { return "/api/admin/seats/"+seat+"/code"; }
    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder req,String token) { return req.header("Authorization","Bearer "+token); }
    MockHttpServletRequestBuilder checkin(long id,String code,String token) throws Exception { return auth(post("/api/reservations/"+id+"/checkin"),token).contentType("application/json").content(json.writeValueAsString(Map.of("seat_code",code))); }
    Map<String,Object> row(long id) { return db.one("SELECT * FROM reservations WHERE id=?",id); }
    static String decode(byte[] png) throws Exception {
        assertArrayEquals(new byte[]{(byte)137,80,78,71,13,10,26,10},Arrays.copyOf(png,8));var image=ImageIO.read(new ByteArrayInputStream(png));
        int width=image.getWidth(),height=image.getHeight();var source=new RGBLuminanceSource(width,height,image.getRGB(0,0,width,height,null,0,width));
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
    }
    @Test void nanosecondClockCheckinAndFractionalCheckoutRoundTripAtDatabasePrecision() throws Exception {
        long id=reservation(student,seat1);String token=token("student"),payload=code(seat1);
        clock.set(base.atTime(10,0,0,123456789));
        http.perform(checkin(id,payload,token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.usage_record.duration_seconds").value(0));
        assertNull(row(id).get("checkin_at"));assertEquals(base.atTime(10,0,0,123456000),LocalDateTime.parse(db.one("SELECT checkin_at FROM usage_records WHERE reservation_id=?",id).get("checkin_at").toString()));
        clock.set(base.atTime(10,12,5,987654321));
        http.perform(auth(post("/api/reservations/"+id+"/checkout"),token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.usage_record.duration_seconds").value(725.864198)).andExpect(jsonPath("$.data.usage_record.end_reason").value("early_checkout"));
        var result=bookings.detail(student,id);assertNull(row(id).get("checkout_at"));assertEquals("completed",result.get("status"));
        @SuppressWarnings("unchecked") var record=(Map<String,Object>)result.get("usage_record");assertEquals(0,new java.math.BigDecimal("725.864198").compareTo((java.math.BigDecimal)record.get("duration_seconds")));assertEquals("seat_code",record.get("origin"));
        assertFalse(record.containsKey("legacy_checkout_at"));assertFalse(record.containsKey("legacy_termination_reason"));assertEquals(0,db.count("SELECT count(*) FROM credit_logs"));assertEquals(0,db.count("SELECT count(*) FROM violations"));
        assertNotNull(bookings.create(staff,new Requests.Reservation(seat1,base.toString(),"10:13","10:43")));
        assertEquals(409,assertThrows(Api.Failure.class,()->bookings.action(student,id,"checkout")).status);assertEquals(1,db.count("SELECT count(*) FROM usage_records"));assertEquals(1,db.count("SELECT count(*) FROM notifications WHERE type='reservation_completed'"));
    }
    @Test void directCreateAndSeatMapSettleExpiredUsageOnceAndReleaseImmediately() {
        long first=reservation(student,seat1),second=reservation(staff,seat2);String one=code(seat1),two=code(seat2);at(10,0,0);
        bookings.checkin(student,first,one);bookings.checkin(staff,second,two);at(11,0,0);
        assertNotNull(bookings.create(student,new Requests.Reservation(seat1,base.toString(),"11:00","11:30")));assertEquals("completed",row(first).get("status"));assertEquals("checked_in",row(second).get("status"));
        // 到期使用不再挡资源保护，尚未mine/tick/seatMap，不能靠后台任务延迟释放。
        resources.updateSeat(admin,seat2,new Requests.Seat("regular",false,false,"maintenance","02",1,2,1L,"原结束后维护"));
        opening.save(admin,room,new Requests.OpeningChange(null,base.toString(),List.of(new Requests.OpeningWindow("11:00","22:00")),Store.id(resources.room(room),"version"),"只移除已经过去片段"));
        resources.seatMap(room,base.toString(),"11:00","11:30");assertEquals("completed",row(second).get("status"));
        bookings.mine(student);bookings.detail(staff,second);bookings.tick();bookings.tick();assertEquals(2,db.count("SELECT count(*) FROM usage_records WHERE ended_at IS NOT NULL"));assertEquals(2,db.count("SELECT count(*) FROM notifications WHERE type='reservation_completed'"));assertEquals(2,db.count("SELECT count(*) FROM audit_events WHERE action='reservation.complete'"));assertEquals(0,db.count("SELECT count(*) FROM violations"));assertEquals(0,db.count("SELECT count(*) FROM credit_logs"));
    }
    @Test void deadlineActionCommitsFinishBefore409AndRetiredActionsDoNotMutate() throws Exception {
        long id=reservation(student,seat1);String token=token("student"),other=token("staff"),payload=code(seat1);at(10,0,0);bookings.checkin(student,id,payload);at(11,0,0);
        http.perform(auth(post("/api/reservations/"+id+"/checkout"),other)).andExpect(status().isForbidden());assertEquals("checked_in",row(id).get("status"));
        for(String action:List.of("leave","return")) { http.perform(auth(post("/api/reservations/"+id+"/"+action),token)).andExpect(status().isGone());assertEquals("checked_in",row(id).get("status")); }
        http.perform(auth(post("/api/reservations/"+id+"/checkout"),token)).andExpect(status().isConflict()).andExpect(jsonPath("$.details.status").value("completed")).andExpect(jsonPath("$.details.usage_record.end_reason").value("auto_end")).andExpect(jsonPath("$.details.usage_record.duration_seconds").value(3600));
        assertEquals("completed",row(id).get("status"));http.perform(auth(get("/api/reservations/"+id),token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.allowed_actions").isEmpty()).andExpect(jsonPath("$.data.usage_record.settled_at").value(base+"T11:00"));assertEquals(1,db.count("SELECT count(*) FROM notifications WHERE type='reservation_completed'"));
    }
    @Test void finishAndImmediateQueryFailuresRollbackUsageReservationNotificationAndAudit() throws Exception {
        long id=reservation(student,seat1);String token=token("student"),payload=code(seat1);at(10,0,0);bookings.checkin(student,id,payload);at(10,30,0);
        for(boolean auditFault:List.of(true,false)) {
            String table=auditFault?"audit_events":"notifications",expression=auditFault?"action<>'reservation.complete'":"type<>'reservation_completed'";
            db.update("ALTER TABLE "+table+" ADD CONSTRAINT s10_finish_fail CHECK("+expression+") NOT VALID");
            try { http.perform(auth(post("/api/reservations/"+id+"/checkout"),token)).andExpect(status().isInternalServerError());assertEquals("checked_in",row(id).get("status"));assertNull(db.one("SELECT ended_at FROM usage_records WHERE reservation_id=?",id).get("ended_at"));assertNull(row(id).get("ended_at"));assertEquals(0,db.count("SELECT count(*) FROM audit_events WHERE action='reservation.complete'"));assertEquals(0,db.count("SELECT count(*) FROM notifications WHERE type='reservation_completed'")); }
            finally { db.update("ALTER TABLE "+table+" DROP CONSTRAINT s10_finish_fail"); }
        }
        at(11,0,0);db.update("ALTER TABLE audit_events ADD CONSTRAINT s10_finish_fail CHECK(action<>'reservation.complete') NOT VALID");
        try { http.perform(auth(get("/api/reservations/"+id),token)).andExpect(status().isInternalServerError());http.perform(auth(get("/api/reservations/mine"),token)).andExpect(status().isInternalServerError());assertEquals("checked_in",row(id).get("status"));assertNull(db.one("SELECT ended_at FROM usage_records WHERE reservation_id=?",id).get("ended_at")); }
        finally { db.update("ALTER TABLE audit_events DROP CONSTRAINT s10_finish_fail"); }
        http.perform(auth(get("/api/reservations/"+id),token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("completed"));assertEquals(1,db.count("SELECT count(*) FROM notifications WHERE type='reservation_completed'"));
    }
    @Test void creationFailureRollsBackAutomaticUsageFinishAndNewReservationTogether() throws Exception {
        long id=reservation(student,seat1);String token=token("staff"),payload=code(seat1);at(10,0,0);bookings.checkin(student,id,payload);at(11,0,0);
        db.update("ALTER TABLE audit_events ADD CONSTRAINT s10_create_fail CHECK(action<>'reservation.create') NOT VALID");
        try { http.perform(auth(post("/api/reservations"),token).contentType("application/json").content(json.writeValueAsString(new Requests.Reservation(seat1,base.toString(),"11:00","11:30")))).andExpect(status().isInternalServerError());assertEquals("checked_in",row(id).get("status"));assertEquals(1,db.count("SELECT count(*) FROM reservations"));assertNull(db.one("SELECT ended_at FROM usage_records WHERE reservation_id=?",id).get("ended_at"));assertEquals(0,db.count("SELECT count(*) FROM notifications WHERE type='reservation_completed'")); }
        finally { db.update("ALTER TABLE audit_events DROP CONSTRAINT s10_create_fail"); }
        assertNotNull(bookings.create(staff,new Requests.Reservation(seat1,base.toString(),"11:00","11:30")));assertEquals("completed",row(id).get("status"));
    }
    @Test void concurrentCheckinAndConcurrentFinishEachCreateOnlyOneFact() throws Exception {
        long id=reservation(student,seat1);String payload=code(seat1);at(10,0,0);var pool=Executors.newFixedThreadPool(6);var start=new CountDownLatch(1);
        try {
            List<Future<Integer>> results=new ArrayList<>();for(int i=0;i<6;i++) results.add(pool.submit(()->{start.await();try { return bookings.checkin(student,id,payload).status(); } catch(Api.Failure e) { return e.status; }}));start.countDown();int successes=0;for(var result:results) if(result.get(20,TimeUnit.SECONDS)==200) successes++;assertEquals(1,successes);assertEquals(1,db.count("SELECT count(*) FROM usage_records"));assertEquals(1,db.count("SELECT count(*) FROM audit_events WHERE action='reservation.checkin'"));
            at(11,0,0);var finishGate=new CountDownLatch(1);List<Future<?>> finishes=new ArrayList<>();for(int i=0;i<6;i++) { final int source=i;finishes.add(pool.submit(()->{finishGate.await();if(source%3==0) bookings.tick();else if(source%3==1) bookings.mine(student);else bookings.detail(student,id);return null;})); }finishGate.countDown();for(var result:finishes) result.get(20,TimeUnit.SECONDS);
            assertEquals("completed",row(id).get("status"));assertEquals(1,db.count("SELECT count(*) FROM usage_records"));assertEquals(1,db.count("SELECT count(*) FROM audit_events WHERE action='reservation.complete'"));assertEquals(1,db.count("SELECT count(*) FROM notifications WHERE type='reservation_completed'"));assertEquals(0,db.count("SELECT count(*) FROM credit_logs"));
        } finally { pool.shutdownNow(); }
    }
    @Test void statsAndSafeViewsUseUsageFactsAndUnattendedCancellationHasNoUsage() {
        long id=reservation(student,seat1);String payload=code(seat1);at(10,0,0);bookings.checkin(student,id,payload);at(10,30,0);bookings.action(student,id,"checkout");
        // 原列保留兼容溯源，读消费者必须从唯一usage取事实。
        db.update("UPDATE reservations SET checkin_at=?,checkout_at=? WHERE id=?",java.sql.Timestamp.valueOf(base.atTime(8,0)),java.sql.Timestamp.valueOf(base.atTime(8,1)),id);
        clock.set(base.plusDays(1).atTime(9,0));assertEquals(0.5,((Number)stats.top(14,10).get(0).get("hours")).doubleValue(),0.0000001);assertNotNull(stats.heatmap(room,base.toString()));
        var current=bookings.detail(student,id);@SuppressWarnings("unchecked") var actual=(Map<String,Object>)current.get("usage_record");assertEquals(base.atTime(10,0),LocalDateTime.parse(actual.get("checkin_at").toString()));assertEquals(0,new java.math.BigDecimal("1800").compareTo((java.math.BigDecimal)actual.get("duration_seconds")));
        long unused=Store.id(bookings.create(student,new Requests.Reservation(seat2,base.plusDays(1).toString(),"10:00","11:00")),"id");bookings.action(student,unused,"cancel");assertNull(bookings.detail(student,unused).get("usage_record"));assertEquals(1,db.count("SELECT count(*) FROM usage_records"));assertEquals(0,db.count("SELECT count(*) FROM violations"));
    }
}
