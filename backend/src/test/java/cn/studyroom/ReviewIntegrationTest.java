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
class ReviewIntegrationTest {
    @TestConfiguration static class TimeConfig { @Bean @Primary MinuteBookingIntegrationTest.TestClock testClock() { var c=new MinuteBookingIntegrationTest.TestClock();c.set(LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1).atTime(9,0));return c; } }
    @Autowired ReviewService reviews;
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
        base=LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1);clock.set(base.atTime(9,0));remote="s24-test-"+UUID.randomUUID();
        String hash=new BCryptPasswordEncoder().encode("S02-test-only-password");
        admin=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s24_admin',?,'admin','S09-admin')",hash);
        student=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s24_student',?,'student','S09-student')",hash);
        staff=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s24_staff',?,'staff','S09-staff')",hash);
        room=db.insert("INSERT INTO rooms(name,seat_rows,seat_cols) VALUES('S10模拟空间',1,2)");opening.initializeDefaults(room);
        seat1=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'01',1,1)",room);seat2=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'02',1,2)",room);
    }
    @AfterEach void clearRate() { redis.delete("studyroom-java:rate:login:"+remote); }
    void at(int h,int m,int s) { clock.set(base.atTime(h,m,s)); }
    String token(String role) throws Exception {
        var result=http.perform(post("/api/auth/login").with(r->{r.setRemoteAddr(remote);return r;}).contentType("application/json").content(json.writeValueAsString(Map.of("username","s24_"+role,"password","S02-test-only-password")))).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }
    long reservation(long user,long seat) { return Store.id(bookings.create(user,new Requests.Reservation(seat,base.toString(),"10:00","11:00")),"id"); }
    String code(long seat) { codes.generate(admin,seat,new Requests.SeatCodeChange(0L,"测试实际生成座位码"));return current(seat); }
    String current(long seat) { return Store.text(db.one("SELECT code_value FROM seat_codes WHERE seat_id=?",seat),"code_value"); }
    String path(long seat) { return "/api/admin/seats/"+seat+"/code"; }
    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder req,String token) { return req.header("Authorization","Bearer "+token); }
    MockHttpServletRequestBuilder checkin(long id,String code,String token) throws Exception { return auth(post("/api/reservations/"+id+"/checkin"),token).contentType("application/json").content(json.writeValueAsString(Map.of("seat_code",code))); }
    Map<String,Object> row(long id) { return db.one("SELECT * FROM reservations WHERE id=?",id); }

    long completed(){long id=reservation(student,seat1);String payload=code(seat1);at(10,0,0);bookings.checkin(student,id,payload);at(10,5,0);bookings.action(student,id,"checkout");return id;}
    String op(){return UUID.randomUUID().toString();}
    @SuppressWarnings("unchecked") Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    Map<String,Object> create(long id,String operation){return reviews.save(student,id,"create",operation,4," useful study room ",null);}
    String reviewPath(long id){return "/api/reservations/"+id+"/review";}
    @Test void completedOwnerCreatesAndUpdatesOneReviewAndPreservesUsageFacts()throws Exception{
        long id=completed();String before=json.writeValueAsString(db.list("SELECT * FROM usage_records"));var result=create(id,op());var first=map(result.get("review"));assertEquals("useful study room",first.get("content"));assertEquals(1L,((Number)first.get("version")).longValue());assertEquals(Set.of("id","reservation_id","rating","content","version","created_at","updated_at"),first.keySet());
        var changed=map(reviews.save(student,id,"update",op(),5,"changed",1L).get("review"));assertEquals(first.get("id"),changed.get("id"));assertEquals(2L,((Number)changed.get("version")).longValue());assertEquals(1,db.count("SELECT count(*) FROM reviews"));assertEquals(2,db.count("SELECT count(*) FROM review_operations"));assertEquals(before,json.writeValueAsString(db.list("SELECT * FROM usage_records")));assertEquals(0,db.count("SELECT count(*) FROM violations"));
        assertEquals(409,assertThrows(Api.Failure.class,()->create(id,op())).status);assertEquals(409,assertThrows(Api.Failure.class,()->reviews.save(student,id,"update",op(),1,"stale",1L)).status);
    }
    @Test void pureReplayRetainsOriginalSnapshotAfterLaterUpdateAndNewExpiredBooking()throws Exception{
        long id=completed();String operation=op();var first=create(id,operation);reviews.save(student,id,"update",op(),5,"new",1L);at(9,0,0);bookings.create(student,new Requests.Reservation(seat2,base.toString(),"12:00","13:00"));at(12,15,0);String before=json.writeValueAsString(db.list("SELECT * FROM reservations ORDER BY id"));long audits=db.count("SELECT count(*) FROM audit_events");var replay=create(id,operation);assertEquals(true,replay.get("replayed"));assertEquals(json.readTree(json.writeValueAsString(first.get("review"))),json.readTree(json.writeValueAsString(replay.get("review"))));assertEquals(before,json.writeValueAsString(db.list("SELECT * FROM reservations ORDER BY id")));assertEquals(audits,db.count("SELECT count(*) FROM audit_events"));
        assertEquals(409,assertThrows(Api.Failure.class,()->reviews.save(student,id,"create",operation,3,"other",null)).status);assertEquals(409,assertThrows(Api.Failure.class,()->reviews.save(student,id,"update",operation,4,"useful study room",1L)).status);assertEquals(409,assertThrows(Api.Failure.class,()->reviews.save(staff,id,"create",operation,4,"useful study room",null)).status);
    }
    @Test void pendingUnusedCancelledAndUnfinishedUseCannotReview(){
        long id=reservation(student,seat1);assertEquals(false,map(reviews.detail(student,id).get("eligibility")).get("can_review"));assertEquals(409,assertThrows(Api.Failure.class,()->create(id,op())).status);bookings.action(student,id,"cancel");assertEquals(409,assertThrows(Api.Failure.class,()->create(id,op())).status);
        long active=reservation(student,seat2);String payload=code(seat2);at(10,0,0);bookings.checkin(student,active,payload);assertEquals(409,assertThrows(Api.Failure.class,()->create(active,op())).status);assertEquals(0,db.count("SELECT count(*) FROM reviews"));
    }
    @Test void getSettlesOrdinaryScheduledEndThenAllowsReviewWithoutNewPenalty(){
        long id=reservation(student,seat1);String payload=code(seat1);at(10,0,0);bookings.checkin(student,id,payload);at(11,0,0);assertEquals(true,map(reviews.detail(student,id).get("eligibility")).get("can_review"));assertEquals("completed",row(id).get("status"));create(id,op());assertEquals(0,db.count("SELECT count(*) FROM violations"));
    }
    @Test void resourceTerminatedSettledUsageAndCorrectedUsageRemainEligible(){
        long id=reservation(student,seat1);String payload=code(seat1);at(10,0,0);bookings.checkin(student,id,payload);at(10,5,0);transactions.executeWithoutResult(t->bookings.cancelForResource(admin,id,"test closure",base.atTime(10,5)));assertEquals(true,map(reviews.detail(student,id).get("eligibility")).get("can_review"));create(id,op());
        at(9,0,0);long corrected=reservation(student,seat2);at(10,5,0);bookings.correctCheckin(admin,corrected,op(),base+"T10:02:00+08:00","public","private");at(10,6,0);bookings.action(student,corrected,"checkout");create(corrected,op());assertEquals(2,db.count("SELECT count(*) FROM reviews"));
    }
    @Test void zeroDurationRealSettledUseHasNoArtificialMinimum(){
        long id=reservation(student,seat1);String payload=code(seat1);at(10,0,0);bookings.checkin(student,id,payload);bookings.action(student,id,"checkout");create(id,op());assertEquals(1,db.count("SELECT count(*) FROM reviews"));
    }
    @Test void authenticatedOwnerOnlyAndHttpRejectsEveryNonIntegerJsonShape()throws Exception{
        long id=completed();String owner=token("student"),other=token("staff"),manager=token("admin");for(String token:List.of(other,manager))http.perform(auth(get(reviewPath(id)),token)).andExpect(status().isForbidden());
        for(String rating:List.of("0","6","4.5","4.0","\"4\"","true","null","2147483648")){String payload="{\"operation_id\":\""+op()+"\",\"rating\":"+rating+",\"content\":\"text\"}";http.perform(auth(post(reviewPath(id)),owner).contentType("application/json").content(payload)).andExpect(status().isBadRequest());}
        for(var body:List.of(Map.of("operation_id",op(),"rating",4,"content"," "),Map.of("operation_id",op(),"rating",4,"content","x".repeat(2001)),Map.of("operation_id",op(),"rating",4,"content","text","user_id",student)))http.perform(auth(post(reviewPath(id)),owner).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        db.update("UPDATE users SET status='disabled' WHERE id=?",student);assertEquals(403,assertThrows(Api.Failure.class,()->create(id,op())).status);assertEquals(0,db.count("SELECT count(*) FROM reviews"));
    }
    @Test void missingUpdateAndNonIntegralVersionRejectWithoutAnyReview()throws Exception{
        long id=completed();assertEquals(404,assertThrows(Api.Failure.class,()->reviews.save(student,id,"update",op(),4,"text",1L)).status);String owner=token("student");for(String version:List.of("0","1.0","true","\"1\"","9223372036854775808")){String payload="{\"operation_id\":\""+op()+"\",\"rating\":4,\"content\":\"text\",\"version\":"+version+"}";http.perform(auth(put(reviewPath(id)),owner).contentType("application/json").content(payload)).andExpect(status().isBadRequest());}assertEquals(0,db.count("SELECT count(*) FROM reviews"));
    }
    @Test void concurrentCreatesHaveOneWinnerAndSameOperationReplays()throws Exception{
        long id=completed();var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);try{Callable<Integer> action=()->{gate.await();try{create(id,op());return 200;}catch(Api.Failure f){return f.status;}};var a=pool.submit(action);var b=pool.submit(action);gate.countDown();var statuses=new ArrayList<>(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS)));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);assertEquals(1,db.count("SELECT count(*) FROM reviews"));assertEquals(1,db.count("SELECT count(*) FROM review_operations"));}finally{pool.shutdownNow();}
    }
    @Test void directPostExpiredPendingCommitsOriginalTimeoutButCreatesNoReview()throws Exception{
        long id=reservation(student,seat1);String owner=token("student");at(10,15,0);http.perform(auth(post(reviewPath(id)),owner).contentType("application/json").content(json.writeValueAsString(Map.of("operation_id",op(),"rating",4,"content","text")))).andExpect(status().isConflict()).andExpect(jsonPath("$.details.blocked_by").value("no_usage"));assertEquals("unused_ended",row(id).get("status"));assertEquals(1,db.count("SELECT count(*) FROM violations WHERE type='no_show'"));assertEquals(0,db.count("SELECT count(*) FROM reviews"));
    }
    @Test void inconsistentReservationAndUsageNeverGrantEligibility(){
        long id=completed();db.update("UPDATE reservations SET ended_at=ended_at+INTERVAL '1 second' WHERE id=?",id);assertEquals("usage_inconsistent",map(reviews.detail(student,id).get("eligibility")).get("blocked_by"));assertEquals(409,assertThrows(Api.Failure.class,()->create(id,op())).status);db.update("UPDATE reservations SET ended_at=ended_at-INTERVAL '1 second',termination_reason='admin_resource_change' WHERE id=?",id);assertEquals("usage_inconsistent",map(reviews.detail(student,id).get("eligibility")).get("blocked_by"));assertEquals(0,db.count("SELECT count(*) FROM reviews"));
    }
    @Test void sameOperationRaceReplaysOneImmutableReceipt()throws Exception{
        long id=completed();String operation=op();var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->create(id,operation));var b=pool.submit(()->create(id,operation));var one=a.get(20,TimeUnit.SECONDS);var two=b.get(20,TimeUnit.SECONDS);assertNotEquals(one.get("replayed"),two.get("replayed"));assertEquals(json.readTree(json.writeValueAsString(one.get("review"))),json.readTree(json.writeValueAsString(two.get("review"))));assertEquals(1,db.count("SELECT count(*) FROM reviews"));assertEquals(1,db.count("SELECT count(*) FROM review_operations"));assertThrows(Exception.class,()->db.update("UPDATE review_operations SET action='update'"));}finally{pool.shutdownNow();}
    }
    @Test void snapshotOrAuditFailureRollsBackReviewAndDoesNotChangeUsage()throws Exception{
        long id=completed();String before=json.writeValueAsString(db.list("SELECT * FROM usage_records"));for(String table:List.of("review_operations","audit_events")){String expr=table.equals("review_operations")?"false":"action<>'review.create'";db.update("ALTER TABLE "+table+" ADD CONSTRAINT s24_fault CHECK("+expr+") NOT VALID");try{assertThrows(Exception.class,()->create(id,op()));assertEquals(0,db.count("SELECT count(*) FROM reviews"));assertEquals(0,db.count("SELECT count(*) FROM review_operations"));assertEquals(before,json.writeValueAsString(db.list("SELECT * FROM usage_records")));}finally{db.update("ALTER TABLE "+table+" DROP CONSTRAINT s24_fault");}}
    }
}
