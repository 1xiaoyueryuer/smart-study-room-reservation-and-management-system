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
class ComplaintTargetIntegrationTest {
    @TestConfiguration static class TimeConfig { @Bean @Primary MinuteBookingIntegrationTest.TestClock testClock() { var c=new MinuteBookingIntegrationTest.TestClock();c.set(LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1).atTime(9,0));return c; } }
    @Autowired Store db;
    @Autowired BookingService bookings;
    @Autowired SeatCodeService codes;
    @Autowired OpeningScheduleService opening;
    @Autowired ResourceService resources;
    @Autowired ComplaintService complaints;
    @Autowired ComplaintManagementService management;
    @Autowired ComplaintWorkflowService workflow;
    @Autowired ComplaintTargetService targets;
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
    long historical(){clock.set(base.atTime(0,0));long id=reservation(student,seat1);clock.set(base.atTime(0,1));bookings.action(student,id,"cancel");return id;}
    long occupation(long reservation){return Store.id(complaints.create(student,new Requests.Complaint("occupation",reservation,null,"S17工程模拟被占求助，不代表现场事实已核实",List.of(),key())),"id");}
    Requests.Complaint request(String operation){return new Requests.Complaint("service",null,room,"S32工程模拟其他服务问题",List.of(),operation,"other_service","环境卫生",null,null,null,null);}
    long service(){return Store.id(complaints.create(student,request(key())),"id");}
    @SuppressWarnings("unchecked") Map<String,Object> target(long id){return (Map<String,Object>)complaints.detail(student,id).get("service_target");}
    LocalDateTime due(long id){return LocalDateTime.parse(Store.text(db.one("SELECT due_at FROM complaint_targets WHERE complaint_id=?",id),"due_at"));}
    long roomVersion(){return Store.id(resources.room(room),"version");}
    void week(List<Requests.OpeningWindow> windows){for(int weekday=1;weekday<=7;weekday++)opening.save(admin,room,new Requests.OpeningChange(weekday,null,windows,roomVersion(),"S17工程模拟每周开放安排"));}
    void date(LocalDate date,List<Requests.OpeningWindow> windows){opening.save(admin,room,new Requests.OpeningChange(null,date.toString(),windows,roomVersion(),"S17工程模拟日期例外"));}
    void block(Long seat,LocalDateTime start,LocalDateTime end){opening.saveBlock(admin,room,null,new Requests.Unavailability(seat,start.toString(),end.toString(),roomVersion(),"S17工程模拟临时停用"));}
    void enabled(boolean value){var r=resources.room(room);resources.save(admin,room,new Requests.Room(Store.text(r,"name"),Store.text(r,"location"),null,null,1,2,null,null,null,value,Store.id(r,"version"),value?"S17工程模拟恢复空间":"S17工程模拟空间停用"));}
    String facts(){var result=new LinkedHashMap<String,Object>();for(String table:List.of("complaints","complaint_events","complaint_targets","complaint_target_events","notifications","audit_events","violations","credit_logs","reminders","reservations","usage_records"))result.put(table,db.list("SELECT to_jsonb(t)::text AS raw FROM "+table+" t ORDER BY to_jsonb(t)::text"));return result.toString();}
    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder req,Object value,String token)throws Exception{return auth(req,token).contentType("application/json").content(json.writeValueAsString(value));}
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"07:59:59.999999","08:00:00","21:59:59.999999","22:00:00"})
    void exactOpeningEdgesAndCrossClosureUseFifteenElapsedMinutes(String time){
        long history=historical();var now=base.atTime(LocalTime.parse(time));clock.set(now);long id=occupation(history);LocalDateTime anchor=time.startsWith("07:")?base.atTime(8,0):time.startsWith("22:")?base.plusDays(1).atTime(8,0):now;var result=target(id);assertEquals(anchor.atZone(BookingPolicy.CAMPUS_ZONE).toOffsetDateTime().toString(),result.get("anchor_at"));assertEquals(anchor.plusMinutes(15),due(id));assertEquals(anchor.equals(now)?"open_at_submission":"next_room_opening",result.get("basis"));assertEquals("running",result.get("status"));assertEquals("acceptance",result.get("kind"));assertEquals(0,db.count("SELECT count(*) FROM complaint_target_events"));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"00:00:00","23:59:59.999999"})
    void FullDayWindowSupportsMidnightAndEnd24WhileTargetMayCrossDay(String text){long history=historical();date(base,List.of(new Requests.OpeningWindow("00:00","24:00")));var now=base.atTime(LocalTime.parse(text));clock.set(now);long id=occupation(history);assertEquals(now.plusMinutes(15),due(id));assertEquals(now.atZone(BookingPolicy.CAMPUS_ZONE).toOffsetDateTime().toString(),target(id).get("anchor_at"));}
    @Test void crossDayExceptionAndRoomBlockUnionUseRoomLevelNotSeatState(){
        long history=historical();block(null,base.atTime(8,0),base.atTime(10,0));block(null,base.atTime(9,0),base.atTime(12,0));block(seat1,base.atTime(12,0),base.atTime(16,0));var seat=db.one("SELECT * FROM seats WHERE id=?",seat1);resources.updateSeat(admin,seat1,new Requests.Seat(Store.text(seat,"zone"),(Boolean)seat.get("has_power"),(Boolean)seat.get("near_window"),"maintenance",Store.text(seat,"seat_no"),1,1,Store.id(seat,"version"),"S17工程模拟单座位维护不改变空间开放目标"));clock.set(base.atTime(9,0));long id=occupation(history);assertEquals(base.atTime(12,15),due(id));assertEquals(base.atTime(12,0).atZone(BookingPolicy.CAMPUS_ZONE).toOffsetDateTime().toString(),target(id).get("anchor_at"));
        date(base.plusDays(1),List.of());date(base.plusDays(2),List.of(new Requests.OpeningWindow("12:00","13:00")));clock.set(base.atTime(23,0));long exception=occupation(history);assertEquals(base.plusDays(2).atTime(12,15),due(exception));
    }
    @Test void finiteFutureExceptionBeyond365DaysHasNoArtificialSearchCap(){long history=historical();week(List.of());LocalDate far=base.plusDays(1500);date(far,List.of(new Requests.OpeningWindow("08:00","09:00")));clock.set(base.atTime(9,0));long id=occupation(history);assertEquals(far.atTime(8,15),due(id));}
    @Test void thousandYearRoomBlockJumpsToItsUnionEndWithoutDailyLoop(){long history=historical();var far=base.plusYears(1000).atTime(8,0);block(null,base.atStartOfDay(),far);block(null,far.minusDays(1),far.plusHours(1));clock.set(base.atTime(9,0));var calendar=opening.calendar(room);assertTimeoutPreemptively(Duration.ofSeconds(2),()->assertEquals(far.plusHours(1),calendar.nextOpening(base.atTime(9,0))));long id=occupation(history);assertEquals(far.plusHours(1).plusMinutes(15),due(id));}
    @Test void unknownNextOpeningIsFrozenAndCompletionHasNoInventedOnTimeResult(){long history=historical();week(List.of());clock.set(base.atTime(9,0));long id=occupation(history);var target=target(id);assertEquals("pending",target.get("status"));assertNull(target.get("anchor_at"));assertNull(target.get("due_at"));assertFalse(Store.text(target,"reason").isBlank());week(List.of(new Requests.OpeningWindow("08:00","22:00")));assertNull(target(id).get("due_at"));management.accept(admin,id,new Requests.ComplaintAccept(1L,key()));assertEquals("completed",target(id).get("status"));assertEquals("pending",target(id).get("calculation_status"));assertNull(target(id).get("was_late"));clock.set(base.plusDays(20).atTime(9,0));assertEquals(0,targets.settle());}
    @Test void disabledRoomStaysPendingDespiteFutureScheduleAndWithdrawalStopsWithoutCompletion(){long history=historical();date(base.plusDays(5),List.of(new Requests.OpeningWindow("08:00","22:00")));enabled(false);clock.set(base.atTime(9,0));long id=occupation(history);assertEquals("pending",target(id).get("status"));enabled(true);assertNull(target(id).get("due_at"));workflow.action(student,id,"withdraw",new Requests.ComplaintResponse(1L,key(),null));assertEquals("withdrawn",target(id).get("status"));assertNull(target(id).get("was_late"));assertNotNull(target(id).get("stopped_at"));assertEquals(0,db.count("SELECT count(*) FROM complaint_target_events"));}
    @Test void targetSnapshotDoesNotShiftWhenResourcesChange(){long history=historical();clock.set(base.atTime(9,0));long id=occupation(history);var original=db.one("SELECT * FROM complaint_targets WHERE complaint_id=?",id).toString();long version=roomVersion();week(List.of(new Requests.OpeningWindow("12:00","22:00")));clock.set(base.atTime(9,16));workflow.detail(student,id);assertEquals(original,db.one("SELECT * FROM complaint_targets WHERE complaint_id=?",id).toString());assertEquals(version,Store.id(target(id),"room_version"));assertEquals(base.atTime(9,15),due(id));assertEquals("overdue",target(id).get("status"));}
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"09:14:59.999999","09:15:00","09:17:00"})
    void acceptanceAtDeadlineRecordsOverdueBeforeCompletionAndThenStops(String at){long history=historical();clock.set(base.atTime(9,0));long id=occupation(history);clock.set(base.atTime(LocalTime.parse(at)));management.accept(admin,id,new Requests.ComplaintAccept(1L,key()));boolean late=!at.startsWith("09:14");assertEquals(late?"late_completed":"completed",target(id).get("status"));assertEquals(late,target(id).get("was_late"));assertEquals(late?1:0,db.count("SELECT count(*) FROM complaint_target_events"));String before=facts();clock.set(base.plusDays(3).atTime(9,0));targets.settle();assertEquals(before,facts());}
    @Test void openHoursTargetCompletesOnAcceptanceNotFeedbackAndFeedbackCycleStaysIndependent(){clock.set(base.atTime(9,0,0,123456000));long id=service();var expected=base.plusDays(1).atTime(19,0,0,123456000);assertEquals(expected,due(id));assertEquals("acceptance",target(id).get("kind"));clock.set(base.atTime(11,0));management.accept(admin,id,new Requests.ComplaintAccept(1L,key(),"工程模拟初步安排：先核对投诉并说明后续处理。",null,null,null,null));assertEquals("completed",target(id).get("status"));var completed=target(id).get("completed_at");management.verify(admin,id,new Requests.ComplaintVerification(2L,key(),"service_verified",base+"T10:00:00+08:00","public verification","private verification",null,false,null,true,false));assertEquals(completed,target(id).get("completed_at"));workflow.feedback(admin,id,new Requests.ComplaintFeedback(3L,key(),"first formal result","public basis"));clock.set(expected.plusDays(1));workflow.action(student,id,"object",new Requests.ComplaintResponse(4L,key(),"objection"));workflow.feedback(admin2,id,new Requests.ComplaintFeedback(5L,key(),"second result","second basis"));assertEquals(completed,target(id).get("completed_at"));assertEquals("service_open_hours_24",db.one("SELECT basis FROM complaint_targets WHERE complaint_id=?",id).get("basis"));}
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void withdrawalStopsTheTargetButDoesNotEraseAnAlreadyLateFact(boolean late){long id=service();clock.set(late?due(id):base.atTime(10,0));workflow.action(student,id,"withdraw",new Requests.ComplaintResponse(1L,key(),null));assertEquals("withdrawn",target(id).get("status"));assertNull(target(id).get("completed_at"));assertNull(target(id).get("was_late"));assertEquals(late?1:0,db.count("SELECT count(*) FROM complaint_target_events"));String before=facts();clock.set(base.plusDays(5).atTime(9,0));targets.settle();assertEquals(before,facts());}
    @Test void readAndTaskDetectionIsOnceWithLogicalDueAndActualDetectionNoUserPenaltyOrNotification()throws Exception{String owner=token("student"),adminToken=token("admin");long id=service();var due=due(id);clock.set(due.minusNanos(1000));http.perform(auth(get("/api/complaints/"+id),owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.service_target.status").value("running"));assertEquals(0,db.count("SELECT count(*) FROM complaint_target_events"));clock.set(due.plusHours(2));http.perform(auth(get("/api/admin/complaints"),adminToken)).andExpect(status().isOk());var event=db.one("SELECT * FROM complaint_target_events WHERE complaint_id=?",id);assertEquals(due.toString(),event.get("occurred_at"));assertEquals(due.plusHours(2).toString(),event.get("detected_at"));assertEquals("new_submission",event.get("source"));String before=facts();workflow.mine(student);workflow.managementDetail(admin,id);targets.settle();assertEquals(before,facts());assertEquals(1,db.count("SELECT count(*) FROM audit_events WHERE action='complaint.target_overdue'"));assertEquals(0,db.count("SELECT count(*) FROM notifications"));assertEquals(0,db.count("SELECT count(*) FROM violations"));assertEquals(0,db.count("SELECT count(*) FROM reminders"));}
    @Test void successfulUuidReplaysNeverDetectNewOverdueFacts(){long history=historical();var request=new Requests.Complaint("occupation",history,null,"S17工程模拟求助",List.of(),key());long id=Store.id(complaints.create(student,request),"id");var accept=new Requests.ComplaintAccept(1L,key());management.accept(admin,id,accept);clock.set(due(id));String before=facts();assertEquals(true,complaints.create(student,request).get("replayed"));assertEquals(true,management.accept(admin,id,accept).get("replayed"));assertEquals(before,facts());assertEquals("completed",target(id).get("status"));assertNull(target(id).get("overdue_event"));workflow.detail(student,id);assertEquals(0,db.count("SELECT count(*) FROM complaint_target_events"));}
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"accept","feedback","withdraw"})
    void completionAndWithdrawalRollbackIfTargetOverdueAuditFails(String action)throws Exception{String owner=token("student"),adminToken=token("admin");long id;if(action.equals("accept")){long history=historical();clock.set(base.atTime(9,0));id=occupation(history);}else if(action.equals("feedback")){id=legacyService(false,base.atTime(9,0));}else{id=service();}var targetDue=due(id);clock.set(targetDue);String before=facts();db.update("ALTER TABLE audit_events ADD CONSTRAINT s17_fail CHECK(action<>'complaint.target_overdue') NOT VALID");try{Object request=action.equals("accept")?new Requests.ComplaintAccept(1L,key(),"工程模拟初步安排：先核对问题并说明后续处理。",null,null,null,null):action.equals("feedback")?new Requests.ComplaintFeedback(1L,key(),"result","basis"):Map.of("version",1,"operation_id",key());String path=action.equals("withdraw")?"/api/complaints/"+id+"/withdraw":"/api/admin/complaints/"+id+"/"+action;http.perform(body(post(path),request,action.equals("withdraw")?owner:adminToken)).andExpect(status().isInternalServerError());assertEquals(before,facts());}finally{db.update("ALTER TABLE audit_events DROP CONSTRAINT s17_fail");}}
    @Test void targetEventFailureAndUnauthorizedAccessDoNotPartiallyWrite()throws Exception{String owner=token("student"),otherToken=token("other");long id=service();clock.set(due(id));String before=facts();http.perform(auth(get("/api/complaints/"+id),otherToken)).andExpect(status().isForbidden());assertEquals(before,facts());db.update("ALTER TABLE complaint_target_events ADD CONSTRAINT s17_fail CHECK(false) NOT VALID");try{http.perform(auth(get("/api/complaints/"+id),owner)).andExpect(status().isInternalServerError());assertEquals(before,facts());}finally{db.update("ALTER TABLE complaint_target_events DROP CONSTRAINT s17_fail");}}
    @Test void concurrentDetectionAndFormalFeedbackProduceOneTargetFactWithLateCompletion()throws Exception{long id=legacyService(false,base.atTime(9,0));clock.set(due(id));var barrier=new CyclicBarrier(3);var pool=Executors.newFixedThreadPool(3);try{var jobs=List.of(pool.submit(()->{barrier.await();return targets.settle();}),pool.submit(()->{barrier.await();return workflow.detail(student,id);}),pool.submit(()->{barrier.await();return workflow.feedback(admin,id,new Requests.ComplaintFeedback(1L,key(),"result","basis"));}));for(var job:jobs)job.get(20,TimeUnit.SECONDS);}finally{pool.shutdownNow();}assertEquals(1,db.count("SELECT count(*) FROM complaint_target_events"));assertEquals(1,db.count("SELECT count(*) FROM audit_events WHERE action='complaint.target_overdue'"));assertEquals("late_completed",target(id).get("status"));}
    long legacyService(boolean completed){return legacyService(completed,base.minusDays(3).atTime(9,0));}
    long legacyService(boolean completed,LocalDateTime submitted){long id=db.insert("INSERT INTO complaints(user_id,operation_id,request_hash,type,status,description,submitted_at,handler_id,accepted_at) VALUES(?,CAST(? AS UUID),'legacy-intent','service',?,'S17明确旧数据模拟',?,?,?)",student,key(),completed?"awaiting_confirmation":"processing",java.sql.Timestamp.valueOf(submitted),admin,java.sql.Timestamp.valueOf(submitted.plusMinutes(1)));db.update("INSERT INTO complaint_events(complaint_id,actor_id,event_type,source,created_at) VALUES(?,?,'submitted','user_submit',?)",id,student,java.sql.Timestamp.valueOf(submitted));if(completed){var at=submitted.plusHours(25);db.update("INSERT INTO complaint_events(complaint_id,actor_id,event_type,source,created_at,operation_id,request_hash,feedback_result,feedback_basis,feedback_deadline) VALUES(?,?,'feedback','admin_self_report',?,CAST(? AS UUID),'legacy-feedback','old result','old basis',?)",id,admin,java.sql.Timestamp.valueOf(at),key(),java.sql.Timestamp.valueOf(at.plusDays(7)));}db.update("INSERT INTO complaint_targets(complaint_id,kind,calculation_status,anchor_at,due_at,reason,room_version,calculated_at,basis,source,target_seconds,open_intervals,room_calendar_snapshot) VALUES(?,'first_feedback','calculated',?,?,'旧服务目标推导模拟，不补造过去检测',NULL,?,'legacy_service_derived','legacy_derived',86400,NULL,NULL)",id,java.sql.Timestamp.valueOf(submitted),java.sql.Timestamp.valueOf(submitted.plusHours(24)),java.sql.Timestamp.valueOf(base.atTime(9,0)));return id;}
    @Test void legacyCompletedLateHasNoInventedEventButActiveLegacyGetsActualDerivedDetection(){long completed=legacyService(true),active=legacyService(false);assertEquals("late_completed",target(completed).get("status"));assertEquals(true,target(completed).get("was_late"));assertNull(target(completed).get("overdue_event"));workflow.detail(student,completed);assertEquals(0,db.count("SELECT count(*) FROM complaint_target_events"));workflow.detail(student,active);assertEquals("legacy_derived",db.one("SELECT source FROM complaint_target_events WHERE complaint_id=?",active).get("source"));targets.settle();assertEquals(1,db.count("SELECT count(*) FROM complaint_target_events"));}
}
