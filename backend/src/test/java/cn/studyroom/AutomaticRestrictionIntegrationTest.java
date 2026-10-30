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
class AutomaticRestrictionIntegrationTest {
    @TestConfiguration static class TimeConfig { @Bean @Primary MinuteBookingIntegrationTest.TestClock testClock() { var c=new MinuteBookingIntegrationTest.TestClock();c.set(LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1).atTime(9,0));return c; } }
    @Autowired Store db;
    @Autowired BookingService bookings;
    @Autowired SeatCodeService codes;
    @Autowired OpeningScheduleService opening;
    @Autowired ResourceService resources;
    @Autowired ComplaintService complaints;
    @Autowired ComplaintManagementService management;
    @Autowired ComplaintWorkflowService workflow;
    @Autowired ViolationService violations;
    @Autowired RestrictionWorkflow restrictionWorkflow;
    @Autowired RestrictionPolicy restrictionPolicy;
    @Autowired RestrictionActivationRunner restrictionRunner;
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
        base=LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1);clock.set(base.atTime(9,0));remote="s20rt-test-"+UUID.randomUUID();
        String hash=new BCryptPasswordEncoder().encode("S02-test-only-password");
        admin=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s20rt_admin',?,'admin','S09-admin')",hash);
        admin2=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s20rt_admin2',?,'admin','S15-admin2')",hash);
        student=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s20rt_student',?,'student','S09-student')",hash);
        staff=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s20rt_staff',?,'staff','S09-staff')",hash);
        other=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s20rt_other',?,'student','S14-other')",hash);
        db.update("UPDATE users SET real_name='S19模拟管理员甲' WHERE id=?",admin);
        room=db.insert("INSERT INTO rooms(name,seat_rows,seat_cols) VALUES('S14模拟空间',1,2)");opening.initializeDefaults(room);
        seat1=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'01',1,1)",room);seat2=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'02',1,2)",room);
    }
    @AfterEach void clearRate() { redis.delete("studyroom-java:rate:login:"+remote); }
    void at(int h,int m,int s) { clock.set(base.atTime(h,m,s)); }
    String token(String role) throws Exception {
        var result=http.perform(post("/api/auth/login").with(r->{r.setRemoteAddr(remote);return r;}).contentType("application/json").content(json.writeValueAsString(Map.of("username","s20rt_"+role,"password","S02-test-only-password")))).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }
    long reservation(long user,long seat) { return Store.id(bookings.create(user,new Requests.Reservation(seat,base.toString(),"10:00","11:00")),"id"); }
    String code(long seat) { codes.generate(admin,seat,new Requests.SeatCodeChange(0L,"测试实际生成座位码"));return current(seat); }
    String current(long seat) { return Store.text(db.one("SELECT code_value FROM seat_codes WHERE seat_id=?",seat),"code_value"); }
    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder req,String token) { return req.header("Authorization","Bearer "+token); }
    MockHttpServletRequestBuilder checkin(long id,String code,String token) throws Exception { return auth(post("/api/reservations/"+id+"/checkin"),token).contentType("application/json").content(json.writeValueAsString(Map.of("seat_code",code))); }
    Map<String,Object> row(long id) { return db.one("SELECT * FROM reservations WHERE id=?",id); }



    String key(){return UUID.randomUUID().toString();}
    LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),BookingPolicy.CAMPUS_ZONE).truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    String offset(LocalDateTime t){return t.atZone(BookingPolicy.CAMPUS_ZONE).toOffsetDateTime().toString();}
    Requests.RestrictionCreate create(long user){return new Requests.RestrictionCreate(user,offset(base.plusDays(2).atTime(18,0)),"PUBLIC-S19-REASON 管理限制公开原因","PRIVATE-S19-VERIFICATION 工程模拟核实依据",true,key());}
    Requests.RestrictionRelease release(long id){return new Requests.RestrictionRelease(version(id),"PUBLIC-S19-RELEASE 本条解除原因","PRIVATE-S19-RELEASE 工程模拟解除依据",true,key());}
    @SuppressWarnings("unchecked")Map<String,Object> record(Map<String,Object> receipt){return (Map<String,Object>)receipt.get("restriction");}
    long restriction(Map<String,Object> receipt){return Store.id(record(receipt),"id");}
    long version(long id){return Store.id(db.one("SELECT version FROM restrictions WHERE id=?",id),"version");}
    String state(long id){return Store.text(restrictionPolicy.detail(id,now(),false),"status");}
    Map<String,Object> qualify(long user){return restrictionWorkflow.eligibility(user);}
    long booking(long user,long seat,String start,String end){return Store.id(bookings.create(user,new Requests.Reservation(seat,base.toString(),start,end)),"id");}
    long future(long user){return Store.id(bookings.create(user,new Requests.Reservation(seat2,base.plusDays(1).toString(),"12:00","13:00")),"id");}
    long legacy(long user,LocalDateTime start,LocalDateTime end){db.update("UPDATE users SET credit_banned_until=? WHERE id=?",java.sql.Timestamp.valueOf(end),user);return db.insert("INSERT INTO restrictions(user_id,source,starts_at,ends_at,reason,creator_id,verification_note,facts_confirmed,created_at) VALUES(?,'legacy',?,?,'S19明确工程模拟余期接管，原原因起点未知',NULL,NULL,false,?)",user,java.sql.Timestamp.valueOf(start),java.sql.Timestamp.valueOf(end),java.sql.Timestamp.valueOf(start));}
    String facts(){var all=new LinkedHashMap<String,Object>();for(String table:List.of("restriction_evaluation_sources","restriction_evaluations","restrictions","restriction_operations","reservations","usage_records","violations","notifications","audit_events","credit_logs","users"))all.put(table,db.list("SELECT to_jsonb(t)::text AS raw FROM "+table+" t ORDER BY to_jsonb(t)::text"));return all.toString();}
    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder req,Object payload,String token)throws Exception{return auth(req,token).contentType("application/json").content(json.writeValueAsString(payload));}
    String path(long id){return "/api/admin/restrictions/"+id+"/release";}
    long[] pendingAndInUse(){clock.set(base.atTime(8,0));long expired=booking(student,seat2,"09:00","09:30"),used=booking(student,seat1,"10:00","11:00"),pending=booking(student,seat2,"12:00","13:00");String raw=code(seat1);clock.set(base.atTime(10,1));assertEquals(200,bookings.checkin(student,used,raw).status());clock.set(base.atTime(10,5,0,123456000));return new long[]{expired,used,pending};}

    long source(long user,LocalDateTime occurred){
        var start=occurred.minusMinutes(15);long reservation=db.insert("INSERT INTO reservations(user_id,seat_id,res_date,start_time,end_time,status,ended_at,termination_reason) VALUES(?,?,?,CAST(? AS TIME),CAST(? AS TIME),'unused_ended',?,'no_show')",user,seat1,java.sql.Date.valueOf(start.toLocalDate()),start.toLocalTime().toString(),start.plusMinutes(30).toLocalTime().toString(),java.sql.Timestamp.valueOf(occurred));
        return db.insert("INSERT INTO violations(user_id,reservation_id,type,event_key,occurred_at,registered_at,status,reason,source) VALUES(?,?,'no_show',?,?,?,'active','S20明确历史事实工程构型，不以registered代替occurred','automatic')",user,reservation,"unused:"+reservation,java.sql.Timestamp.valueOf(occurred),java.sql.Timestamp.valueOf(now()));
    }
    long[] three(long user){return new long[]{source(user,now().minusDays(3)),source(user,now().minusDays(2)),source(user,now().minusDays(1))};}
    long automatic(long user){return Store.id(db.one("SELECT id FROM restrictions WHERE user_id=? AND source='automatic' ORDER BY id DESC LIMIT 1",user),"id");}
    List<Long> consumed(long id){return db.list("SELECT violation_id FROM restriction_evaluation_sources WHERE restriction_id=? ORDER BY source_order",id).stream().map(row->Store.id(row,"violation_id")).toList();}
    long automaticCount(long user){return db.count("SELECT count(*) FROM restrictions WHERE user_id=? AND source='automatic'",user);}
    @Test void twoDoNotTriggerThirdCreatesOneExactSevenDayBatchAndSafeOriginalOrder()throws Exception{
        long a=source(student,now().minusDays(3)),b=source(student,now().minusDays(2));assertEquals(true,qualify(student).get("can_book"));assertEquals(0,automaticCount(student));long c=source(student,now().minusDays(1));String owner=token("student");http.perform(auth(get("/api/booking-eligibility"),owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.can_book").value(false)).andExpect(jsonPath("$.data.active_restrictions[0].verification_source").value("system_rolling_violations")).andExpect(jsonPath("$.data.active_restrictions[0].automatic_evaluation.threshold").value(3)).andExpect(jsonPath("$.data.active_restrictions[0].automatic_evaluation.duration_hours").value(168)).andExpect(jsonPath("$.data.active_restrictions[0].automatic_evaluation.source_violations.length()").value(3));long id=automatic(student);assertEquals(List.of(a,b,c),consumed(id));assertEquals(now().toString(),restrictionPolicy.required(id).get("starts_at"));assertEquals(now().plusHours(168).toString(),restrictionPolicy.required(id).get("ends_at"));assertNull(restrictionPolicy.required(id).get("creator_id"));assertEquals(1,db.count("SELECT count(*) FROM notifications WHERE restriction_id=? AND type='restriction_started'",id));String before=facts();qualify(student);bookings.tick();assertEquals(before,facts());
    }
    @Test void closedWindowIncludesBothMicrosecondEndpointsAndRejectsOlderFutureAndRevoked(){
        LocalDateTime stamp=base.atTime(9,0,0,123456000);clock.set(stamp);long old=source(student,stamp.minusDays(30).minusNanos(1000)),left=source(student,stamp.minusDays(30)),right=source(student,stamp),future=source(student,stamp.plusNanos(1000)),revoked=source(student,stamp.minusDays(1));db.update("UPDATE violations SET status='revoked' WHERE id=?",revoked);assertEquals(true,qualify(student).get("can_book"));long middle=source(student,stamp.minusHours(1));qualify(student);assertEquals(List.of(left,middle,right),consumed(automatic(student)));assertEquals(0,db.count("SELECT count(*) FROM restriction_evaluation_sources WHERE violation_id IN(?,?,?)",old,future,revoked));
    }
    @Test void registeredNowDoesNotReviveAThirtyDayOldOccurrence(){for(int i=1;i<=3;i++)source(student,now().minusDays(30).minusHours(i));assertEquals(true,qualify(student).get("can_book"));assertEquals(0,automaticCount(student));}
    @Test void equalOccurrenceBreaksTiesByIdAndFourSourcesConsumeOnlyEarliestThree(){var time=now().minusMinutes(1);long a=source(student,time),b=source(student,time),c=source(student,time),d=source(student,time);qualify(student);assertEquals(List.of(a,b,c),consumed(automatic(student)));assertEquals(0,db.count("SELECT count(*) FROM restriction_evaluation_sources WHERE violation_id=?",d));assertEquals(1,automaticCount(student));}
    @Test void sixSourcesAreOneBatchThenExplicitSameMicrosecondReleaseCreatesOnlyOneNextBatch(){long[] ids=new long[6];for(int i=0;i<6;i++)ids[i]=source(student,now().minusDays(6-i));qualify(student);long first=automatic(student);assertEquals(1,automaticCount(student));assertEquals(List.of(ids[0],ids[1],ids[2]),consumed(first));var response=restrictionWorkflow.release(admin,first,release(first));long second=automatic(student);assertNotEquals(first,second);assertEquals(2,automaticCount(student));assertEquals(List.of(ids[3],ids[4],ids[5]),consumed(second));assertEquals(false,((Map<?,?>)response.get("eligibility")).get("can_book"));assertEquals(restrictionPolicy.required(first).get("starts_at"),restrictionPolicy.required(second).get("starts_at"));assertEquals(6,db.count("SELECT count(*) FROM restriction_evaluation_sources"));}
    @Test void anyManualOrLegacyActiveDefersUntilAllEndAndUsesTheRestoredWindow(){long a=legacy(student,now(),now().plusDays(2));var request=new Requests.RestrictionCreate(student,offset(now().plusDays(1)),"manual public","private admin self report",true,key());long b=restriction(restrictionWorkflow.create(admin,request));three(student);assertEquals(false,qualify(student).get("can_book"));assertEquals(0,automaticCount(student));clock.set(now().plusDays(1));qualify(student);assertEquals("expired",state(b));assertEquals("active",state(a));assertEquals(0,automaticCount(student));clock.set(base.plusDays(2).atTime(9,0));qualify(student);assertEquals(1,automaticCount(student));assertEquals(base.plusDays(2).atTime(9,0).toString(),restrictionPolicy.required(automatic(student)).get("starts_at"));}
    @Test void deferredOldBacklogFallsOutsideWindowInsteadOfShiftingOccurrenceDates(){long id=legacy(student,now(),now().plusDays(45));three(student);clock.set(now().plusDays(45));assertEquals(true,qualify(student).get("can_book"));assertEquals("expired",state(id));assertEquals(0,automaticCount(student));assertEquals(0,db.count("SELECT count(*) FROM restriction_evaluation_sources"));}
    @Test void consumedSourcesRemainConsumedAfterExpiryReleaseAndCurrentRevocation(){long[] sources=three(student);qualify(student);long id=automatic(student);restrictionWorkflow.release(admin,id,release(id));db.update("UPDATE violations SET status='revoked' WHERE id=?",sources[0]);String stored=Store.text(db.one("SELECT violation_snapshot::text AS value FROM restriction_evaluation_sources WHERE violation_id=?",sources[0]),"value");assertTrue(stored.contains("\"status\": \"active\""));var dto=restrictionPolicy.detail(id,now(),false);assertTrue(jsonValue(dto).contains("revoked"));assertEquals(3,consumed(id).size());assertEquals(true,qualify(student).get("can_book"));clock.set(now().plusDays(8));assertEquals(true,qualify(student).get("can_book"));assertEquals(1,automaticCount(student));}
    String jsonValue(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    @Test void disabledOrdinaryIsAssessedButAdministratorOldFactsNeverBecomeOrdinaryPunishment(){three(staff);db.update("UPDATE users SET status='disabled' WHERE id=?",staff);assertEquals("disabled",qualify(staff).get("blocked_by"));assertEquals(1,automaticCount(staff));three(admin2);assertEquals("role",qualify(admin2).get("blocked_by"));assertEquals(0,automaticCount(admin2));assertEquals(3,db.count("SELECT count(*) FROM violations WHERE user_id=?",admin2));}
    @Test void wholeExpiredBatchIsSettledBeforeAnyNewRestrictionCancelsRemainingPending(){clock.set(base.atTime(7,0));long[] ids={booking(student,seat1,"08:00","08:30"),booking(student,seat1,"08:30","09:00"),booking(student,seat1,"09:00","09:30"),booking(student,seat1,"09:30","10:00"),future(student)};clock.set(base.atTime(10,15));bookings.mine(student);assertEquals(4,db.count("SELECT count(*) FROM violations WHERE user_id=?",student));for(int i=0;i<4;i++){assertEquals("unused_ended",row(ids[i]).get("status"));assertEquals("no_show",row(ids[i]).get("termination_reason"));}assertEquals("cancelled",row(ids[4]).get("status"));assertEquals(3,consumed(automatic(student)).size());assertEquals(1,automaticCount(student));}
    @Test void checkedInContinuesToOriginalEndWhileAutomaticCancelsOnlyUnstartedPending(){long used=reservation(student,seat1),pending=future(student);String raw=code(seat1);clock.set(base.atTime(10,1));bookings.checkin(student,used,raw);three(student);qualify(student);assertEquals("checked_in",row(used).get("status"));assertNull(db.one("SELECT ended_at FROM usage_records WHERE reservation_id=?",used).get("ended_at"));assertEquals("cancelled",row(pending).get("status"));clock.set(base.atTime(11,0));bookings.detail(student,used);assertEquals("completed",row(used).get("status"));assertEquals(base+"T11:00",db.one("SELECT ended_at FROM usage_records WHERE reservation_id=?",used).get("ended_at"));assertEquals(3,db.count("SELECT count(*) FROM violations WHERE user_id=?",student));}
    @Test void firstCheckinAssessesBacklogBeforeItCanBecomeUsageAndTyped409Commits()throws Exception{String owner=token("student");long id=reservation(student,seat1);String raw=code(seat1);three(student);clock.set(base.atTime(10,1));http.perform(checkin(id,raw,owner)).andExpect(status().isConflict()).andExpect(jsonPath("$.details.status").value("cancelled"));assertEquals(0,db.count("SELECT count(*) FROM usage_records"));assertEquals(1,automaticCount(student));}
    @Test void abandonThirdViolationTriggersSameTransactionAndReturnsItsRealUnusedState(){long id=reservation(student,seat1),pending=future(student);source(student,now().minusDays(2));source(student,now().minusDays(1));clock.set(base.atTime(10,1));var result=bookings.action(student,id,"abandon");assertEquals(200,result.status());assertEquals("unused_ended",result.data().get("status"));assertEquals("cancelled",row(pending).get("status"));assertEquals(3,db.count("SELECT count(*) FROM violations WHERE user_id=?",student));assertEquals(1,automaticCount(student));}
    @Test void newBookingTyped403CommitsEvaluationAndNoShowFacts()throws Exception{String owner=token("student");long id=reservation(student,seat1);source(student,now().minusDays(2));source(student,now().minusDays(1));clock.set(base.atTime(10,15));http.perform(body(post("/api/reservations"),new Requests.Reservation(seat2,base.plusDays(1).toString(),"14:00","15:00"),owner)).andExpect(status().isForbidden()).andExpect(jsonPath("$.details.active_restrictions[0].automatic_evaluation.source_violations.length()").value(3));assertEquals("unused_ended",row(id).get("status"));assertEquals(1,automaticCount(student));}
    @Test void availabilityRemainsReadableButReconcilesAllMyDatesAndAssessment()throws Exception{String owner=token("student");long expired=reservation(student,seat1);source(student,now().minusDays(2));source(student,now().minusDays(1));clock.set(base.atTime(10,15));http.perform(auth(get("/api/availability").param("room_id",Long.toString(room)).param("date",base.plusDays(1).toString()),owner)).andExpect(status().isOk());assertEquals("unused_ended",row(expired).get("status"));assertEquals(1,automaticCount(student));}
    @Test void ownerViolationsFirstReadAndRoomSeatMapBothReachSameAssessmentCore(){three(student);bookings.violationsMine(student);assertEquals(1,automaticCount(student));clock.set(base.atTime(8,0));long pending=booking(other,seat1,"09:00","09:30");source(other,now().minusDays(2));source(other,now().minusDays(1));clock.set(base.atTime(10,0));resources.seatMap(room,base.toString(),"10:00","11:00");assertEquals("unused_ended",row(pending).get("status"));assertEquals(1,automaticCount(other));}
    @Test void startupAndTaskFindBacklogWithoutAnyCurrentlyActiveRestriction(){three(student);restrictionRunner.run(new org.springframework.boot.DefaultApplicationArguments(new String[0]));assertEquals(1,automaticCount(student));clock.set(now().plusDays(7));for(int i=0;i<3;i++)source(student,now().minusHours(i+1));bookings.tick();assertEquals(2,automaticCount(student));String before=facts();bookings.tick();assertEquals(before,facts());}
    @Test void concurrentQualificationAndTaskCreateOnlyOneBatchAndPermanentSources()throws Exception{three(student);var pool=Executors.newFixedThreadPool(3);var barrier=new CyclicBarrier(3);try{var a=pool.submit(()->{barrier.await();return qualify(student);});var b=pool.submit(()->{barrier.await();bookings.tick();return true;});var c=pool.submit(()->{barrier.await();return bookings.violationsMine(student);});a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);c.get(20,TimeUnit.SECONDS);}finally{pool.shutdownNow();}assertEquals(1,automaticCount(student));assertEquals(3,db.count("SELECT count(*) FROM restriction_evaluation_sources"));assertEquals(1,db.count("SELECT count(*) FROM notifications WHERE type='restriction_started'"));}
    long refusalSource(){
        long reservation=reservation(other,seat1);clock.set(base.atTime(10,1));long complaint=Store.id(complaints.create(other,new Requests.Complaint("occupation",reservation,null,"S20明确工程模拟第三方占座核实来源",List.of(),key())),"id");management.accept(admin,complaint,new Requests.ComplaintAccept(1L,key()));return Store.id(management.verify(admin,complaint,new Requests.ComplaintVerification(2L,key(),"verified_refusal",offset(now()),"允许主动公开的核实进度","PRIVATE-S20-FACTS",student,true,"PRIVATE-S20-IDENTITY",true,true)),"event_id");
    }
    @Test void manualThirdEventUsesSameTransactionAndSafeSourceDoesNotExposeReporter()throws Exception{
        String adminToken=token("admin"),owner=token("student");long pending=future(student);source(student,now().minusDays(2));source(student,now().minusDays(1));long event=refusalSource();var request=new Requests.ManualViolation(key(),"独立面向被登记人的事实说明");http.perform(body(post("/api/admin/complaint-events/"+event+"/violations"),request,adminToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(false));assertEquals(1,automaticCount(student));assertEquals("cancelled",row(pending).get("status"));String safe=http.perform(auth(get("/api/restrictions/mine"),owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].automatic_evaluation.source_violations[2].source").value("manual")).andExpect(jsonPath("$.data.items[0].automatic_evaluation.source_violations[2].reservation_id").isEmpty()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);assertFalse(safe.contains("PRIVATE-S20"));assertFalse(safe.contains("source_event_id"));assertFalse(safe.contains("subject_user_id"));assertFalse(safe.contains("reporter"));long id=automatic(student);restrictionWorkflow.release(admin,id,release(id));three(student);String before=facts();http.perform(body(post("/api/admin/complaint-events/"+event+"/violations"),request,adminToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(true));assertEquals(before,facts());assertEquals(1,automaticCount(student));
    }
    @Test void automaticFailureRollsBackManualThirdViolationNotificationAndRegistrationOperation()throws Exception{
        String adminToken=token("admin");source(student,now().minusDays(2));source(student,now().minusDays(1));long event=refusalSource();String before=facts();db.update("ALTER TABLE restriction_evaluation_sources ADD CONSTRAINT s20_manual_fail CHECK(source_order<>3) NOT VALID");try{http.perform(body(post("/api/admin/complaint-events/"+event+"/violations"),new Requests.ManualViolation(key(),"明确工程模拟公开事实"),adminToken)).andExpect(status().isInternalServerError());assertEquals(before,facts());assertEquals(0,db.count("SELECT count(*) FROM violations WHERE source_event_id=?",event));}finally{db.update("ALTER TABLE restriction_evaluation_sources DROP CONSTRAINT s20_manual_fail");}
    }
    @Test void successfulOldRestrictionOperationReplayIsPureEvenWithNewBacklogAndFailingAssessment(){var request=create(student);long id=restriction(restrictionWorkflow.create(admin,request));var released=release(id);restrictionWorkflow.release(admin,id,released);three(student);String before=facts();db.update("ALTER TABLE restriction_evaluation_sources ADD CONSTRAINT s20_replay_fail CHECK(source_order<>3) NOT VALID");try{assertEquals(true,restrictionWorkflow.create(admin,request).get("replayed"));assertEquals(true,restrictionWorkflow.release(admin,id,released).get("replayed"));assertEquals(before,facts());assertEquals(0,automaticCount(student));}finally{db.update("ALTER TABLE restriction_evaluation_sources DROP CONSTRAINT s20_replay_fail");}}
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"restriction","evaluation","source","notification","audit","cancel"})
    void dependencyFailureRollsBackNewUnusedViolationEvaluationAndAllEffects(String layer)throws Exception{String owner=token("student");long id=reservation(student,seat1),pending=future(student);source(student,now().minusDays(2));source(student,now().minusDays(1));clock.set(base.atTime(10,15));String before=facts();String table=switch(layer){case "restriction"->"restrictions";case "evaluation"->"restriction_evaluations";case "source"->"restriction_evaluation_sources";case "notification","cancel"->"notifications";default->"audit_events";};String condition=switch(layer){case "restriction"->"source<>'automatic'";case "evaluation"->"threshold<>3";case "source"->"source_order<>3";case "notification"->"type<>'restriction_started'";case "cancel"->"type<>'restriction_cancelled'";default->"action<>'restriction.automatic_create'";};db.update("ALTER TABLE "+table+" ADD CONSTRAINT s20_fail CHECK("+condition+") NOT VALID");try{http.perform(auth(get("/api/booking-eligibility"),owner)).andExpect(status().isInternalServerError());assertEquals(before,facts());assertEquals("pending",row(id).get("status"));assertEquals("pending",row(pending).get("status"));}finally{db.update("ALTER TABLE "+table+" DROP CONSTRAINT s20_fail");}}
}
