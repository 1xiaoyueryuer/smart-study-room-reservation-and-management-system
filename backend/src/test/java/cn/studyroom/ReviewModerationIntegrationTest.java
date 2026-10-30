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
class ReviewModerationIntegrationTest {
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
        base=LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1);clock.set(base.atTime(9,0));remote="s25-test-"+UUID.randomUUID();
        String hash=new BCryptPasswordEncoder().encode("S02-test-only-password");
        admin=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s25_admin',?,'admin','S09-admin')",hash);
        student=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s25_student',?,'student','S09-student')",hash);
        staff=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s25_staff',?,'staff','S09-staff')",hash);
        room=db.insert("INSERT INTO rooms(name,seat_rows,seat_cols) VALUES('S10模拟空间',1,2)");opening.initializeDefaults(room);
        seat1=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'01',1,1)",room);seat2=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'02',1,2)",room);
    }
    @AfterEach void clearRate() { redis.delete("studyroom-java:rate:login:"+remote); }
    void at(int h,int m,int s) { clock.set(base.atTime(h,m,s)); }
    String token(String role) throws Exception {
        var result=http.perform(post("/api/auth/login").with(r->{r.setRemoteAddr(remote);return r;}).contentType("application/json").content(json.writeValueAsString(Map.of("username","s25_"+role,"password","S02-test-only-password")))).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }
    long reservation(long user,long seat) { return Store.id(bookings.create(user,new Requests.Reservation(seat,base.toString(),"10:00","11:00")),"id"); }
    String code(long seat) { codes.generate(admin,seat,new Requests.SeatCodeChange(0L,"测试实际生成座位码"));return current(seat); }
    String current(long seat) { return Store.text(db.one("SELECT code_value FROM seat_codes WHERE seat_id=?",seat),"code_value"); }
    String path(long seat) { return "/api/admin/seats/"+seat+"/code"; }
    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder req,String token) { return req.header("Authorization","Bearer "+token); }
    MockHttpServletRequestBuilder checkin(long id,String code,String token) throws Exception { return auth(post("/api/reservations/"+id+"/checkin"),token).contentType("application/json").content(json.writeValueAsString(Map.of("seat_code",code))); }
    Map<String,Object> row(long id) { return db.one("SELECT * FROM reservations WHERE id=?",id); }


    @SuppressWarnings("unchecked") Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") List<Map<String,Object>> rows(Object value){return (List<Map<String,Object>>)value;}
    String op(){return UUID.randomUUID().toString();}
    long review(){long id=reservation(student,seat1);String payload=code(seat1);at(10,0,0);bookings.checkin(student,id,payload);at(10,5,0);bookings.action(student,id,"checkout");return Store.id(map(reviews.save(student,id,"create",op(),4,"original private owner content",null).get("review")),"id");}
    Map<String,Object> moderate(long id,String action,long version,String operation){return reviews.moderate(admin,id,action,operation,version,action.equals("reply")?"management reply":"hidden reason");}
    @Test void replyHideKeepRatingContentAndHiddenOwnerEditsRemainHidden(){
        long id=review(),reservation=Store.id(db.one("SELECT reservation_id FROM reviews WHERE id=?",id),"reservation_id");var reply=map(moderate(id,"reply",1,op()).get("review"));assertEquals(4,reply.get("rating"));assertEquals("original private owner content",reply.get("content"));assertEquals("management reply",reply.get("admin_reply"));assertNotNull(reply.get("replied_at"));moderate(id,"hide",2,op());var owner=map(reviews.detail(student,reservation).get("review"));assertEquals(true,owner.get("hidden"));assertEquals("hidden reason",owner.get("hidden_reason"));var edited=map(reviews.save(student,reservation,"update",op(),5,"owner edit",3L).get("review"));assertEquals(true,edited.get("hidden"));assertEquals("hidden reason",edited.get("hidden_reason"));assertEquals("management reply",edited.get("admin_reply"));assertEquals(0L,reviews.roomReviews(staff,room,1,20).get("total"));assertEquals(2,db.count("SELECT count(*) FROM notifications WHERE review_operation_id IS NOT NULL"));
    }
    @Test void publicProjectionContainsExactlySixFieldsAndPaginationCountsOnlyVisible(){
        long id=review();moderate(id,"reply",1,op());var visible=reviews.roomReviews(staff,room,1,1);assertEquals(1L,visible.get("total"));var item=rows(visible.get("items")).get(0);assertEquals(Set.of("id","rating","content","updated_at","admin_reply","replied_at"),item.keySet());assertEquals(List.of(),reviews.roomReviews(staff,room,2,1).get("items"));
        var management=reviews.adminDetail(admin,id);assertEquals(student,map(management.get("subject")).get("id"));assertFalse(management.toString().contains("password"));assertEquals(1L,reviews.adminList(admin,1,20,room,false).get("total"));moderate(id,"hide",2,op());assertEquals(0L,reviews.roomReviews(staff,room,1,20).get("total"));assertEquals(1L,reviews.adminList(admin,1,20,null,true).get("total"));assertEquals(0L,reviews.adminList(admin,1,20,room,false).get("total"));
    }
    @Test void managementReplayIsPureAndPreservesFirstSnapshotAfterOwnerUpdate()throws Exception{
        long id=review(),reservation=Store.id(db.one("SELECT reservation_id FROM reviews WHERE id=?",id),"reservation_id");String operation=op();var first=moderate(id,"reply",1,operation);reviews.save(student,reservation,"update",op(),5,"new owner text",2L);long audits=db.count("SELECT count(*) FROM audit_events"),notices=db.count("SELECT count(*) FROM notifications");var replay=moderate(id,"reply",1,operation);assertEquals(true,replay.get("replayed"));assertEquals(json.readTree(json.writeValueAsString(first.get("review"))),json.readTree(json.writeValueAsString(replay.get("review"))));assertEquals(audits,db.count("SELECT count(*) FROM audit_events"));assertEquals(notices,db.count("SELECT count(*) FROM notifications"));assertEquals(409,assertThrows(Api.Failure.class,()->reviews.moderate(admin,id,"reply",operation,1,"different")).status);assertEquals(409,assertThrows(Api.Failure.class,()->reviews.moderate(admin,id,"hide",operation,1,"management reply")).status);
    }
    @Test void originalOwnerSuccessfulOperationRemainsPureAfterModeration()throws Exception{
        long id=review(),reservation=Store.id(db.one("SELECT reservation_id FROM reviews WHERE id=?",id),"reservation_id");String operation=op();var first=reviews.save(student,reservation,"update",operation,3,"owner before hide",1L);moderate(id,"hide",2,op());var replay=reviews.save(student,reservation,"update",operation,3,"owner before hide",1L);assertEquals(true,replay.get("replayed"));assertEquals(false,map(replay.get("review")).get("hidden"));assertEquals(true,map(reviews.detail(student,reservation).get("review")).get("hidden"));assertEquals(json.readTree(json.writeValueAsString(first.get("review"))),json.readTree(json.writeValueAsString(replay.get("review"))));
    }
    @Test void strictHttpPermissionsFieldsVersionsAndRequiredReason()throws Exception{
        long id=review();String manager=token("admin"),owner=token("student");String path="/api/admin/reviews/"+id;http.perform(auth(get(path),owner)).andExpect(status().isForbidden());http.perform(get("/api/rooms/"+room+"/reviews")).andExpect(status().isUnauthorized());
        for(String action:List.of("reply","hide")){String field=action.equals("reply")?"reply":"reason";var body=new LinkedHashMap<String,Object>(Map.of("operation_id",op(),"version",1,field,"valid"));http.perform(auth(post(path+"/"+action),owner).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isForbidden());body.put("rating",5);http.perform(auth(post(path+"/"+action),manager).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isBadRequest());body.remove("rating");body.put(field," ");http.perform(auth(post(path+"/"+action),manager).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isBadRequest());body.put(field,"valid");body.put("version",1.0);http.perform(auth(post(path+"/"+action),manager).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isBadRequest());}assertEquals(4,db.one("SELECT rating FROM reviews WHERE id=?",id).get("rating"));assertEquals(0,db.count("SELECT count(*) FROM notifications WHERE review_operation_id IS NOT NULL"));
        http.perform(auth(get("/api/admin/reviews?hidden=yes"),manager)).andExpect(status().isBadRequest());http.perform(auth(get("/api/admin/reviews?page=0"),manager)).andExpect(status().isBadRequest());http.perform(auth(get("/api/rooms/"+room+"/reviews?page_size=51"),owner)).andExpect(status().isBadRequest());
    }
    @Test void versionConflictsAndHiddenNewOperationDoNotOverwrite(){long id=review();moderate(id,"hide",1,op());assertEquals(409,assertThrows(Api.Failure.class,()->moderate(id,"reply",1,op())).status);assertEquals(409,assertThrows(Api.Failure.class,()->moderate(id,"hide",2,op())).status);assertNull(db.one("SELECT admin_reply FROM reviews WHERE id=?",id).get("admin_reply"));assertEquals(2L,Store.id(db.one("SELECT version FROM reviews WHERE id=?",id),"version"));}
    @Test void concurrentAdministratorAndOwnerVersionUpdatesHaveOneWinner()throws Exception{
        long id=review(),reservation=Store.id(db.one("SELECT reservation_id FROM reviews WHERE id=?",id),"reservation_id");var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);try{var a=pool.submit(()->{gate.await();try{moderate(id,"hide",1,op());return 200;}catch(Api.Failure f){return f.status;}});var b=pool.submit(()->{gate.await();try{reviews.save(student,reservation,"update",op(),5,"racing owner",1L);return 200;}catch(Api.Failure f){return f.status;}});gate.countDown();var statuses=new ArrayList<>(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS)));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);assertEquals(2L,Store.id(db.one("SELECT version FROM reviews WHERE id=?",id),"version"));}finally{pool.shutdownNow();}
    }
    @Test void notificationAndAuditFaultsRollBackReplyVersionOperationAndPreserveUsage()throws Exception{
        long id=review();String before=json.writeValueAsString(db.list("SELECT * FROM usage_records"));for(String table:List.of("notifications","audit_events")){String expr=table.equals("notifications")?"review_operation_id IS NULL":"action<>'review.reply'";db.update("ALTER TABLE "+table+" ADD CONSTRAINT s25_fault CHECK("+expr+") NOT VALID");try{assertThrows(Exception.class,()->moderate(id,"reply",1,op()));assertEquals(1L,Store.id(db.one("SELECT version FROM reviews WHERE id=?",id),"version"));assertNull(db.one("SELECT admin_reply FROM reviews WHERE id=?",id).get("admin_reply"));assertEquals(1,db.count("SELECT count(*) FROM review_operations"));assertEquals(before,json.writeValueAsString(db.list("SELECT * FROM usage_records")));}finally{db.update("ALTER TABLE "+table+" DROP CONSTRAINT s25_fault");}}
    }
    @Test void databaseRetainsOwnerGuardImmutableIdentityAndNotificationRecipientChain(){
        long id=review(),reservation=Store.id(db.one("SELECT reservation_id FROM reviews WHERE id=?",id),"reservation_id");assertThrows(Exception.class,()->db.update("INSERT INTO review_operations(operation_id,review_id,actor_id,reservation_id,action,request,receipt,occurred_at) VALUES(CAST(? AS UUID),?,?,?,'create','{}','{}',CURRENT_TIMESTAMP)",op(),id,staff,reservation));assertThrows(Exception.class,()->db.update("UPDATE reviews SET user_id=? WHERE id=?",staff,id));assertThrows(Exception.class,()->db.update("UPDATE review_operations SET receipt='{}'"));String operation=op();moderate(id,"reply",1,operation);assertThrows(Exception.class,()->db.update("INSERT INTO notifications(user_id,type,title,content,review_reservation_id,review_operation_id) VALUES(?,'review_replied','test','test',?,CAST(? AS UUID))",staff,reservation,operation));assertThrows(Exception.class,()->db.update("UPDATE notifications SET user_id=? WHERE review_operation_id=CAST(? AS UUID)",staff,operation));
    }
}
