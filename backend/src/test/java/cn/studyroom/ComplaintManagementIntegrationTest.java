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
class ComplaintManagementIntegrationTest {
    @TestConfiguration static class TimeConfig { @Bean @Primary MinuteBookingIntegrationTest.TestClock testClock() { var c=new MinuteBookingIntegrationTest.TestClock();c.set(LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1).atTime(9,0));return c; } }
    @Autowired Store db;
    @Autowired BookingService bookings;
    @Autowired SeatCodeService codes;
    @Autowired OpeningScheduleService opening;
    @Autowired ResourceService resources;
    @Autowired ComplaintService complaints;
    @Autowired ComplaintManagementService management;
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
        for(String table:List.of("notifications","reminders","complaint_attachments","complaint_events","complaints","resource_change_operations","usage_records","seat_codes","violations","resource_unavailability","opening_windows","opening_schedules","audit_events","credit_logs","waitlist","reservations","seats","rooms","users")) db.update("DELETE FROM "+table);
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

    String key() {return UUID.randomUUID().toString();}
    long submitted(String type,Long reservation) {return Store.id(complaints.create(student,new Requests.Complaint(type,reservation,null,"工程模拟投诉，尚未核实",List.of(),key())),"id");}
    long accepted(String type,Long reservation) {long id=submitted(type,reservation);management.accept(admin,id,new Requests.ComplaintAccept(1L,key()));return id;}
    Requests.ComplaintVerification verification(long version,String outcome,Long subject) {return new Requests.ComplaintVerification(version,key(),outcome,base.atTime(10,5).atZone(BookingPolicy.CAMPUS_ZONE).toOffsetDateTime().toString(),"管理员主动公开的核查进度","PRIVATE-FACT-SENTINEL 工程模拟事实依据",subject,subject!=null,subject==null?null:"PRIVATE-IDENTITY-SENTINEL 工程模拟已核实",true,true);}
    Requests.ComplaintVerification change(Requests.ComplaintVerification r,String operation,String outcome,String occurred,String publicNote,Long subject,Boolean identity,String basis,Boolean facts,Boolean valid) {return new Requests.ComplaintVerification(r.version(),operation,outcome,occurred,publicNote,r.privateNote(),subject,identity,basis,facts,valid);}
    String managementFacts() {var state=new LinkedHashMap<String,Object>();for(String table:List.of("complaints","complaint_events","reminders","reservations","usage_records","notifications","violations","credit_logs","audit_events")) state.put(table,db.list("SELECT to_jsonb(t)::text AS raw FROM "+table+" t ORDER BY to_jsonb(t)::text"));return state.toString();}
    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request,Object value,String token) throws Exception {return auth(request,token).contentType("application/json").content(json.writeValueAsString(value));}
    String acceptPath(long id) {return "/api/admin/complaints/"+id+"/accept";}
    String verifyPath(long id) {return "/api/admin/complaints/"+id+"/verifications";}
    @Test void authorizationOnHttpAndServiceAndStrictFilters() throws Exception {
        long id=submitted("service",null);String adminToken=token("admin"),studentToken=token("student"),staffToken=token("staff");
        http.perform(get("/api/admin/complaints")).andExpect(status().isUnauthorized());
        for(String ordinary:List.of(studentToken,staffToken)) {http.perform(auth(get("/api/admin/complaints"),ordinary)).andExpect(status().isForbidden());http.perform(auth(get("/api/admin/complaints/"+id),ordinary)).andExpect(status().isForbidden());http.perform(body(post(acceptPath(id)),new Requests.ComplaintAccept(1L,key()),ordinary)).andExpect(status().isForbidden());}
        assertEquals(403,assertThrows(Api.Failure.class,()->management.list(student,null,null,null)).status);assertEquals(403,assertThrows(Api.Failure.class,()->management.accept(staff,id,new Requests.ComplaintAccept(1L,key()))).status);
        http.perform(auth(get("/api/admin/complaints").param("status","submitted").param("type","service"),adminToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(id)).andExpect(jsonPath("$.data[0].version").value(1));
        for(String invalid:List.of("","not_a_state","accepted")) http.perform(auth(get("/api/admin/complaints").param("status",invalid),adminToken)).andExpect(status().isBadRequest());
        http.perform(auth(get("/api/admin/complaints").param("type","unknown"),adminToken)).andExpect(status().isBadRequest());http.perform(auth(get("/api/admin/complaints").param("room_id","1.5"),adminToken)).andExpect(status().isBadRequest());
        db.update("UPDATE users SET status='disabled' WHERE id=?",admin);assertEquals(403,assertThrows(Api.Failure.class,()->management.detail(admin,id)).status);
    }
    @Test void twoAdministratorsAcceptanceHasSingleWinnerAndAcceptedDoesNotMeanVerified() throws Exception {
        long id=submitted("service",null);var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);var jobs=new ArrayList<Future<Integer>>();try {for(long actor:List.of(admin,admin2)) jobs.add(pool.submit(()->{barrier.await();try {management.accept(actor,id,new Requests.ComplaintAccept(1L,key()));return 200;}catch(Api.Failure failure){return failure.status;}}));var statuses=new ArrayList<Integer>();for(var job:jobs) statuses.add(job.get(20,TimeUnit.SECONDS));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);} finally {pool.shutdownNow();}
        var row=db.one("SELECT * FROM complaints WHERE id=?",id);assertEquals("processing",row.get("status"));assertEquals(2,Store.id(row,"version"));assertNotNull(row.get("accepted_at"));assertTrue(Set.of(admin,admin2).contains(Store.id(row,"handler_id")));assertEquals(1,db.count("SELECT count(*) FROM complaint_events WHERE event_type='accepted'"));assertEquals(0,db.count("SELECT count(*) FROM reminders"));assertEquals(0,db.count("SELECT count(*) FROM violations"));
    }
    @Test void acceptanceReplayIsBeforeVersionAndBindsActorTargetAndAction() {
        long id=submitted("service",null);var request=new Requests.ComplaintAccept(1L,key());var first=management.accept(admin,id,request);String facts=managementFacts();assertEquals(true,management.accept(admin,id,request).get("replayed"));assertEquals(first.get("event_id"),management.accept(admin,id,request).get("event_id"));assertEquals(facts,managementFacts());
        assertEquals(409,assertThrows(Api.Failure.class,()->management.accept(admin2,id,request)).status);assertEquals(409,assertThrows(Api.Failure.class,()->management.accept(admin,id,new Requests.ComplaintAccept(2L,request.operationId()))).status);long second=submitted("service",null);assertEquals(409,assertThrows(Api.Failure.class,()->management.accept(admin,second,request)).status);assertEquals(409,assertThrows(Api.Failure.class,()->management.accept(admin,id,new Requests.ComplaintAccept(1L,key()))).status);
    }
    @Test void anyActiveAdministratorMayAppendImmutableFactsAndFirstHandlerNeverChanges() {
        long reservation=reservation(student,seat1),id=accepted("occupation",reservation);at(10,30,0);var request=verification(2,"cooperated",other);var first=management.verify(admin2,id,request);long event=Store.id(first,"event_id");String immutable=db.one("SELECT * FROM complaint_events WHERE id=?",event).toString();
        management.verify(admin,id,verification(3,"identity_pending",null));var row=db.one("SELECT * FROM complaints WHERE id=?",id);assertEquals(admin,Store.id(row,"handler_id"));assertEquals(4,Store.id(row,"version"));assertEquals("processing",row.get("status"));assertEquals(immutable,db.one("SELECT * FROM complaint_events WHERE id=?",event).toString());assertEquals(admin2,Store.id(db.one("SELECT actor_id FROM complaint_events WHERE id=?",event),"actor_id"));assertEquals(1,db.count("SELECT count(*) FROM reminders"));assertEquals(0,db.count("SELECT count(*) FROM violations"));assertEquals(0,db.count("SELECT count(*) FROM credit_logs"));
    }
    @Test void allFiveOutcomesHaveExactReminderAndNoViolationSemantics() {
        long reservation=reservation(student,seat1),occupation=accepted("occupation",reservation),service=accepted("service",null);at(10,30,0);
        management.verify(admin,occupation,verification(2,"cooperated",null));management.verify(admin2,occupation,verification(3,"identity_pending",null));management.verify(admin,occupation,verification(4,"unsubstantiated",other));management.verify(admin2,occupation,verification(5,"verified_refusal",other));management.verify(admin,service,verification(2,"service_verified",null));
        assertEquals(5,db.count("SELECT count(*) FROM complaint_events WHERE event_type='verification'"));assertEquals(1,db.count("SELECT count(*) FROM reminders"));assertNull(db.one("SELECT subject_user_id FROM reminders").get("subject_user_id"));assertEquals(0,db.count("SELECT count(*) FROM violations"));assertEquals(0,db.count("SELECT count(*) FROM credit_logs"));assertTrue(management.reminders(student).isEmpty());assertTrue(management.reminders(other).isEmpty());
        assertEquals("processing",db.one("SELECT status FROM complaints WHERE id=?",service).get("status"));
    }
    @Test void knownSubjectCanBeDisabledButMustBeExplicitVerifiedAndNotReporterForOccupation() {
        long reservation=reservation(student,seat1),id=accepted("occupation",reservation);at(10,30,0);db.update("UPDATE users SET status='disabled' WHERE id=?",other);management.verify(admin,id,verification(2,"cooperated",other));assertEquals(1,db.count("SELECT count(*) FROM reminders WHERE subject_user_id=?",other));
        var r=verification(3,"verified_refusal",other);assertEquals(400,assertThrows(Api.Failure.class,()->management.verify(admin,id,change(r,key(),r.outcome(),r.occurredAt(),r.publicNote(),student,true,r.identityBasis(),true,true))).status);
        assertEquals(400,assertThrows(Api.Failure.class,()->management.verify(admin,id,change(r,key(),r.outcome(),r.occurredAt(),r.publicNote(),other,false,r.identityBasis(),true,true))).status);assertEquals(400,assertThrows(Api.Failure.class,()->management.verify(admin,id,change(r,key(),r.outcome(),r.occurredAt(),r.publicNote(),other,true," ",true,true))).status);assertEquals(404,assertThrows(Api.Failure.class,()->management.verify(admin,id,change(r,key(),r.outcome(),r.occurredAt(),r.publicNote(),Long.MAX_VALUE,true,r.identityBasis(),true,true))).status);
    }
    @Test void positiveOccupationRequiresExplicitRightsFactsAndInOriginalIntervalNotCurrentStatusInference() {
        long reservation=reservation(student,seat1);bookings.action(student,reservation,"cancel");long id=accepted("occupation",reservation),service=accepted("service",null);at(10,30,0);var r=verification(2,"cooperated",null);
        assertEquals(400,assertThrows(Api.Failure.class,()->management.verify(admin,id,change(r,key(),r.outcome(),r.occurredAt(),r.publicNote(),null,false,null,false,true))).status);assertEquals(400,assertThrows(Api.Failure.class,()->management.verify(admin,id,change(r,key(),r.outcome(),r.occurredAt(),r.publicNote(),null,false,null,true,false))).status);
        for(String time:List.of("09:59:59.999999","11:00:00")) {var occurred=base+"T"+time+"+08:00";assertEquals(400,assertThrows(Api.Failure.class,()->management.verify(admin,id,change(r,key(),r.outcome(),occurred,r.publicNote(),null,false,null,true,true))).status);}
        assertEquals(400,assertThrows(Api.Failure.class,()->management.verify(admin,service,r)).status);String before=row(reservation).toString();management.verify(admin,id,r);assertEquals(before,row(reservation).toString());assertEquals("cancelled",row(reservation).get("status"));
    }
    @Test void timestampsAreExplicitOffsetMicrosecondFactsSeparateFromRegistration() {
        long id=accepted("service",null);at(10,30,0);var r=verification(2,"service_verified",null);
        for(String time:List.of(base+"T10:05:00",base+"T10:31:00+08:00",base+"T10:05:00.1234567+08:00","bad")) assertEquals(400,assertThrows(Api.Failure.class,()->management.verify(admin,id,change(r,key(),r.outcome(),time,r.publicNote(),null,false,null,true,false))).status);
        var result=management.verify(admin,id,change(r,key(),r.outcome(),base+"T02:05:00.123456Z",r.publicNote(),null,false,null,true,false));var event=db.one("SELECT occurred_at,created_at FROM complaint_events WHERE id=?",result.get("event_id"));assertEquals(base+"T10:05:00.123456",event.get("occurred_at"));assertEquals(base+"T10:30",event.get("created_at"));
    }
    @Test void validationDoesNotInventIdentityServiceOrActionState() {
        long id=submitted("service",null);at(10,30,0);var r=verification(1,"service_verified",null);assertEquals(409,assertThrows(Api.Failure.class,()->management.verify(admin,id,r)).status);management.accept(admin,id,new Requests.ComplaintAccept(1L,key()));
        var updated=verification(2,"service_verified",null);assertEquals(400,assertThrows(Api.Failure.class,()->management.verify(admin,id,change(updated,key(),updated.outcome(),updated.occurredAt(),updated.publicNote(),null,false,null,false,false))).status);
        assertEquals(400,assertThrows(Api.Failure.class,()->management.verify(admin,id,change(updated,key(),"identity_pending",updated.occurredAt(),updated.publicNote(),other,true,"known",false,false))).status);assertEquals(400,assertThrows(Api.Failure.class,()->management.verify(admin,id,change(updated,key(),"verified_refusal",updated.occurredAt(),updated.publicNote(),null,false,null,true,true))).status);assertEquals(400,assertThrows(Api.Failure.class,()->management.verify(admin,id,change(updated,key(),"closed",updated.occurredAt(),updated.publicNote(),null,false,null,true,true))).status);
    }
    @Test void verificationReplayAndConcurrentSameKeyDoNotDuplicateEventsRemindersOrAudits() throws Exception {
        long id=accepted("occupation",reservation(student,seat1));at(10,30,0);var request=verification(2,"cooperated",other);var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);try {var a=pool.submit(()->{barrier.await();return management.verify(admin,id,request);});var b=pool.submit(()->{barrier.await();return management.verify(admin,id,request);});assertEquals(a.get(20,TimeUnit.SECONDS).get("event_id"),b.get(20,TimeUnit.SECONDS).get("event_id"));}finally{pool.shutdownNow();}
        assertEquals(1,db.count("SELECT count(*) FROM reminders"));assertEquals(1,db.count("SELECT count(*) FROM complaint_events WHERE event_type='verification'"));assertEquals(1,db.count("SELECT count(*) FROM audit_events WHERE action='complaint.verify'"));String before=managementFacts();assertEquals(true,management.verify(admin,id,request).get("replayed"));assertEquals(before,managementFacts());assertEquals(409,assertThrows(Api.Failure.class,()->management.verify(admin2,id,request)).status);assertEquals(409,assertThrows(Api.Failure.class,()->management.verify(admin,id,change(request,request.operationId(),request.outcome(),request.occurredAt(),"changed",other,true,request.identityBasis(),true,true))).status);assertEquals(409,assertThrows(Api.Failure.class,()->management.accept(admin,id,new Requests.ComplaintAccept(2L,request.operationId()))).status);
    }
    @Test void publicOwnerDtoAndRemindedSubjectDtoNeverCopyPrivateFactsOrThirdPartyProfiles() throws Exception {
        long id=accepted("occupation",reservation(student,seat1));db.update("UPDATE users SET username='SUBJECT-SECRET',real_name='NAME-SECRET',campus_id='CAMPUS-SECRET',phone='PHONE-SECRET',email='EMAIL-SECRET@example.test' WHERE id=?",other);at(10,30,0);management.verify(admin2,id,verification(2,"cooperated",other));String owner=token("student");
        for(String path:List.of("/api/complaints/"+id,"/api/complaints/mine")) {String result=http.perform(auth(get(path),owner)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);for(String secret:List.of("SUBJECT-SECRET","NAME-SECRET","CAMPUS-SECRET","PHONE-SECRET","EMAIL-SECRET","PRIVATE-FACT-SENTINEL","PRIVATE-IDENTITY-SENTINEL","subject_user_id","actor_id","identity_basis","admin_events","reporter")) assertFalse(result.contains(secret),secret);assertTrue(result.contains("processing"));}
        var reminder=management.reminders(other).get(0);assertEquals(Set.of("id","outcome","occurred_at","registered_at","public_note","counts_as_violation"),reminder.keySet());assertEquals(false,reminder.get("counts_as_violation"));assertFalse(reminder.toString().contains("管理员主动公开"));assertTrue(management.reminders(student).isEmpty());
        String adminResult=json.writeValueAsString(management.detail(admin,id));assertTrue(adminResult.contains("PRIVATE-FACT-SENTINEL"));assertTrue(adminResult.contains("SUBJECT-SECRET"));assertTrue(adminResult.contains("admin_events"));
    }
    @Test void currentContextIsPureSnapshotEvenWhenDeadlinesOrUsageHaveEnded() {
        long reservation=reservation(student,seat1),id=accepted("occupation",reservation);at(10,30,0);String before=managementFacts();var detail=management.detail(admin,id);assertEquals(before,managementFacts());@SuppressWarnings("unchecked") var current=(Map<String,Object>)detail.get("current_context");@SuppressWarnings("unchecked") var r=(Map<String,Object>)current.get("reservation");assertEquals("pending",r.get("status"));assertEquals(true,r.get("deadline_passed"));assertEquals(false,r.get("remaining_resource_legal"));assertNull(r.get("usage_record"));
        at(10,1,0);String raw=code(seat1);bookings.checkin(student,reservation,raw);at(11,1,0);before=managementFacts();detail=management.detail(admin,id);assertEquals(before,managementFacts());@SuppressWarnings("unchecked") var second=(Map<String,Object>)((Map<String,Object>)detail.get("current_context")).get("reservation");assertEquals("checked_in",second.get("status"));assertEquals(true,second.get("scheduled_end_passed"));assertNull(((Map<?,?>)second.get("usage_record")).get("ended_at"));assertEquals("pending",((Map<?,?>)detail.get("context_snapshot")).get("status_at_submission"));
    }
    @Test void currentCheckedInMissingUsageDiagnosesWithoutWritingFacts() {
        long reservation=reservation(student,seat1),id=accepted("occupation",reservation);db.update("UPDATE reservations SET status='checked_in' WHERE id=?",reservation);at(10,5,0);String before=managementFacts();assertThrows(IllegalStateException.class,()->management.detail(admin,id));assertEquals(before,managementFacts());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"accepted_event","accepted_audit","verification_event","reminder","verification_audit","version"})
    void dependencyFailureRollsBackEntireManagementWrite(String layer) throws Exception {
        boolean accept=layer.startsWith("accepted");long id=accept?submitted("service",null):accepted("occupation",reservation(student,seat1));at(10,30,0);String token=token("admin");String table=layer.endsWith("audit")?"audit_events":layer.equals("reminder")?"reminders":layer.equals("version")?"complaints":"complaint_events";String condition=layer.endsWith("audit")?"action<>'complaint."+(accept?"accept":"verify")+"'":layer.equals("reminder")?"false":layer.equals("version")?"version<3":"event_type<>'"+(accept?"accepted":"verification")+"'";String before=managementFacts();db.update("ALTER TABLE "+table+" ADD CONSTRAINT s15_fail CHECK("+condition+") NOT VALID");
        try {Object request=accept?new Requests.ComplaintAccept(1L,key()):verification(2,"cooperated",other);http.perform(body(post(accept?acceptPath(id):verifyPath(id)),request,token)).andExpect(status().isInternalServerError());assertEquals(before,managementFacts());}finally{db.update("ALTER TABLE "+table+" DROP CONSTRAINT s15_fail");}
    }
    @Test void httpInputWhitelistIntegerPrecisionAndMissingBusinessObjectsRemainStrict() throws Exception {
        long id=accepted("service",null);at(10,30,0);String token=token("admin");var base=new LinkedHashMap<String,Object>();base.put("version",2);base.put("operation_id",key());base.put("outcome","service_verified");base.put("occurred_at",this.base+"T10:05:00+08:00");base.put("public_note","公开进度");base.put("private_note","私有服务事实");base.put("facts_confirmed",true);
        for(String field:List.of("actor_id","handler_id","status","reporter_id")) {var modified=new LinkedHashMap<>(base);modified.put(field,other);http.perform(body(post(verifyPath(id)),modified,token)).andExpect(status().isBadRequest());}
        for(String field:List.of("version","subject_user_id")) {var modified=new LinkedHashMap<>(base);modified.put(field,2.5);http.perform(body(post(verifyPath(id)),modified,token)).andExpect(status().isBadRequest());}var invalid=new LinkedHashMap<>(base);invalid.put("facts_confirmed","true");http.perform(body(post(verifyPath(id)),invalid,token)).andExpect(status().isBadRequest());
        http.perform(auth(get("/api/admin/complaints/"+Long.MAX_VALUE),token)).andExpect(status().isNotFound());http.perform(auth(get("/api/reminders/mine"),token)).andExpect(status().isForbidden());http.perform(get("/api/reminders/mine")).andExpect(status().isUnauthorized());http.perform(body(post(verifyPath(id)),base,token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.complaint.status").value("processing")).andExpect(jsonPath("$.data.complaint.version").value(3));
    }
    @Test void realHttpAcceptVerifyOwnerProgressAndNeutralReminderAreCompleteAndReplayUsesCurrentDetail() throws Exception {
        long id=submitted("occupation",reservation(student,seat1));String adminToken=token("admin"),ownerToken=token("student"),subjectToken=token("other");var acceptance=new Requests.ComplaintAccept(1L,key());
        http.perform(body(post(acceptPath(id)),acceptance,adminToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.complaint.status").value("processing")).andExpect(jsonPath("$.data.complaint.handler.id").value(admin)).andExpect(jsonPath("$.data.complaint.version").value(2));at(10,30,0);
        var verification=verification(2,"cooperated",other);http.perform(body(post(verifyPath(id)),verification,adminToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.complaint.admin_events[2].subject.id").value(other)).andExpect(jsonPath("$.data.complaint.version").value(3));
        http.perform(auth(get("/api/complaints/"+id),ownerToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.handler.display_name").value("S15模拟管理员甲")).andExpect(jsonPath("$.data.handler.id").doesNotExist()).andExpect(jsonPath("$.data.events[2].public_note").value(verification.publicNote())).andExpect(jsonPath("$.data.events[2].private_note").doesNotExist()).andExpect(jsonPath("$.data.events[2].subject_user_id").doesNotExist());
        http.perform(auth(get("/api/reminders/mine"),subjectToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].counts_as_violation").value(false)).andExpect(jsonPath("$.data[0].public_note").value("管理员记录已配合纠正并给予提醒，本记录不计违规")).andExpect(jsonPath("$.data[0].complaint_id").doesNotExist());http.perform(auth(get("/api/reminders/mine"),ownerToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        http.perform(body(post(acceptPath(id)),acceptance,adminToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(true)).andExpect(jsonPath("$.data.complaint.version").value(3));
        var saved=db.one("SELECT operation_id,reservation_id FROM complaints WHERE id=?",id);http.perform(body(post("/api/complaints"),new Requests.Complaint("occupation",Store.id(saved,"reservation_id"),null,"工程模拟投诉，尚未核实",List.of(),saved.get("operation_id").toString()),ownerToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(true)).andExpect(jsonPath("$.data.status").value("processing")).andExpect(jsonPath("$.data.version").value(3));
        http.perform(auth(get("/api/admin/complaints").param("status","submitted"),adminToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));http.perform(auth(get("/api/admin/complaints").param("status","processing").param("room_id",Long.toString(room)),adminToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].reporter.id").value(student)).andExpect(jsonPath("$.data[0].events").doesNotExist()).andExpect(jsonPath("$.data[0].attachments").doesNotExist());
        var changed=new LinkedHashMap<String,Object>();changed.put("version",3);changed.put("operation_id",key());changed.put("outcome",123);http.perform(body(post(verifyPath(id)),changed,adminToken)).andExpect(status().isBadRequest());
    }
    @Test void differentAdministratorsAppendAtSameVersionOnlyOneWinsWithoutOverwriting() throws Exception {
        long id=accepted("service",null);at(10,30,0);var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);try {var jobs=new ArrayList<Future<Integer>>();for(long actor:List.of(admin,admin2)) jobs.add(pool.submit(()->{barrier.await();try {management.verify(actor,id,verification(2,"service_verified",null));return 200;}catch(Api.Failure failure){return failure.status;}}));var statuses=new ArrayList<Integer>();for(var job:jobs) statuses.add(job.get(20,TimeUnit.SECONDS));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);}finally{pool.shutdownNow();}assertEquals(1,db.count("SELECT count(*) FROM complaint_events WHERE event_type='verification'"));assertEquals(admin,Store.id(db.one("SELECT handler_id FROM complaints WHERE id=?",id),"handler_id"));
    }
}
