package cn.studyroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
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
class ComplaintWorkflowIntegrationTest {
    @TestConfiguration static class TimeConfig { @Bean @Primary MinuteBookingIntegrationTest.TestClock testClock() { var c=new MinuteBookingIntegrationTest.TestClock();c.set(LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1).atTime(9,0));return c; } }
    @Autowired Store db;
    @Autowired BookingService bookings;
    @Autowired SeatCodeService codes;
    @Autowired OpeningScheduleService opening;
    @Autowired ResourceService resources;
    @Autowired ComplaintService complaints;
    @Autowired ComplaintManagementService management;
    @Autowired ComplaintWorkflowService workflow;
    @Autowired AttachmentService attachments;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired MinuteBookingIntegrationTest.TestClock clock;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    long admin,admin2,student,staff,other,room,seat1,seat2;
    LocalDate base;
    String remote;
    @BeforeEach void fixture() {
        assertEquals("studyroom_java_test",db.one("SELECT current_database() AS name").get("name"));
        TestDatabaseCleanup.clear(db);
        base=LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1);clock.set(base.atTime(9,0));remote="s14-test-"+UUID.randomUUID();
        String hash=new BCryptPasswordEncoder().encode("S02-test-only-password");
        admin=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s14_admin',?,'admin','S09-admin')",hash);
        admin2=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s14_admin2',?,'admin','S15-admin2')",hash);
        student=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s14_student',?,'student','S09-student')",hash);
        staff=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s14_staff',?,'staff','S09-staff')",hash);
        other=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s14_other',?,'student','S14-other')",hash);
        db.update("UPDATE users SET real_name='S15模拟管理员甲' WHERE id=?",admin);
        room=db.insert("INSERT INTO rooms(name,seat_rows,seat_cols) VALUES('S14模拟空间',1,2)");opening.initializeDefaults(room);
        seat1=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'01',1,1)",room);seat2=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'02',1,2)",room);
    }
    @AfterEach void clearRate() { redis.delete("studyroom-java:rate:login:"+remote); }
    void at(int h,int m,int s) { clock.set(base.atTime(h,m,s)); }
    String token(String role) throws Exception {
        var result=http.perform(post("/api/auth/login").with(r->{r.setRemoteAddr(remote);return r;}).contentType("application/json").content(json.writeValueAsString(Map.of("username","s14_"+role,"password","S02-test-only-password")))).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }
    long reservation(long user,long seat) { return Store.id(bookings.create(user,new Requests.Reservation(seat,base.toString(),"10:00","11:00")),"id"); }
    String code(long seat) { codes.generate(admin,seat,new Requests.SeatCodeChange(0L,"测试实际生成座位码"));return current(seat); }
    String current(long seat) { return Store.text(db.one("SELECT code_value FROM seat_codes WHERE seat_id=?",seat),"code_value"); }
    String path(long seat) { return "/api/admin/seats/"+seat+"/code"; }
    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder req,String token) { return req.header("Authorization","Bearer "+token); }
    MockHttpServletRequestBuilder checkin(long id,String code,String token) throws Exception { return auth(post("/api/reservations/"+id+"/checkin"),token).contentType("application/json").content(json.writeValueAsString(Map.of("seat_code",code))); }
    Map<String,Object> row(long id) { return db.one("SELECT * FROM reservations WHERE id=?",id); }


    String key(){return UUID.randomUUID().toString();}
    long submitted(){return Store.id(complaints.create(student,new Requests.Complaint("service",null,room,"S16工程模拟一般服务投诉",List.of(),key(),"other_service","环境卫生",null,null,null,null)),"id");}
    long processing(){long id=submitted();management.accept(admin,id,new Requests.ComplaintAccept(1L,key(),"工程模拟初步安排：管理员将核对服务问题并说明后续处理。",null,null,null,null));return id;}
    Requests.ComplaintFeedback feedbackRequest(long id){return new Requests.ComplaintFeedback(version(id),key(),"工程模拟处理结果：已核查并说明处理情况","工程模拟公开依据，不复制私有核实身份资料");}
    long awaiting(){long id=processing();workflow.feedback(admin,id,feedbackRequest(id));return id;}
    long version(long id){return Store.id(db.one("SELECT version FROM complaints WHERE id=?",id),"version");}
    String state(long id){return Store.text(db.one("SELECT status FROM complaints WHERE id=?",id),"status");}
    LocalDateTime deadline(long id){return LocalDateTime.parse(Store.text(db.one("SELECT feedback_deadline FROM complaint_events WHERE complaint_id=? AND event_type='feedback' ORDER BY id DESC LIMIT 1",id),"feedback_deadline"));}
    Requests.ComplaintResponse response(long id,String action){return new Requests.ComplaintResponse(version(id),key(),action.equals("object")?"工程模拟异议：所述处理结果仍有疑问，请继续核查":null);}
    String facts(){var all=new LinkedHashMap<String,Object>();for(String table:List.of("complaints","complaint_events","notifications","audit_events","reminders","violations","credit_logs","reservations","usage_records"))all.put(table,db.list("SELECT to_jsonb(t)::text AS raw FROM "+table+" t ORDER BY to_jsonb(t)::text"));return all.toString();}
    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder req,Object payload,String token)throws Exception{if(payload instanceof Requests.ComplaintResponse r&&r.description()==null)payload=Map.of("version",r.version(),"operation_id",r.operationId());return auth(req,token).contentType("application/json").content(json.writeValueAsString(payload));}
    String path(long id,String action){return "/api/complaints/"+id+"/"+action;}
    String feedbackPath(long id){return "/api/admin/complaints/"+id+"/feedback";}
    @Test void feedbackStoresResultBasisAndFixedSevenDayFactWithNotificationAndSafeProjection()throws Exception{
        String owner=token("student"),adminToken=token("admin");long id=processing();clock.set(base.atTime(10,3,4,123456000));var request=feedbackRequest(id);
        http.perform(body(post(feedbackPath(id)),request,adminToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.complaint.status").value("awaiting_confirmation")).andExpect(jsonPath("$.data.complaint.current_feedback.result").value(request.result())).andExpect(jsonPath("$.data.complaint.current_feedback.basis").value(request.basis())).andExpect(jsonPath("$.data.complaint.current_feedback.deadline").value(base.plusDays(7)+"T10:03:04.123456+08:00"));
        http.perform(auth(get("/api/complaints/"+id),owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.allowed_actions[0]").value("confirm")).andExpect(jsonPath("$.data.allowed_actions[1]").value("object")).andExpect(jsonPath("$.data.current_feedback.response_type").isEmpty()).andExpect(jsonPath("$.data.events[2].type").value("feedback"));
        var note=db.one("SELECT * FROM notifications WHERE type='complaint_feedback'");assertEquals(id,Store.id(note,"complaint_id"));assertEquals(student,Store.id(note,"user_id"));assertFalse(note.toString().contains("PRIVATE"));assertEquals(1,db.count("SELECT count(*) FROM audit_events WHERE action='complaint.feedback'"));assertEquals(0,db.count("SELECT count(*) FROM violations"));
    }
    @Test void submittedWithdrawalIsTerminalIdempotentAndCannotWithdrawAfterAcceptance(){
        long id=submitted();var request=response(id,"withdraw");var result=workflow.action(student,id,"withdraw",request);assertEquals(200,result.status());assertEquals("withdrawn",state(id));String before=facts();assertEquals(true,workflow.action(student,id,"withdraw",request).data().get("replayed"));assertEquals(before,facts());assertEquals(409,assertThrows(Api.Failure.class,()->management.accept(admin,id,new Requests.ComplaintAccept(version(id),key()))).status);long handled=processing();assertEquals(409,assertThrows(Api.Failure.class,()->workflow.action(student,handled,"withdraw",response(handled,"withdraw"))).status);
    }
    @Test void confirmAndObjectAreAllowedImmediatelyBeforeDeadlineAndTaskCannotOverwriteThem(){
        long confirm=awaiting(),object=awaiting();clock.set(deadline(confirm).minusNanos(1000));assertEquals(200,workflow.action(student,confirm,"confirm",response(confirm,"confirm")).status());assertEquals(200,workflow.action(student,object,"object",response(object,"object")).status());assertEquals("closed",state(confirm));assertEquals("processing",state(object));clock.set(deadline(confirm));assertEquals(0,workflow.settleExpired());assertEquals("confirmed",workflow.detail(student,confirm).get("closure_type"));assertEquals(admin,Store.id(db.one("SELECT handler_id FROM complaints WHERE id=?",object),"handler_id"));assertEquals(0,db.count("SELECT count(*) FROM complaint_events WHERE event_type='auto_closed'"));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"confirm","object"})
    void exactlyAtDeadlineActionCommitsAutoClosureThenHttp409WithoutFakeUserConfirmation(String action)throws Exception{
        String owner=token("student");long id=awaiting();var request=response(id,action);clock.set(deadline(id));
        http.perform(body(post(path(id,action)),request,owner)).andExpect(status().isConflict()).andExpect(jsonPath("$.details.status").value("closed")).andExpect(jsonPath("$.details.closure_type").value("auto_closed")).andExpect(jsonPath("$.details.complaint_id").value(id));assertEquals("closed",state(id));assertEquals(1,db.count("SELECT count(*) FROM complaint_events WHERE event_type='auto_closed'"));assertEquals(0,db.count("SELECT count(*) FROM complaint_events WHERE event_type IN('confirmed','objected')"));String before=facts();http.perform(body(post(path(id,action)),request,owner)).andExpect(status().isConflict());assertEquals(before,facts());
    }
    @Test void delayedAutoClosureKeepsOriginalDeadlineOccurrenceAndActualRegistrationTime(){
        long reservation=reservation(student,seat1);long id=Store.id(complaints.create(student,new Requests.Complaint("occupation",reservation,null,"S16工程模拟关联预约，不延长或结算预约",List.of(),key())),"id");management.accept(admin,id,new Requests.ComplaintAccept(1L,key()));workflow.feedback(admin,id,feedbackRequest(id));String original=row(reservation).toString();var due=deadline(id);var actual=due.plusHours(2).plusNanos(123456000);clock.set(actual);var result=workflow.detail(student,id);workflow.managementDetail(admin,id);assertEquals(original,row(reservation).toString());assertEquals(0,db.count("SELECT count(*) FROM usage_records"));assertEquals(0,db.count("SELECT count(*) FROM violations"));assertEquals("closed",result.get("status"));assertEquals(actual.atZone(BookingPolicy.CAMPUS_ZONE).toOffsetDateTime().toString(),result.get("closed_at"));var event=db.one("SELECT * FROM complaint_events WHERE event_type='auto_closed'");assertEquals(due.toString(),event.get("occurred_at"));assertEquals(actual.toString(),event.get("created_at"));assertNull(event.get("actor_id"));assertEquals("system",event.get("source"));assertNull(event.get("operation_id"));
    }
    @Test void allFourHttpReadEntrypointsConvergeExpiredFactsAndExposeSystemEvent()throws Exception{
        String owner=token("student"),adminToken=token("admin");for(String mode:List.of("owner_detail","owner_mine","admin_detail","admin_list")){clock.set(base.atTime(9,0));long id=awaiting();clock.set(deadline(id));String path=switch(mode){case "owner_detail"->"/api/complaints/"+id;case "owner_mine"->"/api/complaints/mine";case "admin_detail"->"/api/admin/complaints/"+id;default->"/api/admin/complaints?status=closed";};String result=http.perform(auth(get(path),mode.startsWith("owner")?owner:adminToken)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);assertEquals("closed",state(id));assertTrue(result.contains("closed"));if(mode.endsWith("detail")){assertTrue(result.contains("auto_closed"));assertTrue(result.contains("system"));} }
        assertEquals(4,db.count("SELECT count(*) FROM complaint_events WHERE event_type='auto_closed'"));String before=facts();workflow.mine(student);workflow.managementList(admin,null,null,null);workflow.settleExpired();assertEquals(before,facts());
    }
    @Test void objectionThenNewFeedbackStartsNewSevenDayRoundAndOldDeadlineCannotCloseIt(){
        long id=awaiting();long first=Store.id(db.one("SELECT id FROM complaint_events WHERE event_type='feedback'"),"id");var firstDeadline=deadline(id);clock.set(base.plusDays(1).atTime(9,0));workflow.action(student,id,"object",response(id,"object"));var secondRequest=feedbackRequest(id);workflow.feedback(admin2,id,secondRequest);assertEquals(base.plusDays(8).atTime(9,0),deadline(id));clock.set(firstDeadline);assertEquals(0,workflow.settleExpired());assertEquals("awaiting_confirmation",state(id));assertEquals(2,db.count("SELECT count(*) FROM complaint_events WHERE event_type='feedback'"));assertEquals(first,Store.id(db.one("SELECT related_feedback_id FROM complaint_events WHERE event_type='objected'"),"related_feedback_id"));clock.set(deadline(id));assertEquals(1,workflow.settleExpired());assertEquals(1,db.count("SELECT count(*) FROM complaint_events WHERE event_type='auto_closed'"));assertEquals(1,db.count("SELECT count(*) FROM notifications WHERE complaint_id=? AND type='complaint_initial_arrangement'",id));assertEquals(3,db.count("SELECT count(*) FROM notifications WHERE complaint_id=? AND type IN('complaint_feedback','complaint_auto_closed')",id));
    }
    @Test void feedbackAndResponseSuccessReplayPrecedesChangedStateVersionAndDeadline(){
        long id=processing();var feedback=feedbackRequest(id);var first=workflow.feedback(admin,id,feedback);clock.set(base.plusDays(1).atTime(9,0));var object=response(id,"object");var done=workflow.action(student,id,"object",object);workflow.feedback(admin2,id,feedbackRequest(id));clock.set(base.plusDays(10).atTime(9,0));String before=facts();db.update("ALTER TABLE audit_events ADD CONSTRAINT s16_replay_guard CHECK(action<>'complaint.auto_close') NOT VALID");try{assertEquals(true,workflow.feedback(admin,id,feedback).get("replayed"));assertEquals(true,workflow.action(student,id,"object",object).data().get("replayed"));assertEquals(before,facts());}finally{db.update("ALTER TABLE audit_events DROP CONSTRAINT s16_replay_guard");}assertEquals("awaiting_confirmation",state(id));workflow.detail(student,id);assertEquals("closed",state(id));assertEquals(true,workflow.action(student,id,"object",object).data().get("replayed"));assertEquals(done.data().get("event_id"),workflow.action(student,id,"object",object).data().get("event_id"));assertEquals(first.get("event_id"),workflow.feedback(admin,id,feedback).get("event_id"));assertEquals(2,db.count("SELECT count(*) FROM complaint_events WHERE event_type='feedback'"));assertEquals(1,db.count("SELECT count(*) FROM complaint_events WHERE event_type='objected'"));
    }
    @Test void operationKeysBindActorActionTargetAndNormalizedBody(){
        long id=processing();var r=feedbackRequest(id);workflow.feedback(admin,id,r);assertEquals(409,assertThrows(Api.Failure.class,()->workflow.feedback(admin2,id,r)).status);assertEquals(409,assertThrows(Api.Failure.class,()->workflow.feedback(admin,id,new Requests.ComplaintFeedback(r.version(),r.operationId(),"different",r.basis()))).status);long second=processing();assertEquals(409,assertThrows(Api.Failure.class,()->workflow.feedback(admin,second,r)).status);assertEquals(409,assertThrows(Api.Failure.class,()->workflow.action(student,id,"confirm",new Requests.ComplaintResponse(version(id),r.operationId(),null))).status);
    }
    @Test void feedbackOnlyProcessingAndNoTerminalReopeningOrAwaitingOverwrite(){
        long raw=submitted();assertEquals(409,assertThrows(Api.Failure.class,()->workflow.feedback(admin,raw,feedbackRequest(raw))).status);long id=awaiting();assertEquals(409,assertThrows(Api.Failure.class,()->workflow.feedback(admin,id,feedbackRequest(id))).status);workflow.action(student,id,"confirm",response(id,"confirm"));assertEquals(409,assertThrows(Api.Failure.class,()->workflow.feedback(admin,id,feedbackRequest(id))).status);assertEquals(409,assertThrows(Api.Failure.class,()->management.verify(admin,id,new Requests.ComplaintVerification(version(id),key(),"service_verified",base+"T08:00:00+08:00","public","private",null,false,null,true,false))).status);
    }
    @Test void otherOwnerCannotTriggerClosureOrAccessBeforeAuthorization()throws Exception{
        String owner=token("student"),foreign=token("other"),adminToken=token("admin");long id=awaiting();clock.set(deadline(id));String before=facts();http.perform(body(post(path(id,"confirm")),response(id,"confirm"),foreign)).andExpect(status().isForbidden());http.perform(auth(get("/api/complaints/"+id),foreign)).andExpect(status().isForbidden());assertEquals(before,facts());http.perform(get("/api/complaints/mine")).andExpect(status().isUnauthorized());http.perform(body(post(feedbackPath(id)),feedbackRequest(id),owner)).andExpect(status().isForbidden());http.perform(body(post(path(id,"confirm")),response(id,"confirm"),adminToken)).andExpect(status().isForbidden());assertEquals(before,facts());
    }
    @Test void concurrentTaskReadAndAtDeadlineResponseCreateOneSystemEventAndNotification()throws Exception{
        long id=awaiting();clock.set(deadline(id));var barrier=new CyclicBarrier(3);var pool=Executors.newFixedThreadPool(3);try{var jobs=List.of(pool.submit(()->{barrier.await();return workflow.settleExpired();}),pool.submit(()->{barrier.await();return workflow.detail(student,id);}),pool.submit(()->{barrier.await();try{return workflow.action(student,id,"object",response(id,"object"));}catch(Api.Failure e){return e.status;}}));for(var job:jobs)job.get(20,TimeUnit.SECONDS);}finally{pool.shutdownNow();}assertEquals("closed",state(id));assertEquals(1,db.count("SELECT count(*) FROM complaint_events WHERE event_type='auto_closed'"));assertEquals(1,db.count("SELECT count(*) FROM notifications WHERE type='complaint_auto_closed'"));assertEquals(1,db.count("SELECT count(*) FROM audit_events WHERE action='complaint.auto_close'"));
    }
    @Test void pendingWithdrawAndAdminAcceptRaceHasOneLegalWinner()throws Exception{
        long id=submitted();var request=response(id,"withdraw");var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->{barrier.await();try{return workflow.action(student,id,"withdraw",request).status();}catch(Api.Failure e){return e.status;}});var b=pool.submit(()->{barrier.await();try{management.accept(admin,id,new Requests.ComplaintAccept(1L,key(),"工程模拟初步安排：管理员将核对服务问题并说明后续处理。",null,null,null,null));return 200;}catch(Api.Failure e){return e.status;}});var result=new ArrayList<>(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS)));Collections.sort(result);assertEquals(List.of(200,409),result);}finally{pool.shutdownNow();}assertEquals(1,db.count("SELECT count(*) FROM complaint_events WHERE event_type IN('accepted','withdrawn')"));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"event","state","notification","audit"})
    void feedbackFailureRollsBackPhaseFactNotificationAndAudit(String layer)throws Exception{
        String token=token("admin");long id=processing();var request=feedbackRequest(id);String before=facts();String table=switch(layer){case "event"->"complaint_events";case "state"->"complaints";case "notification"->"notifications";default->"audit_events";};String condition=switch(layer){case "event"->"event_type<>'feedback'";case "state"->"status<>'awaiting_confirmation'";case "notification"->"type<>'complaint_feedback'";default->"action<>'complaint.feedback'";};db.update("ALTER TABLE "+table+" ADD CONSTRAINT s16_fail CHECK("+condition+") NOT VALID");try{http.perform(body(post(feedbackPath(id)),request,token)).andExpect(status().isInternalServerError());assertEquals(before,facts());}finally{db.update("ALTER TABLE "+table+" DROP CONSTRAINT s16_fail");}
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"event","notification","audit"})
    void autoClosureReadFailureRollsBackAndCanRetryWithoutFakeClosedState(String layer)throws Exception{
        String token=token("student");long id=awaiting();clock.set(deadline(id));String before=facts();String table=layer.equals("event")?"complaint_events":layer.equals("notification")?"notifications":"audit_events",condition=layer.equals("event")?"event_type<>'auto_closed'":layer.equals("notification")?"type<>'complaint_auto_closed'":"action<>'complaint.auto_close'";db.update("ALTER TABLE "+table+" ADD CONSTRAINT s16_fail CHECK("+condition+") NOT VALID");try{http.perform(auth(get("/api/complaints/"+id),token)).andExpect(status().isInternalServerError());assertEquals(before,facts());}finally{db.update("ALTER TABLE "+table+" DROP CONSTRAINT s16_fail");}http.perform(auth(get("/api/complaints/"+id),token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("closed"));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"withdraw","confirm","object"})
    void ownerActionAuditFailureCannotPersistItsEventOrPhase(String action)throws Exception{
        String token=token("student");long id=action.equals("withdraw")?submitted():awaiting();var request=response(id,action);String before=facts();db.update("ALTER TABLE audit_events ADD CONSTRAINT s16_fail CHECK(action<>'complaint."+action+"') NOT VALID");try{http.perform(body(post(path(id,action)),request,token)).andExpect(status().isInternalServerError());assertEquals(before,facts());}finally{db.update("ALTER TABLE audit_events DROP CONSTRAINT s16_fail");}
    }
    @Test void invalidFieldsAndStaleVersionDoNotWriteAndPublicHistoryRemainsWhitelisted()throws Exception{
        String adminToken=token("admin"),owner=token("student");long id=processing();var r=feedbackRequest(id);var raw=new LinkedHashMap<String,Object>();raw.put("version",r.version());raw.put("operation_id",r.operationId());raw.put("result",r.result());raw.put("basis",r.basis());for(String field:List.of("handler_id","actor_id","status","deadline")){var bad=new LinkedHashMap<>(raw);bad.put(field,"bad");http.perform(body(post(feedbackPath(id)),bad,adminToken)).andExpect(status().isBadRequest());}var fractional=new LinkedHashMap<>(raw);fractional.put("version",2.5);http.perform(body(post(feedbackPath(id)),fractional,adminToken)).andExpect(status().isBadRequest());assertEquals(400,assertThrows(Api.Failure.class,()->workflow.feedback(admin,id,new Requests.ComplaintFeedback(version(id),key()," ","basis"))).status);workflow.feedback(admin,id,r);assertEquals(409,assertThrows(Api.Failure.class,()->workflow.action(student,id,"object",new Requests.ComplaintResponse(1L,key(),"old version"))).status);assertEquals(400,assertThrows(Api.Failure.class,()->workflow.action(student,id,"object",new Requests.ComplaintResponse(version(id),key()," "))).status);http.perform(body(post(path(id,"object")),response(id,"object"),owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.complaint.status").value("processing")).andExpect(jsonPath("$.data.complaint.events[3].description").value("工程模拟异议：所述处理结果仍有疑问，请继续核查")).andExpect(jsonPath("$.data.complaint.events[3].private_note").doesNotExist());
    }
}
