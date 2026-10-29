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
class MinuteBookingIntegrationTest {
    static class TestClock extends Clock {
        private volatile Instant value;
        void set(LocalDateTime date) { value=date.atZone(BookingPolicy.CAMPUS_ZONE).toInstant(); }
        @Override public Instant instant() { return value; }
        @Override public ZoneId getZone() { return BookingPolicy.CAMPUS_ZONE; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(value,zone); }
    }
    @TestConfiguration static class TimeConfig { @Bean @Primary TestClock testClock() { var c=new TestClock();c.set(LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1).atTime(9,0));return c; } }
    @Autowired Store db;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    @Autowired BookingService bookings;
    @Autowired SeatCodeService codes;
    @Autowired ResourceService resources;
    @Autowired OpeningScheduleService opening;
    @Autowired StatsService stats;
    @Autowired UsageService usage;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired TestClock clock;
    long admin,student,staff,room,seat1,seat2;
    LocalDate base;
    String remote;
    static final String PASSWORD="S02-test-only-password";
    String seatCode(long seat) {
        if(db.count("SELECT count(*) FROM seat_codes WHERE seat_id=?",seat)==0) codes.generate(admin,seat,new Requests.SeatCodeChange(0L,"测试生成真实座位码"));
        return Store.text(db.one("SELECT code_value FROM seat_codes WHERE seat_id=?",seat),"code_value");
    }
    @BeforeEach void fixture() {
        assertEquals("studyroom_java_test",db.one("SELECT current_database() AS name").get("name"));
        for(String table:List.of("reminders","complaint_attachments","complaint_events","complaints","resource_change_operations","usage_records","seat_codes","violations","resource_unavailability","opening_windows","opening_schedules","audit_events","credit_logs","notifications","waitlist","reservations","seats","rooms","users")) db.update("DELETE FROM "+table);
        base=LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1);clock.set(base.atTime(9,0));remote="s07-test-"+UUID.randomUUID();
        String hash=new BCryptPasswordEncoder().encode(PASSWORD);
        admin=db.insert("INSERT INTO users(username,password_hash,role) VALUES('s07_admin',?,'admin')",hash);
        student=db.insert("INSERT INTO users(username,password_hash,role) VALUES('s07_student',?,'student')",hash);
        staff=db.insert("INSERT INTO users(username,password_hash,role) VALUES('s07_staff',?,'staff')",hash);
        room=db.insert("INSERT INTO rooms(name,seat_rows,seat_cols) VALUES('S07全日模拟空间',1,2)");opening.initializeDefaults(room);
        seat1=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'01',1,1)",room);seat2=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'02',1,2)",room);
        for(int weekday=1;weekday<=7;weekday++) opening.save(admin,room,new Requests.OpeningChange(weekday,null,List.of(new Requests.OpeningWindow("00:00","24:00")),Store.id(resources.room(room),"version"),"测试24小时合法开放"));
        db.update("DELETE FROM audit_events");
    }
    @AfterEach void clearRate() { redis.delete("studyroom-java:rate:login:"+remote);for(long id:List.of(student,staff,admin)) redis.delete("studyroom-java:rate:reserve:"+id); }
    String d(int offset) { return base.plusDays(offset).toString(); }
    Requests.Reservation req(long seat,int offset,String start,String end) { return new Requests.Reservation(seat,d(offset),start,end); }
    String token(String role) throws Exception {
        var result=http.perform(post("/api/auth/login").with(r->{r.setRemoteAddr(remote);return r;}).contentType("application/json").content(json.writeValueAsString(Map.of("username","s07_"+role,"password",PASSWORD))))
            .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }
    MockHttpServletRequestBuilder payload(MockHttpServletRequestBuilder request,String token,Object body) throws Exception { return request.header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(body)); }
    @Test void policyAndOneMinuteAheadSubmissionUseSameCampusClock() throws Exception {
        http.perform(get("/api/booking-policy")).andExpect(status().isUnauthorized());
        String token=token("student");
        http.perform(get("/api/booking-policy").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.campus_today").value(d(0))).andExpect(jsonPath("$.data.dates[2]").value(d(2))).andExpect(jsonPath("$.data.min_duration_minutes").value(30)).andExpect(jsonPath("$.data.campus_zone").value("Asia/Shanghai")).andExpect(jsonPath("$.data.server_now").value(d(0)+"T09:00+08:00"));
        http.perform(payload(post("/api/reservations"),token,req(seat1,0,"09:01","09:31"))).andExpect(status().isOk());
        assertEquals(1,db.count("SELECT count(*) FROM audit_events WHERE action='reservation.create'"));
    }
    @Test void arbitraryMinutesNoDurationOrDailyFourHourCapAndDateBounds() {
        assertNotNull(bookings.create(student,req(seat1,0,"09:00","13:01")));
        assertNotNull(bookings.create(student,req(seat1,0,"13:01","18:01")));
        assertNotNull(bookings.create(student,req(seat1,1,"00:00","10:00")));
        assertEquals(400,assertThrows(Api.Failure.class,()->bookings.create(staff,req(seat2,0,"09:01","09:30"))).status);
        assertEquals(400,assertThrows(Api.Failure.class,()->bookings.create(staff,req(seat2,3,"09:00","10:00"))).status);
        assertEquals(400,assertThrows(Api.Failure.class,()->bookings.create(staff,req(seat2,-1,"23:30","24:00"))).status);
        assertEquals(400,assertThrows(Api.Failure.class,()->bookings.create(staff,req(seat2,0,"08:59","09:30"))).status);
        assertEquals(3,db.count("SELECT count(*) FROM reservations"));
    }
    @Test void postgresMidnightRoundTripSeatMapAndCrossDateAdjacencyArePreserved() {
        var first=bookings.create(student,req(seat1,0,"23:30","24:00"));long id=Store.id(first,"id");
        assertEquals("24:00:00",first.get("end_time"));
        assertEquals(1,db.count("SELECT count(*) FROM reservations WHERE id=? AND end_time=TIME '24:00:00'",id));
        assertEquals("24:00:00",db.one("SELECT end_time FROM reservations WHERE id=?",id).get("end_time"));
        assertNotNull(bookings.create(student,req(seat1,1,"00:00","00:30")));
        assertNotNull(bookings.create(student,req(seat1,2,"23:30","24:00:00.000")));
        @SuppressWarnings("unchecked") var seats=(List<Map<String,Object>>)resources.seatMap(room,d(0),"23:30","24:00").get("seats");
        assertEquals(true,seats.get(0).get("occupied"));assertEquals(true,seats.get(0).get("bookable_for_slot"));
        assertEquals(409,assertThrows(Api.Failure.class,()->opening.save(admin,room,new Requests.OpeningChange(null,d(0),List.of(new Requests.OpeningWindow("00:00","23:30")),Store.id(resources.room(room),"version"),"不得裁掉午夜边界预约"))).status);
        for(var times:List.of(new String[]{"23:31","24:00"},new String[]{"24:00","24:00"},new String[]{"23:30","24:01"},new String[]{"22:00","02:00"})) assertEquals(400,assertThrows(Api.Failure.class,()->bookings.create(staff,req(seat2,0,times[0],times[1]))).status);
        assertEquals(3,db.count("SELECT count(*) FROM reservations"));
    }
    @Test void midnightLifecycleSettlementAndStatsEndAtNextDayZeroWithoutNewCredit() {
        long id=Store.id(bookings.create(student,req(seat1,0,"23:30","24:00")),"id");
        clock.set(base.atTime(23,30));bookings.checkin(student,id,seatCode(seat1));
        clock.set(base.plusDays(1).atStartOfDay());bookings.tick();bookings.tick();
        var row=db.one("SELECT status,checkout_at,end_time FROM reservations WHERE id=?",id);
        assertEquals("completed",row.get("status"));assertEquals(base.plusDays(1).atStartOfDay(),LocalDateTime.parse(db.one("SELECT ended_at FROM usage_records WHERE reservation_id=?",id).get("ended_at").toString()));assertEquals("24:00:00",row.get("end_time"));
        assertEquals(0,db.count("SELECT count(*) FROM credit_logs WHERE reservation_id=?",id));
        assertEquals(0.5,((Number)stats.top(14,10).get(0).get("hours")).doubleValue(),0.00001);
        assertNotNull(stats.heatmap(room,d(0)));
        var peak=stats.peak(14);assertEquals(24,peak.size());assertTrue(peak.stream().anyMatch(bucket->"00:00".equals(bucket.get("hour"))));
        assertEquals(1L,((Number)peak.stream().filter(bucket->"23:00".equals(bucket.get("hour"))).findFirst().orElseThrow().get("count")).longValue());
        assertNotNull(bookings.create(student,req(seat1,1,"00:00","00:30")));
    }
    @Test void genuineHistoricalSecondsRemainReadableProtectableAndFinishable() {
        long id=db.insert("INSERT INTO reservations(user_id,seat_id,res_date,start_time,end_time,status,source,checkin_at) VALUES(?,?,?,TIME '08:59:59.500000',TIME '09:10:00.123456','checked_in','auto',?)",student,seat1,java.sql.Date.valueOf(base),java.sql.Timestamp.valueOf(base.atTime(8,59,59,500000000)));
        transactions.executeWithoutResult(tx->usage.start(id,base.atTime(8,59,59,500000000),"legacy"));
        var raw=db.one("SELECT start_time,end_time FROM reservations WHERE id=?",id);
        assertEquals(base.atTime(8,59,59,500000000),Slot.endpoint(base,raw.get("start_time").toString()));
        assertEquals(base.atTime(9,10,0,123456000),Slot.endpoint(base,raw.get("end_time").toString()));
        assertEquals(409,assertThrows(Api.Failure.class,()->opening.save(admin,room,new Requests.OpeningChange(null,d(0),List.of(new Requests.OpeningWindow("09:00","09:10")),Store.id(resources.room(room),"version"),"保真保护历史小数端点"))).status);
        clock.set(base.atTime(9,5,0,100000000));bookings.action(student,id,"checkout");
        var row=db.one("SELECT status,source,start_time,end_time FROM reservations WHERE id=?",id);assertEquals("completed",row.get("status"));assertEquals("auto",row.get("source"));assertEquals(raw.get("start_time"),row.get("start_time"));assertEquals(raw.get("end_time"),row.get("end_time"));
    }
    @Test void retiredHttpServiceAndTickLeaveHistoryQueryableAndOwnerCanExit() throws Exception {
        String token=token("student"),adminToken=token("admin");
        for(String path:List.of("/api/reservations/auto","/api/waitlist")) {
            http.perform(post(path).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
            http.perform(payload(post(path),token,Map.of())).andExpect(status().isGone());
            http.perform(payload(post(path),adminToken,Map.of())).andExpect(status().isForbidden());
        }
        assertEquals(410,assertThrows(Api.Failure.class,()->bookings.allocate(student,new Requests.Allocation(room,d(1),"10:00","11:00",null,null,null,0,true))).status);
        assertEquals(410,assertThrows(Api.Failure.class,()->bookings.join(student,new Requests.Wait(room,d(1),"10:00","11:00",null,null,null))).status);
        long waiting=db.insert("INSERT INTO waitlist(user_id,room_id,res_date,start_time,end_time,zone) VALUES(?,?,?,TIME '10:00',TIME '11:00','quiet')",student,room,java.sql.Date.valueOf(base.plusDays(1)));
        bookings.tick();bookings.tick();assertEquals(0,db.count("SELECT count(*) FROM reservations"));
        http.perform(get("/api/waitlist/mine").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].status").value("waiting")).andExpect(jsonPath("$.data[0].zone").value("quiet"));
        assertEquals(403,assertThrows(Api.Failure.class,()->bookings.cancelWait(staff,waiting)).status);
        http.perform(post("/api/waitlist/"+waiting+"/cancel").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        assertEquals("cancelled",db.one("SELECT status FROM waitlist WHERE id=?",waiting).get("status"));
    }
    @Test void reservationAuditFailureRollsBackReservationAndNotificationAndRetryWorks() throws Exception {
        String token=token("student");
        db.update("ALTER TABLE audit_events ADD CONSTRAINT s07_reject_create CHECK(action<>'reservation.create') NOT VALID");
        try {
            http.perform(payload(post("/api/reservations"),token,req(seat1,0,"09:01","09:31"))).andExpect(status().isInternalServerError());
            assertEquals(0,db.count("SELECT count(*) FROM reservations"));assertEquals(0,db.count("SELECT count(*) FROM notifications"));assertEquals(0,db.count("SELECT count(*) FROM audit_events"));
        } finally { db.update("ALTER TABLE audit_events DROP CONSTRAINT s07_reject_create"); }
        http.perform(payload(post("/api/reservations"),token,req(seat1,0,"09:01","09:31"))).andExpect(status().isOk());
        assertEquals(1,db.count("SELECT count(*) FROM reservations"));assertEquals(1,db.count("SELECT count(*) FROM notifications"));assertEquals(1,db.count("SELECT count(*) FROM audit_events WHERE action='reservation.create'"));
    }
}
