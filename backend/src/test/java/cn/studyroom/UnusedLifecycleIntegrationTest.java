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
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named="STUDYROOM_INTEGRATION",matches="true")
@SpringBootTest(properties={"spring.datasource.url=${STUDYROOM_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5433/studyroom_java_test}","studyroom.seed=false","studyroom.scheduler-enabled=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class UnusedLifecycleIntegrationTest {
    @TestConfiguration static class TimeConfig {
        @Bean @Primary MinuteBookingIntegrationTest.TestClock testClock() { var c=new MinuteBookingIntegrationTest.TestClock();c.set(LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1).atTime(9,0));return c; }
    }
    @Autowired Store db;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    @Autowired BookingService bookings;
    @Autowired SeatCodeService codes;
    @Autowired OpeningScheduleService opening;
    @Autowired ResourceService resources;
    @Autowired MinuteBookingIntegrationTest.TestClock clock;
    LocalDate base;
    long student,staff,admin,room,seat1,seat2;
    String remote;
    static final String PASSWORD="S02-test-only-password";
    String seatCode(long seat) {
        if(db.count("SELECT count(*) FROM seat_codes WHERE seat_id=?",seat)==0) codes.generate(admin,seat,new Requests.SeatCodeChange(0L,"测试生成真实座位码"));
        return Store.text(db.one("SELECT code_value FROM seat_codes WHERE seat_id=?",seat),"code_value");
    }
    @BeforeEach void setup() {
        assertEquals("studyroom_java_test",db.one("SELECT current_database() AS name").get("name"));
        for(String table:List.of("notifications","restriction_operations","restrictions","violations","complaint_target_events","complaint_targets","reminders","complaint_attachments","complaint_events","complaints","resource_change_operations","usage_records","seat_codes","resource_unavailability","opening_windows","opening_schedules","audit_events","credit_logs","waitlist","reservations","seats","rooms","users")) db.update("DELETE FROM "+table);
        base=LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1);clock.set(base.atTime(9,0));remote="s08-test-"+UUID.randomUUID();
        String hash=new BCryptPasswordEncoder().encode(PASSWORD);
        student=db.insert("INSERT INTO users(username,password_hash,campus_id,role) VALUES('s08_student',?,'S08-student','student')",hash);
        staff=db.insert("INSERT INTO users(username,password_hash,campus_id,role) VALUES('s08_staff',?,'S08-staff','staff')",hash);
        admin=db.insert("INSERT INTO users(username,password_hash,campus_id,role) VALUES('s08_admin',?,'S08-admin','admin')",hash);
        room=db.insert("INSERT INTO rooms(name,seat_rows,seat_cols) VALUES('S08模拟空间',1,2)");opening.initializeDefaults(room);
        seat1=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'01',1,1)",room);seat2=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'02',1,2)",room);
    }
    @AfterEach void clearRate() { redis.delete("studyroom-java:rate:login:"+remote);for(long id:List.of(student,staff,admin)) redis.delete("studyroom-java:rate:reserve:"+id); }
    String d() { return base.toString(); }
    void at(int h,int m,int s) { clock.set(base.atTime(h,m,s)); }
    long create(long user,long seat,String start,String end) { return Store.id(bookings.create(user,new Requests.Reservation(seat,d(),start,end)),"id"); }
    String token(String role) throws Exception {
        var result=http.perform(post("/api/auth/login").with(r->{r.setRemoteAddr(remote);return r;}).contentType("application/json").content(json.writeValueAsString(Map.of("username","s08_"+role,"password",PASSWORD))))
            .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }
    Map<String,Object> row(long id) { return db.one("SELECT * FROM reservations WHERE id=?",id); }
    MockHttpServletRequestBuilder action(long id,String name,String token) {
        var req=post("/api/reservations/"+id+"/"+name).header("Authorization","Bearer "+token);
        if(name.equals("checkin")) req.contentType("application/json").content("{\"seat_code\":\""+seatCode(Store.id(row(id),"seat_id"))+"\"}");return req;
    }
    @Test void beforeStartCancellationNeverCreatesPenaltyAndReleasesSlot() {
        long id=create(student,seat1,"10:00","11:00");at(9,59,59);
        assertEquals(List.of("cancel"),bookings.mine(student).get(0).get("allowed_actions"));
        assertEquals(403,assertThrows(Api.Failure.class,()->bookings.action(staff,id,"cancel")).status);
        var result=bookings.action(student,id,"cancel");assertEquals(200,result.status());assertEquals(id,result.data().get("cancelled"));
        assertEquals("cancelled",row(id).get("status"));assertEquals("user_cancelled",row(id).get("termination_reason"));assertEquals(base.atTime(9,59,59),LocalDateTime.parse(row(id).get("ended_at").toString()));
        assertEquals(0,db.count("SELECT count(*) FROM violations"));assertEquals(0,db.count("SELECT count(*) FROM credit_logs"));assertEquals(100,db.count("SELECT credit_score FROM users WHERE id=?",student));
        assertEquals(1,db.count("SELECT count(*) FROM notifications WHERE type='reservation_cancelled'"));
        assertEquals(409,assertThrows(Api.Failure.class,()->bookings.action(student,id,"cancel")).status);
        assertNotNull(bookings.create(staff,new Requests.Reservation(seat1,d(),"10:00","11:00")));
    }
    @Test void abandonAtStartIsOneIndependentEventAndCannotBecomeAnotherNoShow() {
        long id=create(student,seat1,"09:00","10:00");
        assertEquals(List.of("checkin","abandon"),bookings.mine(student).get(0).get("allowed_actions"));
        assertEquals(200,bookings.action(student,id,"abandon").status());at(9,20,0);bookings.tick();bookings.mine(student);
        var violation=db.one("SELECT * FROM violations WHERE reservation_id=?",id);assertEquals("abandon",violation.get("type"));assertEquals("unused:"+id,violation.get("event_key"));assertEquals("active",violation.get("status"));assertEquals("automatic",violation.get("source"));
        assertEquals(base.atTime(9,0),LocalDateTime.parse(violation.get("occurred_at").toString()));assertEquals(row(id).get("ended_at"),violation.get("occurred_at"));
        assertEquals(1,db.count("SELECT count(*) FROM violations WHERE reservation_id=?",id));assertEquals(0,db.count("SELECT count(*) FROM credit_logs"));
        assertEquals(409,assertThrows(Api.Failure.class,()->bookings.action(student,id,"abandon")).status);assertEquals(List.of(),bookings.mine(student).get(0).get("allowed_actions"));
        assertNotNull(bookings.create(staff,new Requests.Reservation(seat1,d(),"09:20","09:50")));
    }
    @Test void deadlineOperationCommitsNoShowBeforeHttp409AndOwnershipPrecedesSettlement() throws Exception {
        long id=create(student,seat1,"09:00","10:00"),other=create(staff,seat2,"09:00","10:00");String studentToken=token("student"),staffToken=token("staff");at(9,15,0);
        http.perform(action(other,"checkin",studentToken)).andExpect(status().isForbidden());assertEquals("pending",row(other).get("status"));
        http.perform(action(id,"checkin",studentToken)).andExpect(status().isConflict()).andExpect(jsonPath("$.details.status").value("unused_ended")).andExpect(jsonPath("$.details.termination_reason").value("no_show")).andExpect(jsonPath("$.details.reservation_id").value(id));
        assertEquals("unused_ended",row(id).get("status"));assertEquals(1,db.count("SELECT count(*) FROM violations WHERE reservation_id=?",id));
        assertEquals(base.atTime(9,15),LocalDateTime.parse(row(id).get("ended_at").toString()));
        at(9,17,0);
        http.perform(get("/api/reservations/mine").header("Authorization","Bearer "+staffToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].status").value("unused_ended")).andExpect(jsonPath("$.data[0].allowed_actions").isEmpty());
        var delayed=db.one("SELECT occurred_at,registered_at FROM violations WHERE reservation_id=?",other);assertEquals(base.atTime(9,15),LocalDateTime.parse(delayed.get("occurred_at").toString()));assertEquals(base.atTime(9,17),LocalDateTime.parse(delayed.get("registered_at").toString()));
        var own=http.perform(get("/api/violations/mine").header("Authorization","Bearer "+studentToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].reservation_id").value(id)).andExpect(jsonPath("$.data[0].event_key").doesNotExist()).andExpect(jsonPath("$.data[0].user_id").doesNotExist()).andReturn();
        assertFalse(own.getResponse().getContentAsString().contains("s08_staff"));
        http.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.data.database_name").value("studyroom_java_test")).andExpect(jsonPath("$.data.database").value("PostgreSQL"));
    }
    @Test void abandonTickAndMineRacesShareOneEventBeforeAndAtDeadline() throws Exception {
        long[] users={student,staff};long[] reservations={create(student,seat1,"09:00","10:00"),create(staff,seat2,"09:00","10:00")};
        for(int i=0;i<2;i++) {
            long uid=users[i],id=reservations[i];at(9,i==0?14:15,i==0?59:0);
            var pool=java.util.concurrent.Executors.newFixedThreadPool(3);var ready=new java.util.concurrent.CountDownLatch(3);var start=new java.util.concurrent.CountDownLatch(1);
            try {
                var operation=pool.submit(()->{ready.countDown();start.await();try{return bookings.action(uid,id,"abandon").status();}catch(Api.Failure e){return e.status;}});
                var tick=pool.submit(()->{ready.countDown();start.await();bookings.tick();return true;});
                var query=pool.submit(()->{ready.countDown();start.await();bookings.mine(uid);return true;});
                assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));start.countDown();assertEquals(i==0?200:409,operation.get(20,java.util.concurrent.TimeUnit.SECONDS));assertTrue(tick.get(20,java.util.concurrent.TimeUnit.SECONDS));assertTrue(query.get(20,java.util.concurrent.TimeUnit.SECONDS));
            } finally { start.countDown();pool.shutdownNow(); }
            assertEquals(1,db.count("SELECT count(*) FROM violations WHERE reservation_id=?",id));assertEquals(i==0?"abandon":"no_show",db.one("SELECT type FROM violations WHERE reservation_id=?",id).get("type"));
            assertEquals(1,db.count("SELECT count(*) FROM notifications WHERE user_id=? AND type='violation'",uid));
        }
        assertEquals(2,db.count("SELECT count(*) FROM violations"));assertEquals(0,db.count("SELECT count(*) FROM credit_logs"));
    }
    @Test void notificationAndAuditFailuresRollbackAbandonThenRetryCommitsAllFacts() throws Exception {
        long id=create(student,seat1,"09:00","10:00");String token=token("student");at(9,1,0);
        for(boolean auditFault:List.of(true,false)) {
            String table=auditFault?"audit_events":"notifications",constraint=auditFault?"action<>'reservation.unused_end'":"type<>'violation'";
            db.update("ALTER TABLE "+table+" ADD CONSTRAINT s08_failure CHECK ("+constraint+") NOT VALID");
            try {
                http.perform(action(id,"abandon",token)).andExpect(status().isInternalServerError());assertEquals("pending",row(id).get("status"));assertNull(row(id).get("ended_at"));
                assertEquals(0,db.count("SELECT count(*) FROM violations"));assertEquals(1,db.count("SELECT count(*) FROM notifications"));assertEquals(1,db.count("SELECT count(*) FROM audit_events"));
            } finally { db.update("ALTER TABLE "+table+" DROP CONSTRAINT s08_failure"); }
        }
        http.perform(action(id,"abandon",token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("unused_ended"));
        assertEquals(1,db.count("SELECT count(*) FROM violations"));assertEquals(2,db.count("SELECT count(*) FROM notifications"));assertEquals(3,db.count("SELECT count(*) FROM audit_events"));
    }
    @Test void cancellationAndGetTriggeredDeadlineAuditFailuresLeaveNoPartialFacts() throws Exception {
        long future=create(student,seat1,"10:00","11:00"),due=create(staff,seat2,"09:00","10:00");String studentToken=token("student"),staffToken=token("staff");
        db.update("ALTER TABLE audit_events ADD CONSTRAINT s08_failure CHECK(action NOT IN ('reservation.cancel','reservation.unused_end')) NOT VALID");
        try {
            http.perform(action(future,"cancel",studentToken)).andExpect(status().isInternalServerError());assertEquals("pending",row(future).get("status"));
            at(9,15,0);http.perform(get("/api/reservations/mine").header("Authorization","Bearer "+staffToken)).andExpect(status().isInternalServerError());
            assertEquals("pending",row(due).get("status"));assertEquals(0,db.count("SELECT count(*) FROM violations"));assertEquals(2,db.count("SELECT count(*) FROM notifications"));
        } finally { db.update("ALTER TABLE audit_events DROP CONSTRAINT s08_failure"); }
        http.perform(get("/api/reservations/mine").header("Authorization","Bearer "+staffToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].status").value("unused_ended"));
        assertEquals(1,db.count("SELECT count(*) FROM violations"));
    }
    @Test void oldCreditAndRestrictionAreReadOnlyUntilOriginalDeadlineWithoutNewLowScoreBan() throws Exception {
        db.update("UPDATE users SET credit_score=50,credit_banned_until=? WHERE id=?",java.sql.Timestamp.valueOf(base.atTime(10,0)),student);
        db.update("INSERT INTO restrictions(user_id,source,starts_at,ends_at,reason,created_at) VALUES(?,'legacy',?,?,'S19旧限制余效接管工程模拟，原起点与原因未知',?)",student,java.sql.Timestamp.valueOf(base.atTime(9,0)),java.sql.Timestamp.valueOf(base.atTime(10,0)),java.sql.Timestamp.valueOf(base.atTime(9,0)));
        db.update("INSERT INTO credit_logs(user_id,delta,reason) VALUES(?,-8,'历史模拟信用流水')",student);
        String token=token("student");
        http.perform(get("/api/credit").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.history_only").value(true)).andExpect(jsonPath("$.data.legacy_ban_active").value(true)).andExpect(jsonPath("$.data.legacy_banned_until").value(d()+"T10:00+08:00"));
        assertEquals(403,assertThrows(Api.Failure.class,()->create(student,seat1,"11:00","12:00")).status);
        at(10,0,0);http.perform(get("/api/credit").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.legacy_ban_active").value(false));
        long id=create(student,seat1,"10:00","11:00");bookings.checkin(student,id,seatCode(seat1));at(10,30,0);bookings.action(student,id,"checkout");
        assertEquals(50,db.count("SELECT credit_score FROM users WHERE id=?",student));assertEquals(1,db.count("SELECT count(*) FROM credit_logs WHERE user_id=?",student));assertEquals(base.atTime(10,0),LocalDateTime.parse(db.one("SELECT credit_banned_until FROM users WHERE id=?",student).get("credit_banned_until").toString()));
    }
    @Test void directCreationAndSeatMapReleaseExpiredPendingWithoutWaitingForTickOrMine() {
        long first=create(student,seat1,"09:00","10:00");at(9,15,0);
        // 直接创建原座位的余时，未先调用mine或tick。
        long next=create(staff,seat1,"09:15","09:45");
        assertEquals("unused_ended",row(first).get("status"));assertEquals(1,db.count("SELECT count(*) FROM violations WHERE reservation_id=?",first));assertEquals("pending",row(next).get("status"));
        // 本人旧预约在另一座位上过期，不能继续挡住新座位合法请求。
        at(9,30,0);long moved=create(staff,seat2,"09:30","10:00");
        assertEquals("unused_ended",row(next).get("status"));assertEquals(1,db.count("SELECT count(*) FROM violations WHERE reservation_id=?",next));
        at(9,45,0);
        @SuppressWarnings("unchecked") var map=(List<Map<String,Object>>)resources.seatMap(room,d(),"09:45","10:15").get("seats");
        assertEquals(false,map.get(1).get("occupied"));assertEquals("unused_ended",row(moved).get("status"));
        resources.seatMap(room,d(),"09:45","10:15");bookings.tick();
        assertEquals(1,db.count("SELECT count(*) FROM violations WHERE reservation_id=?",moved));assertEquals(3,db.count("SELECT count(*) FROM violations"));
    }
    @Test void creationSettlementAuditFailureDoesNotLeaveHalfReleasedOrNewRecords() throws Exception {
        long first=create(student,seat1,"09:00","10:00");at(9,15,0);String token=token("staff");
        db.update("ALTER TABLE audit_events ADD CONSTRAINT s08_release_failure CHECK(action<>'reservation.create') NOT VALID");
        try {
            http.perform(post("/api/reservations").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(new Requests.Reservation(seat1,d(),"09:15","09:45")))).andExpect(status().isInternalServerError());
            assertEquals("pending",row(first).get("status"));assertEquals(1,db.count("SELECT count(*) FROM reservations"));assertEquals(0,db.count("SELECT count(*) FROM violations"));assertEquals(1,db.count("SELECT count(*) FROM notifications"));assertEquals(1,db.count("SELECT count(*) FROM audit_events"));
        } finally { db.update("ALTER TABLE audit_events DROP CONSTRAINT s08_release_failure"); }
        assertNotNull(bookings.create(staff,new Requests.Reservation(seat1,d(),"09:15","09:45")));
        assertEquals("unused_ended",row(first).get("status"));assertEquals(1,db.count("SELECT count(*) FROM violations"));
    }
    @Test void resourceAndOpeningProtectionIgnoreAlreadyExpiredPendingWithoutWaitingForTick() {
        long first=create(student,seat1,"09:00","10:00"),second=create(staff,seat2,"09:00","10:00");at(9,15,0);
        resources.updateSeat(admin,seat1,new Requests.Seat("regular",false,false,"maintenance","01",1,1,1L,"截止后维护未使用座位"));
        opening.save(admin,room,new Requests.OpeningChange(null,d(),List.of(),Store.id(resources.room(room),"version"),"截止后闭馆，不执行管理取消"));
        assertEquals("maintenance",db.one("SELECT status FROM seats WHERE id=?",seat1).get("status"));assertEquals(List.of(),opening.calendar(room).day(base,null).windows());
        assertEquals("pending",row(first).get("status"));assertEquals("pending",row(second).get("status"));
        // 保护只使用同一纯截止判断；真正事实仍由统一结算入口落实。
        bookings.mine(student);bookings.mine(staff);bookings.tick();assertEquals(2,db.count("SELECT count(*) FROM violations"));
        assertEquals("no_show",row(first).get("termination_reason"));assertEquals("no_show",row(second).get("termination_reason"));assertEquals(0,db.count("SELECT count(*) FROM credit_logs"));
    }
    @Test void allowedActionsAndRetiredTempActionsFollowServerWindowWithoutEarlyCheckin() throws Exception {
        long id=create(student,seat1,"10:00","11:00");String token=token("student");at(9,59,59);
        http.perform(action(id,"checkin",token)).andExpect(status().isConflict());http.perform(action(id,"abandon",token)).andExpect(status().isConflict());
        assertEquals(List.of("cancel"),bookings.mine(student).get(0).get("allowed_actions"));
        at(10,0,0);http.perform(action(id,"checkin",token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.action").value("checked_in"));
        assertEquals(List.of("checkout"),bookings.mine(student).get(0).get("allowed_actions"));
        http.perform(action(id,"leave",token)).andExpect(status().isGone());http.perform(action(id,"return",token)).andExpect(status().isGone());
        assertEquals(410,assertThrows(Api.Failure.class,()->bookings.action(student,id,"leave")).status);assertEquals(410,assertThrows(Api.Failure.class,()->bookings.action(student,id,"return")).status);
        assertEquals("checked_in",row(id).get("status"));assertNull(row(id).get("leave_at"));
        http.perform(action(id,"abandon",token)).andExpect(status().isConflict());assertEquals(0,db.count("SELECT count(*) FROM violations"));
    }
}
