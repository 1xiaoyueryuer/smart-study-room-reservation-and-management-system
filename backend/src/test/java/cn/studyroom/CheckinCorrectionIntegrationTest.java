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
class CheckinCorrectionIntegrationTest {
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
        TestDatabaseCleanup.clear(db);
        base=LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1);clock.set(base.atTime(9,0));remote="s23-test-"+UUID.randomUUID();
        String hash=new BCryptPasswordEncoder().encode("S02-test-only-password");
        admin=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s23_admin',?,'admin','S09-admin')",hash);
        student=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s23_student',?,'student','S09-student')",hash);
        staff=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s23_staff',?,'staff','S09-staff')",hash);
        room=db.insert("INSERT INTO rooms(name,seat_rows,seat_cols) VALUES('S10模拟空间',1,2)");opening.initializeDefaults(room);
        seat1=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'01',1,1)",room);seat2=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'02',1,2)",room);
    }
    @AfterEach void clearRate() { redis.delete("studyroom-java:rate:login:"+remote); }
    void at(int h,int m,int s) { clock.set(base.atTime(h,m,s)); }
    String token(String role) throws Exception {
        var result=http.perform(post("/api/auth/login").with(r->{r.setRemoteAddr(remote);return r;}).contentType("application/json").content(json.writeValueAsString(Map.of("username","s23_"+role,"password","S02-test-only-password")))).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }
    long reservation(long user,long seat) { return Store.id(bookings.create(user,new Requests.Reservation(seat,base.toString(),"10:00","11:00")),"id"); }
    String code(long seat) { codes.generate(admin,seat,new Requests.SeatCodeChange(0L,"测试实际生成座位码"));return current(seat); }
    String current(long seat) { return Store.text(db.one("SELECT code_value FROM seat_codes WHERE seat_id=?",seat),"code_value"); }

    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder req,String token) { return req.header("Authorization","Bearer "+token); }
    MockHttpServletRequestBuilder checkin(long id,String code,String token) throws Exception { return auth(post("/api/reservations/"+id+"/checkin"),token).contentType("application/json").content(json.writeValueAsString(Map.of("seat_code",code))); }
    Map<String,Object> row(long id) { return db.one("SELECT * FROM reservations WHERE id=?",id); }

    String business(){return base+"T10:02:00+08:00";}
    BookingService.ActionResult correct(long id,String operation){return bookings.correctCheckin(admin,id,operation,business(),"Public correction reason","S23_PRIVATE_TEST_ONLY");}
    String path(long id){return "/api/admin/reservations/"+id+"/correct-checkin";}
    Map<String,Object> body(String op){return new LinkedHashMap<>(Map.of("operation_id",op,"checkin_at",business(),"reason","Public correction reason","verification_note","S23_PRIVATE_TEST_ONLY","facts_confirmed",true));}
    String state(long id){return Store.text(row(id),"status");}
    @SuppressWarnings("unchecked") Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    @Test void successStoresTwoTimesPublicProjectionAndUniqueUsageThroughCheckout()throws Exception{
        long id=reservation(student,seat1);at(10,5,0);String op=UUID.randomUUID().toString();var result=correct(id,op);assertEquals(200,result.status());assertEquals("checked_in",state(id));
        var raw=db.one("SELECT * FROM usage_records WHERE reservation_id=?",id);assertEquals(base+"T10:02",raw.get("checkin_at"));assertEquals(base+"T10:05",raw.get("corrected_at"));assertEquals(raw.get("created_at"),raw.get("corrected_at"));assertEquals("admin_correction",raw.get("origin"));
        var owner=bookings.detail(student,id);String text=json.writeValueAsString(owner);assertTrue(text.contains("Public correction reason"));assertTrue(text.contains("admin_self_report"));for(String secret:List.of("S23_PRIVATE_TEST_ONLY","correction_note","correction_request","correction_operation"))assertFalse(text.contains(secret));
        assertTrue(json.writeValueAsString(bookings.correctionDetail(admin,id)).contains("S23_PRIVATE_TEST_ONLY"));
        at(10,12,0);bookings.action(student,id,"checkout");assertEquals("completed",state(id));assertEquals(600,((Number)usage.view(id,base.atTime(10,12)).get("duration_seconds")).intValue());assertEquals(1,db.count("SELECT count(*) FROM usage_records"));assertEquals(0,db.count("SELECT count(*) FROM violations"));
    }
    @Test void replayRemainsPureAfterDeadlineWhileDifferentIntentOrActorConflicts()throws Exception{
        long id=reservation(student,seat1);at(10,5,0);String op=UUID.randomUUID().toString();correct(id,op);String before=json.writeValueAsString(db.list("SELECT * FROM usage_records"));long audits=db.count("SELECT count(*) FROM audit_events"),notices=db.count("SELECT count(*) FROM notifications");at(12,0,0);
        assertEquals(true,correct(id,op).data().get("replayed"));assertEquals("checked_in",state(id));assertEquals(before,json.writeValueAsString(db.list("SELECT * FROM usage_records")));assertEquals(audits,db.count("SELECT count(*) FROM audit_events"));assertEquals(notices,db.count("SELECT count(*) FROM notifications"));
        assertEquals(409,assertThrows(Api.Failure.class,()->bookings.correctCheckin(admin,id,op,business(),"different","S23_PRIVATE_TEST_ONLY")).status);
    }
    @Test void exactDeadlineCommitsOrdinaryTimeoutInsteadOfBackdating()throws Exception{
        long id=reservation(student,seat1);at(10,15,0);assertEquals(409,correct(id,UUID.randomUUID().toString()).status());assertEquals("unused_ended",state(id));assertEquals(0,db.count("SELECT count(*) FROM usage_records"));assertEquals(1,db.count("SELECT count(*) FROM violations WHERE type='no_show'"));assertEquals(0,db.count("SELECT count(*) FROM audit_events WHERE action='reservation.correct_checkin'"));
    }
    @Test void finalMicrosecondBeforeDeadlineSucceedsAndFutureOrPrestartTimesReject(){
        long id=reservation(student,seat1),next=reservation(staff,seat2);clock.set(base.atTime(10,14,59,999999000));assertEquals(200,correct(id,UUID.randomUUID().toString()).status());
        assertEquals(400,assertThrows(Api.Failure.class,()->bookings.correctCheckin(admin,next,UUID.randomUUID().toString(),base+"T10:15:00+08:00","reason","note")).status);assertEquals(400,assertThrows(Api.Failure.class,()->bookings.correctCheckin(admin,next,UUID.randomUUID().toString(),base+"T09:59:59+08:00","reason","note")).status);
    }
    @Test void terminalAndAlreadyCheckedRecordsNeverReviveOrOverwrite(){
        long cancelled=reservation(student,seat1);bookings.action(student,cancelled,"cancel");at(10,5,0);assertEquals(409,correct(cancelled,UUID.randomUUID().toString()).status());assertEquals("cancelled",state(cancelled));
        at(9,0,0);long used=reservation(staff,seat2);String payload=code(seat2);at(10,0,0);bookings.checkin(staff,used,payload);at(10,5,0);assertEquals(409,correct(used,UUID.randomUUID().toString()).status());assertEquals("seat_code",usage.view(used,base.atTime(10,5)).get("origin"));
    }
    @Test void unavailableSeatAndDisabledSubjectRejectWithoutCorrection(){
        long id=reservation(student,seat1);db.update("UPDATE seats SET status='maintenance' WHERE id=?",seat1);at(10,5,0);assertEquals(409,correct(id,UUID.randomUUID().toString()).status());assertEquals("pending",state(id));db.update("UPDATE seats SET status='available' WHERE id=?",seat1);db.update("UPDATE users SET status='disabled' WHERE id=?",student);assertEquals(409,correct(id,UUID.randomUUID().toString()).status());assertEquals(0,db.count("SELECT count(*) FROM usage_records"));
    }
    @Test void overlappingOtherReservationRejectsWithoutDisplacingIt(){
        long id=reservation(student,seat1);long other=db.insert("INSERT INTO reservations(user_id,seat_id,res_date,start_time,end_time) VALUES(?,?,CAST(? AS DATE),'10:00','11:00')",staff,seat1,base.toString());at(10,5,0);assertEquals(409,correct(id,UUID.randomUUID().toString()).status());assertEquals("pending",state(id));assertEquals("pending",state(other));assertEquals(0,db.count("SELECT count(*) FROM usage_records"));
    }
    @Test void expiredOtherPendingIsNotAnEffectiveConflictAndGetIsPure()throws Exception{
        long id=reservation(student,seat1);long other=db.insert("INSERT INTO reservations(user_id,seat_id,res_date,start_time,end_time) VALUES(?,?,CAST(? AS DATE),'09:55','11:00')",staff,seat1,base.toString());at(10,12,0);long notices=db.count("SELECT count(*) FROM notifications"),audits=db.count("SELECT count(*) FROM audit_events");
        assertEquals(true,bookings.correctionDetail(admin,id).get("can_correct"));assertEquals("pending",state(other));assertEquals(0,db.count("SELECT count(*) FROM violations"));assertEquals(notices,db.count("SELECT count(*) FROM notifications"));assertEquals(audits,db.count("SELECT count(*) FROM audit_events"));assertEquals(200,correct(id,UUID.randomUUID().toString()).status());assertEquals("pending",state(other));
    }
    @Test void activeRestrictionCancelsPendingAndCannotBeBypassed(){
        long id=reservation(student,seat1);var startsAt=java.sql.Timestamp.valueOf(base.atTime(10,0));db.update("INSERT INTO restrictions(user_id,source,starts_at,ends_at,reason,creator_id,verification_note,facts_confirmed,created_at) VALUES(?,'manual',?,?, 'restriction',?,'basis',true,?)",student,startsAt,java.sql.Timestamp.valueOf(base.atTime(12,0)),admin,startsAt);at(10,5,0);assertEquals(409,correct(id,UUID.randomUUID().toString()).status());assertEquals("cancelled",state(id));assertEquals(0,db.count("SELECT count(*) FROM usage_records"));assertEquals(0,db.count("SELECT count(*) FROM violations"));
    }
    @Test void notificationAndAuditFailuresRollbackAllCorrectionFacts()throws Exception{
        long id=reservation(student,seat1);at(10,5,0);
        for(String table:List.of("notifications","audit_events")){String expression=table.equals("notifications")?"type<>'reservation_corrected_checkin'":"action<>'reservation.correct_checkin'";db.update("ALTER TABLE "+table+" ADD CONSTRAINT s23_fault CHECK("+expression+") NOT VALID");try{assertThrows(Exception.class,()->correct(id,UUID.randomUUID().toString()));assertEquals("pending",state(id));assertEquals(0,db.count("SELECT count(*) FROM usage_records"));assertEquals(0,db.count("SELECT count(*) FROM audit_events WHERE action='reservation.correct_checkin'"));assertEquals(0,db.count("SELECT count(*) FROM notifications WHERE type='reservation_corrected_checkin'"));}finally{db.update("ALTER TABLE "+table+" DROP CONSTRAINT s23_fault");}}
    }
    @Test void twoConcurrentAdminsHaveOneWinnerAndUniqueUsage()throws Exception{
        long id=reservation(student,seat1);at(10,5,0);var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);try{Callable<Integer> action=()->{gate.await();return correct(id,UUID.randomUUID().toString()).status();};long secondAdmin=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s23_admin2','hash','admin','S23-admin2')");var a=pool.submit(action);var b=pool.submit(()->{gate.await();return bookings.correctCheckin(secondAdmin,id,UUID.randomUUID().toString(),business(),"Public correction reason","S23_PRIVATE_TEST_ONLY").status();});gate.countDown();var statuses=new ArrayList<>(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS)));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);assertEquals(1,db.count("SELECT count(*) FROM usage_records"));assertEquals(1,db.count("SELECT count(*) FROM audit_events WHERE action='reservation.correct_checkin'"));}finally{pool.shutdownNow();}
    }
    @Test void concurrentSameOperationReturnsOneFactAndSuccessfulReplay()throws Exception{
        long id=reservation(student,seat1);at(10,5,0);String op=UUID.randomUUID().toString();var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);try{Callable<BookingService.ActionResult> action=()->{gate.await();return correct(id,op);};var a=pool.submit(action);var b=pool.submit(action);gate.countDown();var one=a.get(20,TimeUnit.SECONDS);var two=b.get(20,TimeUnit.SECONDS);assertEquals(200,one.status());assertEquals(200,two.status());assertNotEquals(one.data().get("replayed"),two.data().get("replayed"));assertEquals(1,db.count("SELECT count(*) FROM usage_records"));assertEquals(1,db.count("SELECT count(*) FROM notifications WHERE type='reservation_corrected_checkin'"));}finally{pool.shutdownNow();}
    }
    @Test void timeoutRaceHasOneLegalResultNeverResurrected()throws Exception{
        long id=reservation(student,seat1);at(10,15,0);var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->correct(id,UUID.randomUUID().toString()).status());var b=pool.submit(()->{bookings.tick();return 0;});assertEquals(409,a.get(20,TimeUnit.SECONDS));b.get(20,TimeUnit.SECONDS);assertEquals("unused_ended",state(id));assertEquals(0,db.count("SELECT count(*) FROM usage_records"));assertEquals(1,db.count("SELECT count(*) FROM violations"));}finally{pool.shutdownNow();}
    }
    @Test void httpPermissionAndExactInputTypesCannotSupplyMissingFacts()throws Exception{
        long id=reservation(student,seat1);String adminToken=token("admin"),studentToken=token("student");at(10,5,0);
        http.perform(auth(get("/api/admin/reservations/"+id+"/checkin-correction"),studentToken)).andExpect(status().isForbidden());http.perform(auth(post(path(id)),studentToken).contentType("application/json").content(json.writeValueAsString(body(UUID.randomUUID().toString())))).andExpect(status().isForbidden());
        for(String variant:List.of("false","extra","number","offset","empty")){var data=body(UUID.randomUUID().toString());switch(variant){case "false"->data.put("facts_confirmed",false);case "extra"->data.put("actor_id",student);case "number"->data.put("reason",1);case "offset"->data.put("checkin_at",base+"T10:02:00");case "empty"->data.put("verification_note"," ");}http.perform(auth(post(path(id)),adminToken).contentType("application/json").content(json.writeValueAsString(data))).andExpect(status().isBadRequest());}assertEquals(0,db.count("SELECT count(*) FROM usage_records"));
        db.update("UPDATE users SET status='disabled' WHERE id=?",admin);assertEquals(403,assertThrows(Api.Failure.class,()->correct(id,UUID.randomUUID().toString())).status);
    }
}
