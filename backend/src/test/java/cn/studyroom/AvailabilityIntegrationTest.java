package cn.studyroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named="STUDYROOM_INTEGRATION",matches="true")
@SpringBootTest(properties={"spring.datasource.url=${STUDYROOM_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5433/studyroom_java_test}","studyroom.seed=false","studyroom.scheduler-enabled=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class AvailabilityIntegrationTest {
    static class CountingClock extends MinuteBookingIntegrationTest.TestClock { final AtomicInteger calls=new AtomicInteger();@Override public Instant instant() { calls.incrementAndGet();return super.instant(); } }
    @TestConfiguration static class TimeConfig { @Bean @Primary CountingClock testClock() { var c=new CountingClock();c.set(LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1).atTime(9,0));return c; } }
    @Autowired Store db;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    @Autowired AvailabilityService availability;
    @Autowired OpeningScheduleService opening;
    @Autowired ResourceService resources;
    @Autowired BookingService bookings;
    @Autowired UsageService usage;
    @Autowired SeatCodeService codes;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired CountingClock clock;
    long student,staff,admin,room,otherRoom,seatA,seatB,seatC,otherSeat;
    LocalDate base,day;
    String remote;
    @BeforeEach void fixture() {
        assertEquals("studyroom_java_test",db.one("SELECT current_database() AS name").get("name"));
        for(String table:List.of("usage_records","seat_codes","violations","resource_unavailability","opening_windows","opening_schedules","audit_events","credit_logs","notifications","waitlist","reservations","seats","rooms","users")) db.update("DELETE FROM "+table);
        base=LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1);day=base.plusDays(1);clock.set(base.atTime(9,0));remote="s11-test-"+UUID.randomUUID();String hash=new BCryptPasswordEncoder().encode("S02-test-only-password");
        student=db.insert("INSERT INTO users(username,password_hash,campus_id,role) VALUES('s11_student',?,'S11-student','student')",hash);staff=db.insert("INSERT INTO users(username,password_hash,campus_id,role) VALUES('s11_staff',?,'S11-staff','staff')",hash);admin=db.insert("INSERT INTO users(username,password_hash,campus_id,role) VALUES('s11_admin',?,'S11-admin','admin')",hash);
        room=db.insert("INSERT INTO rooms(name,building,floor,location,seat_rows,seat_cols) VALUES('S11目标模拟空间',NULL,'夹层','北侧',1,3)");opening.initializeDefaults(room);
        seatA=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'A',1,1)",room);seatB=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'B',1,2)",room);seatC=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'C',1,3)",room);
        otherRoom=db.insert("INSERT INTO rooms(name,seat_rows,seat_cols) VALUES('S11跨空间模拟',1,1)");opening.initializeDefaults(otherRoom);otherSeat=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'OTHER',1,1)",otherRoom);
    }
    @AfterEach void clearRate() { redis.delete("studyroom-java:rate:login:"+remote);redis.delete("studyroom-java:rate:reserve:"+student); }
    void windows(LocalDate date,String... endpoints) { var ranges=new ArrayList<Requests.OpeningWindow>();for(int i=0;i<endpoints.length;i+=2) ranges.add(new Requests.OpeningWindow(endpoints[i],endpoints[i+1]));opening.save(admin,room,new Requests.OpeningChange(null,date.toString(),ranges,Store.id(resources.room(room),"version"),"S11明确模拟日期开放")); }
    long holder() { return db.insert("INSERT INTO users(username,password_hash,role) VALUES(?,'unused','staff')","holder-"+UUID.randomUUID()); }
    long held(long user,long seat,LocalDate date,String start,String end,String state) { return db.insert("INSERT INTO reservations(user_id,seat_id,res_date,start_time,end_time,status) VALUES(?,?,?,CAST(? AS TIME),CAST(? AS TIME),?)",user,seat,java.sql.Date.valueOf(date),start,end,state); }
    Map<String,Object> find(LocalDate date) { return availability.find(student,room,date.toString(),"earliest"); }
    @SuppressWarnings("unchecked") List<Map<String,Object>> results(Map<String,Object> response) { return (List<Map<String,Object>>)response.get("results"); }
    @SuppressWarnings("unchecked") List<Map<String,Object>> seats(Map<String,Object> response) { return (List<Map<String,Object>>)response.get("seats"); }
    String token(String role) throws Exception { var reply=http.perform(post("/api/auth/login").with(r->{r.setRemoteAddr(remote);return r;}).contentType("application/json").content(json.writeValueAsString(Map.of("username","s11_"+role,"password","S02-test-only-password")))).andExpect(status().isOk()).andReturn();return json.readTree(reply.getResponse().getContentAsString()).path("data").path("token").asText(); }
    String url(LocalDate date) { return "/api/availability?room_id="+room+"&date="+date; }
    @Test void earliestUnionIncludesOwnOtherRoomAndNestedWindowsAreSameSafeFacts() throws Exception {
        windows(day,"08:00","12:00","14:00","16:00");held(holder(),seatA,day,"08:00","09:15","pending");held(holder(),seatB,day,"08:00","10:00","pending");held(holder(),seatC,day,"08:00","12:00","pending");held(student,otherSeat,day,"09:00","09:30","pending");held(student,otherSeat,day,"14:30","15:00","pending");
        codes.generate(admin,seatA,new Requests.SeatCodeChange(0L,"隐私验证模拟码"));String code=Store.text(db.one("SELECT code_value FROM seat_codes WHERE seat_id=?",seatA),"code_value");
        var snapshot=find(day);var rows=results(snapshot);assertEquals(seatA,Store.id(rows.get(0),"seat_id"));assertEquals("09:30",rows.get(0).get("available_start"));assertEquals("12:00",rows.get(0).get("available_end"));assertEquals(150L,rows.get(0).get("duration_minutes"));assertEquals(1470L,rows.get(0).get("wait_minutes"));assertNull(rows.get(0).get("building"));assertEquals("夹层",rows.get(0).get("floor"));
        var nested=new ArrayList<Object>();for(var seat:seats(snapshot)) { nested.addAll((List<?>)seat.get("available_windows"));assertFalse(seat.containsKey("occupied"));assertFalse(seat.containsKey("bookable_for_slot")); }assertEquals(new HashSet<>(rows),new HashSet<>(nested));assertEquals(8,rows.size());
        String raw=json.writeValueAsString(snapshot);for(String forbidden:List.of("code_value","user_id","username","password_hash","reservation_id","open_time","close_time",code)) assertFalse(raw.contains(forbidden));
        @SuppressWarnings("unchecked") var meta=(Map<String,Object>)snapshot.get("room");assertEquals(3L,meta.get("seat_count"));assertEquals(3L,meta.get("capacity"));assertEquals(day.toString(),meta.get("opening_date"));
    }
    @Test void shortFragmentsCannotBeSplicedAndSeparateOpeningWindowsRemainSeparate() {
        windows(day,"08:00","09:20");db.update("UPDATE seats SET status='disabled' WHERE room_id=? AND id<>?",room,seatA);held(holder(),seatA,day,"08:20","09:00","pending");assertTrue(results(find(day)).isEmpty());
        windows(day.plusDays(1),"14:00","14:29");assertTrue(results(find(day.plusDays(1))).isEmpty());windows(day.plusDays(1),"14:00","14:30");assertEquals(30L,results(find(day.plusDays(1))).get(0).get("duration_minutes"));
        windows(day,"08:00","09:20","14:00","15:00");var rows=results(find(day));assertEquals(1,rows.size());assertEquals("14:00",rows.get(0).get("available_start"));assertEquals(60L,rows.get(0).get("duration_minutes"));
    }
    @Test void maintenanceClosureBlocksAndRoomCapacityHaveHonestEmptyResults() {
        db.update("UPDATE seats SET status='maintenance',resource_reason='座位检修' WHERE id=?",seatA);var snap=find(day);assertEquals(List.of(),seats(snap).get(0).get("available_windows"));assertEquals(List.of("座位检修"),seats(snap).get(0).get("closed_reasons"));
        opening.saveBlock(admin,room,null,new Requests.Unavailability(seatB,day.atTime(8,0).toString(),day.atTime(11,0).toString(),Store.id(resources.room(room),"version"),"长段维护"));
        opening.saveBlock(admin,room,null,new Requests.Unavailability(seatB,day.atTime(10,0).toString(),day.atTime(12,0).toString(),Store.id(resources.room(room),"version"),"重叠维护"));
        assertTrue(results(find(day)).stream().anyMatch(row->Store.id(row,"seat_id")==seatB&&"12:00".equals(row.get("available_start"))));
        windows(day);assertTrue(results(find(day)).isEmpty());@SuppressWarnings("unchecked") var closed=(Map<String,Object>)find(day).get("room");assertFalse(((List<?>)closed.get("closed_reasons")).isEmpty());
        db.update("UPDATE rooms SET enabled=FALSE,resource_reason='闭馆装修' WHERE id=?",room);var off=find(day);assertTrue(results(off).isEmpty());@SuppressWarnings("unchecked") var meta=(Map<String,Object>)off.get("room");assertEquals(0L,meta.get("bookable_capacity"));assertEquals(2L,meta.get("capacity"));assertEquals(List.of("闭馆装修"),meta.get("closed_reasons"));
    }
    @Test void historicalSecondsAreSubtractedBeforeInwardRoundingAndEnd24RemainsLegal() {
        windows(day,"00:00","24:00");db.update("UPDATE seats SET status='disabled' WHERE room_id=? AND id<>?",room,seatA);
        held(holder(),seatA,day,"08:00:30.5","09:00:00.123456","pending");held(holder(),seatA,day,"23:00","23:29:59.5","pending");var rows=results(find(day));
        assertEquals(List.of("00:00","09:01","23:30"),rows.stream().map(row->row.get("available_start")).toList());assertEquals(List.of("08:00","23:00","24:00"),rows.stream().map(row->row.get("available_end")).toList());assertEquals(30L,rows.get(2).get("duration_minutes"));
    }
    @Test void snapshotUsesOneRealClockAndStableTiesIncludingMidnightAndMicrosecondPolicy() {
        windows(base,"00:00","24:00");windows(day,"00:00","24:00");clock.set(base.atTime(9,59,59,123456789));clock.calls.set(0);var response=find(base);assertEquals(1,clock.calls.get());
        assertEquals(base+"T09:59:59.123456+08:00",response.get("computed_at"));assertEquals(response.get("computed_at"),response.get("server_now"));assertEquals(List.of("A","B","C"),results(response).stream().map(row->row.get("seat_no")).toList());assertEquals("10:00",results(response).get(0).get("available_start"));assertEquals(1L,results(response).get(0).get("wait_minutes"));
        clock.set(base.atTime(23,59,30));assertTrue(results(find(base)).isEmpty());var tomorrow=find(day);assertEquals("00:00",results(tomorrow).get(0).get("available_start"));assertEquals(1L,results(tomorrow).get(0).get("wait_minutes"));
        clock.set(day.atTime(0,0,0,123));var midnight=find(day);assertEquals(day.toString(),midnight.get("campus_today"));assertEquals("00:00",results(midnight).get(0).get("available_start"));assertNotNull(bookings.create(student,new Requests.Reservation(seatA,day.toString(),"00:00","00:30")));
    }
    @Test void permissionsDateAndStrategyValidationAreRealHttpAndDefaultIsEarliest() throws Exception {
        String user=token("student"),adminToken=token("admin"),staffToken=token("staff");http.perform(get(url(day))).andExpect(status().isUnauthorized());http.perform(get(url(day)).header("Authorization","Bearer "+adminToken)).andExpect(status().isForbidden());
        for(String ordinary:List.of(user,staffToken)) http.perform(get(url(day)).header("Authorization","Bearer "+ordinary)).andExpect(status().isOk()).andExpect(jsonPath("$.data.strategy").value("earliest"));
        http.perform(get(url(base.plusDays(2))).header("Authorization","Bearer "+user)).andExpect(status().isOk());
        for(String strategy:List.of("longest","duration","unknown","")) http.perform(get(url(day)+"&strategy="+strategy).header("Authorization","Bearer "+user)).andExpect(status().isBadRequest());
        for(String invalid:List.of(base.minusDays(1).toString(),base.plusDays(3).toString(),"2026-99-00")) http.perform(get("/api/availability?room_id="+room+"&date="+invalid).header("Authorization","Bearer "+user)).andExpect(status().isBadRequest());
        http.perform(get("/api/availability?room_id=0&date="+day).header("Authorization","Bearer "+user)).andExpect(status().isBadRequest());http.perform(get("/api/availability?room_id=9223372036854775807&date="+day).header("Authorization","Bearer "+user)).andExpect(status().isNotFound());
    }
    @Test void availabilitySnapshotDoesNotReserveAndPostDetectsSeatTakenAfterQuery() throws Exception {
        String token=token("student");var snapshot=find(day);var chosen=results(snapshot).get(0);long seat=Store.id(chosen,"seat_id");String start=Store.text(chosen,"available_start");
        assertEquals(0,db.count("SELECT count(*) FROM reservations"));bookings.create(staff,new Requests.Reservation(seat,day.toString(),start,"08:30"));
        http.perform(post("/api/reservations").header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(new Requests.Reservation(seat,day.toString(),start,"08:30")))).andExpect(status().isConflict());assertEquals(1,db.count("SELECT count(*) FROM reservations"));
        assertEquals("08:30",results(find(day)).stream().filter(row->Store.id(row,"seat_id")==seat).findFirst().orElseThrow().get("available_start"));
    }
    @Test void expiredPendingAndUsageIncludingOwnOtherRoomSettleImmediatelyOnlyOnce() {
        long used=held(staff,seatB,base,"09:00","10:00","checked_in");transactions.executeWithoutResult(tx->usage.start(used,base.atTime(9,1),"demo_seed"));long pending=held(holder(),seatA,base,"10:00","11:30","pending"),own=held(student,otherSeat,base,"10:00","11:30","pending");
        clock.set(base.atTime(11,0));var snap=find(base);assertEquals("completed",db.one("SELECT status FROM reservations WHERE id=?",used).get("status"));assertEquals("unused_ended",db.one("SELECT status FROM reservations WHERE id=?",pending).get("status"));assertEquals("unused_ended",db.one("SELECT status FROM reservations WHERE id=?",own).get("status"));assertEquals("11:00",results(snap).get(0).get("available_start"));assertEquals(2,db.count("SELECT count(*) FROM violations"));
        long audits=db.count("SELECT count(*) FROM audit_events"),notifications=db.count("SELECT count(*) FROM notifications");find(base);bookings.tick();assertEquals(audits,db.count("SELECT count(*) FROM audit_events"));assertEquals(notifications,db.count("SELECT count(*) FROM notifications"));
    }
    @Test void availabilityReconciliationAuditFailureRollsBackAllRelatedFacts() throws Exception {
        String token=token("student");long used=held(staff,seatB,base,"09:00","10:00","checked_in");transactions.executeWithoutResult(tx->usage.start(used,base.atTime(9,1),"demo_seed"));long pending=held(student,otherSeat,base,"10:00","11:30","pending");clock.set(base.atTime(11,0));
        db.update("ALTER TABLE audit_events ADD CONSTRAINT s11_fail CHECK(action<>'reservation.unused_end') NOT VALID");
        try { http.perform(get(url(base)).header("Authorization","Bearer "+token)).andExpect(status().isInternalServerError());assertEquals("checked_in",db.one("SELECT status FROM reservations WHERE id=?",used).get("status"));assertEquals("pending",db.one("SELECT status FROM reservations WHERE id=?",pending).get("status"));assertNull(db.one("SELECT ended_at FROM usage_records WHERE reservation_id=?",used).get("ended_at"));assertEquals(0,db.count("SELECT count(*) FROM notifications"));assertEquals(0,db.count("SELECT count(*) FROM violations"));assertEquals(0,db.count("SELECT count(*) FROM audit_events")); }
        finally { db.update("ALTER TABLE audit_events DROP CONSTRAINT s11_fail"); }
        assertFalse(results(find(base)).isEmpty());assertEquals(1,db.count("SELECT count(*) FROM violations"));
    }
}
