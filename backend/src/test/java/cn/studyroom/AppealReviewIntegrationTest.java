package cn.studyroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import org.springframework.mock.web.MockMultipartFile;
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
class AppealReviewIntegrationTest {
    @TestConfiguration static class TimeConfig { @Bean @Primary MinuteBookingIntegrationTest.TestClock testClock() { var c=new MinuteBookingIntegrationTest.TestClock();c.set(LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1).atTime(9,0));return c; } }
    @Autowired Store db;
    @Autowired AppealService appeals;
    @Autowired AppealReviewService reviews;
    @Autowired RestrictionPolicy policy;
    @Autowired ComplaintManagementService management;
    @Autowired RestrictionWorkflow restrictionWorkflow;
    @Autowired BookingService bookings;
    @Autowired SeatCodeService codes;
    @Autowired OpeningScheduleService opening;
    @Autowired ResourceService resources;
    @Autowired ComplaintService complaints;
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
        base=LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1);clock.set(base.atTime(9,0));remote="s22-review-test-"+UUID.randomUUID();
        String hash=new BCryptPasswordEncoder().encode("S02-test-only-password");
        admin=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s22r_admin',?,'admin','S09-admin')",hash);
        admin2=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s22r_admin2',?,'admin','S22-admin2')",hash);
        student=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s22r_student',?,'student','S09-student')",hash);
        staff=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s22r_staff',?,'staff','S09-staff')",hash);
        other=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s22r_other',?,'student','S14-other')",hash);
        room=db.insert("INSERT INTO rooms(name,seat_rows,seat_cols) VALUES('S14模拟空间',1,2)");opening.initializeDefaults(room);
        seat1=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'01',1,1)",room);seat2=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'02',1,2)",room);
    }
    @AfterEach void clearRate() { redis.delete("studyroom-java:rate:login:"+remote); }
    void at(int h,int m,int s) { clock.set(base.atTime(h,m,s)); }
    String token(String role) throws Exception {
        var result=http.perform(post("/api/auth/login").with(r->{r.setRemoteAddr(remote);return r;}).contentType("application/json").content(json.writeValueAsString(Map.of("username","s22r_"+role,"password","S02-test-only-password")))).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }
    long reservation(long user,long seat) { return Store.id(bookings.create(user,new Requests.Reservation(seat,base.toString(),"10:00","11:00")),"id"); }
    String code(long seat) { codes.generate(admin,seat,new Requests.SeatCodeChange(0L,"测试实际生成座位码"));return current(seat); }
    String current(long seat) { return Store.text(db.one("SELECT code_value FROM seat_codes WHERE seat_id=?",seat),"code_value"); }
    String path(long seat) { return "/api/admin/seats/"+seat+"/code"; }
    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder req,String token) { return req.header("Authorization","Bearer "+token); }
    MockHttpServletRequestBuilder checkin(long id,String code,String token) throws Exception { return auth(post("/api/reservations/"+id+"/checkin"),token).contentType("application/json").content(json.writeValueAsString(Map.of("seat_code",code))); }
    Map<String,Object> row(long id) { return db.one("SELECT * FROM reservations WHERE id=?",id); }
    static byte[] image(String format,int width,int height) throws Exception { var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);var output=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,format,output));image.flush();return output.toByteArray(); }

    String key(){return UUID.randomUUID().toString();}
    LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),BookingPolicy.CAMPUS_ZONE).truncatedTo(java.time.temporal.ChronoUnit.MICROS);}
    long violation(long owner,LocalDateTime notified){long reservation=db.insert("INSERT INTO reservations(user_id,seat_id,res_date,start_time,end_time,status,ended_at,termination_reason) VALUES(?,?,?,'08:00','09:00','unused_ended',?,'no_show')",owner,seat1,java.sql.Date.valueOf(base),java.sql.Timestamp.valueOf(base.atTime(8,15)));long id=db.insert("INSERT INTO violations(user_id,reservation_id,type,event_key,occurred_at,registered_at,status,reason,source) VALUES(?,?,'no_show',?,?,?,'active','S21明确工程模拟原违规事实说明','automatic')",owner,reservation,"unused:"+reservation,java.sql.Timestamp.valueOf(base.atTime(8,15)),java.sql.Timestamp.valueOf(base.atTime(8,20)));if(notified!=null)db.insert("INSERT INTO notifications(user_id,type,title,content,violation_id,created_at) VALUES(?,'violation','真实typed工程模拟通知','通知仅表明系统已生成，不证明用户已读',?,?)",owner,id,java.sql.Timestamp.valueOf(notified));return id;}
    Requests.Appeal request(List<String> ids){return new Requests.Appeal(key(),"  工程模拟：本人申诉说明，并非已经审核成立  ",ids);}
    long appealId(Map<String,Object> receipt){return ((Number)((Map<?,?>)receipt.get("appeal")).get("id")).longValue();}
    String createPath(long id){return "/api/violations/"+id+"/appeals";}
    String eligibilityPath(long id){return "/api/violations/"+id+"/appeal-eligibility";}
    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder req,Object payload,String token)throws Exception{return auth(req,token).contentType("application/json").content(json.writeValueAsString(payload));}
    String file(long owner,String format)throws Exception{return attachments.upload(owner,new MockMultipartFile("file","../../untrusted.html","text/html",image(format,8,8)),AttachmentService.Purpose.APPEAL).get("id").toString();}
    String tables(List<String> names){var result=new LinkedHashMap<String,Object>();for(String table:names)result.put(table,db.list("SELECT to_jsonb(t)::text AS value FROM "+table+" t ORDER BY to_jsonb(t)::text"));return result.toString();}
    String penalties(){return tables(List.of("users","reservations","usage_records","violations","restrictions","restriction_evaluations","restriction_evaluation_sources","notifications","credit_logs","complaints","complaint_events"));}
    String facts(){return penalties()+tables(List.of("appeals","appeal_events","appeal_reviews","restriction_recalculations","restriction_operations","evidence_attachments","audit_events"));}

    String offset(LocalDateTime value){return AttachmentService.offset(value);}
    long source(long user,LocalDateTime occurred){var start=occurred.minusMinutes(15);long reservation=db.insert("INSERT INTO reservations(user_id,seat_id,res_date,start_time,end_time,status,ended_at,termination_reason) VALUES(?,?,?,CAST(? AS TIME),CAST(? AS TIME),'unused_ended',?,'no_show')",user,seat1,java.sql.Date.valueOf(start.toLocalDate()),start.toLocalTime().toString(),start.plusMinutes(30).toLocalTime().toString(),java.sql.Timestamp.valueOf(occurred));long v=db.insert("INSERT INTO violations(user_id,reservation_id,type,event_key,occurred_at,registered_at,status,reason,source) VALUES(?,?,'no_show',?,?,?,'active','S22明确工程历史构型，未主张现场核实','automatic')",user,reservation,"unused:"+reservation,java.sql.Timestamp.valueOf(occurred),java.sql.Timestamp.valueOf(now()));db.insert("INSERT INTO notifications(user_id,type,title,content,violation_id,created_at) VALUES(?,'violation','工程typed事实','未修改原通知时刻',?,?)",user,v,java.sql.Timestamp.valueOf(now()));return v;}
    long[] batch(long user,int count){long[] ids=new long[count];for(int n=0;n<count;n++)ids[n]=source(user,now().minusDays(count-n));restrictionWorkflow.eligibility(user);return ids;}
    long automatic(long user){return Store.id(db.one("SELECT id FROM restrictions WHERE user_id=? AND source='automatic' ORDER BY id DESC LIMIT 1",user),"id");}
    String state(long id){return Store.text(policy.detail(id,now(),false),"status");}
    long submit(long user,long v)throws Exception{return appealId(appeals.create(user,v,request(List.of())));}
    Requests.AppealReview intent(long appeal,String decision){return new Requests.AppealReview(Store.id(db.one("SELECT version FROM appeals WHERE id=?",appeal),"version"),decision,"  PUBLIC-S22 审核公开理由。  "," PRIVATE-S22 仅管理员主动自报核实依据 ",true);}
    Requests.AppealReviewConfirm confirmation(long actor,long appeal,String decision){var request=intent(appeal,decision);var preview=reviews.preview(actor,appeal,request);return new Requests.AppealReviewConfirm(request.version(),request.decision(),request.reason(),request.verificationNote(),true,preview.get("impact_hash").toString(),key());}
    String route(long appeal,String action){return "/api/admin/appeals/"+appeal+"/review/"+action;}
    @SuppressWarnings("unchecked")Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked")List<Map<String,Object>> rows(Object value){return (List<Map<String,Object>>)value;}
    long recoveredPending(){return db.insert("INSERT INTO reservations(user_id,seat_id,res_date,start_time,end_time,status) VALUES(?,?,?,'12:00','13:00','pending')",student,seat2,java.sql.Date.valueOf(base.plusDays(1)));}
    @Test void approvalReleasesOnlyOriginalBatchPreservesOriginalFactsAndProjectsPublicPrivateBoundaries()throws Exception{
        String owner=token("student"),administrator=token("admin");long[] ids=batch(student,3);long original=automatic(student),appeal=submit(student,ids[0]);String permanent=tables(List.of("restriction_evaluations","restriction_evaluation_sources","reservations","usage_records","credit_logs"));var originalViolation=db.one("SELECT * FROM violations WHERE id=?",ids[0]);var originalNotification=db.one("SELECT * FROM notifications WHERE violation_id=?",ids[0]);String before=facts();var projected=reviews.preview(admin,appeal,intent(appeal,"approved"));assertEquals(before,facts());var gap=rows(projected.get("automatic_recalculations")).get(0);assertEquals(3,gap.get("valid_count_before"));assertEquals(2,gap.get("valid_count_after"));assertEquals("released",gap.get("outcome"));assertEquals("active",rows(gap.get("source_violations")).get(0).get("status"));assertEquals("revoked",rows(gap.get("source_violations")).get(0).get("status_after"));
        var request=confirmation(admin,appeal,"approved");var response=http.perform(body(post(route(appeal,"confirm")),request,administrator)).andExpect(status().isOk()).andExpect(jsonPath("$.data.appeal.status").value("approved")).andExpect(jsonPath("$.data.effects.violation_action").value("revoked")).andExpect(jsonPath("$.data.eligibility.can_book").value(true)).andExpect(jsonPath("$.data.review.verification_note").value("PRIVATE-S22 仅管理员主动自报核实依据")).andReturn();assertEquals("released",state(original));assertEquals(permanent,tables(List.of("restriction_evaluations","restriction_evaluation_sources","reservations","usage_records","credit_logs")));var changed=db.one("SELECT * FROM violations WHERE id=?",ids[0]);changed.put("status","active");assertEquals(originalViolation,changed);assertEquals(originalNotification,db.one("SELECT * FROM notifications WHERE violation_id=?",ids[0]));
        var safe=http.perform(auth(get("/api/appeals/"+appeal),owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.review.decision").value("approved")).andExpect(jsonPath("$.data.review.verification_note").doesNotExist()).andExpect(jsonPath("$.data.current_eligibility.can_book").value(true)).andReturn().getResponse().getContentAsString();assertFalse(safe.contains("PRIVATE-S22"));assertFalse(safe.contains("management_context"));assertTrue(json.readTree(response.getResponse().getContentAsString()).path("data").path("effects").path("automatic_recalculations").get(0).path("source_violations").get(0).path("status_after").asText().equals("revoked"));http.perform(auth(get("/api/violations/mine"),owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data[?(@.id=="+ids[0]+")].revocation.reason").value(org.hamcrest.Matchers.hasItem("PUBLIC-S22 审核公开理由。")));assertEquals(1,db.count("SELECT count(*) FROM notifications WHERE appeal_id=? AND user_id=? AND type='appeal_reviewed'",appeal,student));assertEquals(1,db.count("SELECT count(*) FROM appeal_reviews"));assertEquals(1,db.count("SELECT count(*) FROM restriction_recalculations"));http.perform(auth(get("/api/admin/appeals?status=approved"),administrator)).andExpect(jsonPath("$.data.length()").value(1));http.perform(auth(get("/api/admin/appeals"),administrator)).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].review.verification_note").doesNotExist());
    }
    @Test void approvalMayCreateNextBatchFromOnlyOtherUnconsumedSourcesAndDoesNotRestoreOldReservations()throws Exception{
        long[] ids=batch(student,6);long first=automatic(student),appeal=submit(student,ids[0]);long pending=recoveredPending();var outcome=reviews.confirm(admin,appeal,confirmation(admin,appeal,"approved"));assertEquals("released",state(first));assertEquals(false,map(outcome.get("eligibility")).get("can_book"));long second=automatic(student);assertNotEquals(first,second);assertEquals("active",state(second));assertEquals(List.of(ids[3],ids[4],ids[5]),db.list("SELECT violation_id FROM restriction_evaluation_sources WHERE restriction_id=? ORDER BY source_order",second).stream().map(r->Store.id(r,"violation_id")).toList());assertEquals(6,db.count("SELECT count(*) FROM restriction_evaluation_sources"));assertEquals("cancelled",row(pending).get("status"));assertEquals(0,db.count("SELECT count(*) FROM usage_records"));assertEquals("unused_ended",db.one("SELECT status FROM reservations WHERE id=(SELECT reservation_id FROM violations WHERE id=?)",ids[0]).get("status"));assertEquals("released",rows(map(outcome.get("effects")).get("automatic_recalculations")).get(0).get("outcome"));
    }
    @Test void rejectChangesNoPenaltyCoreAndAllowsSupplementOnlyInsideOriginalNotificationWindow()throws Exception{
        long id=source(student,now().minusHours(1)),appeal=submit(student,id);source(student,now().minusMinutes(40));source(student,now().minusMinutes(20));long pending=recoveredPending();String unchanged=tables(List.of("violations","restrictions","restriction_evaluations","restriction_evaluation_sources","reservations","usage_records","credit_logs"));var original=appeals.detail(student,appeal);db.update("ALTER TABLE restriction_evaluation_sources ADD CONSTRAINT s22_no_assess CHECK(source_order<>3) NOT VALID");try{var result=reviews.confirm(admin,appeal,confirmation(admin,appeal,"rejected"));assertEquals(unchanged,tables(List.of("violations","restrictions","restriction_evaluations","restriction_evaluation_sources","reservations","usage_records","credit_logs")));assertEquals(List.of(),map(result.get("effects")).get("automatic_recalculations"));assertNull(map(result.get("effects")).get("booking_effects"));assertEquals("pending",row(pending).get("status"));long second=submit(student,id);assertNotEquals(appeal,second);assertEquals(original.get("notification_basis"),appeals.detail(student,second).get("notification_basis"));}finally{db.update("ALTER TABLE restriction_evaluation_sources DROP CONSTRAINT s22_no_assess");}
    }
    @Test void timelySubmittedReviewWorksAfterSevenDaysAndExpiredOriginalBatchIsNotReleasedAgain()throws Exception{
        long[] ids=batch(student,3);long original=automatic(student),appeal=submit(student,ids[0]);String restriction=tables(List.of("restrictions"));clock.set(now().plusHours(169));var receipt=reviews.confirm(admin,appeal,confirmation(admin,appeal,"approved"));assertEquals("approved",map(receipt.get("appeal")).get("status"));assertEquals("expired_recorded",rows(map(receipt.get("effects")).get("automatic_recalculations")).get(0).get("outcome"));assertEquals("expired",state(original));assertEquals(restriction,tables(List.of("restrictions")));assertEquals(0,db.count("SELECT count(*) FROM notifications WHERE restriction_id=? AND type='restriction_released'",original));
    }
    @Test void releasedOriginalBatchAndIndependentManualRestrictionRemainHistoricallyCorrect()throws Exception{
        long[] ids=batch(student,3);long original=automatic(student),appeal=submit(student,ids[0]);restrictionWorkflow.release(admin,original,new Requests.RestrictionRelease(Store.id(policy.required(original),"version"),"明确工程模拟先前解除","私有先前解除依据",true,key()));long manual=Store.id(map(restrictionWorkflow.create(admin,new Requests.RestrictionCreate(student,offset(now().plusDays(2)),"独立管理限制","自报核实私有依据",true,key())).get("restriction")),"id");String fixed=tables(List.of("restrictions","restriction_evaluations","restriction_evaluation_sources"));var receipt=reviews.confirm(admin,appeal,confirmation(admin,appeal,"approved"));assertEquals("released_recorded",rows(map(receipt.get("effects")).get("automatic_recalculations")).get(0).get("outcome"));assertEquals(fixed,tables(List.of("restrictions","restriction_evaluations","restriction_evaluation_sources")));assertEquals("active",state(manual));assertEquals(false,map(receipt.get("eligibility")).get("can_book"));
    }
    @Test void alreadyRevokedUnknownHistoryDoesNotInventOrReplaceRevocationMetadata()throws Exception{
        long[] ids=batch(student,3);long appeal=submit(student,ids[0]);db.update("UPDATE violations SET status='revoked' WHERE id=?",ids[0]);var result=reviews.confirm(admin,appeal,confirmation(admin,appeal,"approved"));assertEquals("already_revoked",map(result.get("effects")).get("violation_action"));assertNull(ViolationService.safeOne(db,ids[0]).get("revocation"));assertEquals(0,db.count("SELECT count(*) FROM audit_events WHERE action='violation.revoke'"));assertEquals(2,rows(map(result.get("effects")).get("automatic_recalculations")).get(0).get("valid_count_before"));
    }
    @Test void unconsumedViolationApprovalHasNoFabricatedOriginalBatchAndKeepsPhotos()throws Exception{long v=source(student,now().minusHours(1));String photo=file(student,"png");long appeal=appealId(appeals.create(student,v,request(List.of(photo))));String bytes=tables(List.of("evidence_attachments"));var receipt=reviews.confirm(admin,appeal,confirmation(admin,appeal,"approved"));assertEquals(List.of(),map(receipt.get("effects")).get("automatic_recalculations"));assertEquals(bytes,tables(List.of("evidence_attachments")));assertEquals(0,db.count("SELECT count(*) FROM restrictions"));assertEquals(1,rows(map(receipt.get("appeal")).get("attachments")).size());}
    @Test void elapsedSecondsKeepPreviewValidButSemanticBoundaryInvalidatesWithoutAnyWrite()throws Exception{
        long[] ids=batch(student,3);long appeal=submit(student,ids[0]);var intent=intent(appeal,"approved");String hash=reviews.preview(admin,appeal,intent).get("impact_hash").toString();clock.set(now().plusSeconds(2));assertEquals(hash,reviews.preview(admin,appeal,intent).get("impact_hash"));var old=new Requests.AppealReviewConfirm(intent.version(),intent.decision(),intent.reason(),intent.verificationNote(),true,hash,key());clock.set(now().plusHours(168));String before=facts();assertEquals(409,assertThrows(Api.Failure.class,()->reviews.confirm(admin,appeal,old)).status);assertEquals(before,facts());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"new_candidate","source_revoked","independent_restriction","candidate_window","pending_deadline","user_disabled"})
    void semanticChangesRequireNewPreviewAndOldConfirmationWritesNothing(String change)throws Exception{
        long[] ids=batch(student,3);long appeal=submit(student,ids[0]);long candidate=source(student,now().minusHours(720).plusSeconds(1));if(change.equals("pending_deadline"))db.insert("INSERT INTO reservations(user_id,seat_id,res_date,start_time,end_time,status) VALUES(?,?,?,'08:46','10:00','pending')",student,seat2,java.sql.Date.valueOf(base));var request=confirmation(admin,appeal,"approved");switch(change){case "new_candidate"->source(student,now().minusMinutes(1));case "source_revoked"->db.update("UPDATE violations SET status='revoked' WHERE id=?",ids[1]);case "independent_restriction"->restrictionWorkflow.create(admin,new Requests.RestrictionCreate(student,offset(now().plusDays(2)),"独立新限制","管理员核实自报",true,key()));case "candidate_window","pending_deadline"->clock.set(now().plusSeconds(61));default->db.update("UPDATE users SET status='disabled' WHERE id=?",student);}String before=facts();assertEquals(409,assertThrows(Api.Failure.class,()->reviews.confirm(admin,appeal,request)).status);assertEquals(before,facts());assertEquals(0,db.count("SELECT count(*) FROM appeal_reviews"));
    }
    @Test void oldSuccessfulReviewAndCreateReplayArePureEvenWithNewBacklogAndFailingAssessment()throws Exception{
        long v=source(student,now().minusHours(1));var creation=request(List.of());long appeal=appealId(appeals.create(student,v,creation));var request=confirmation(admin,appeal,"approved");reviews.confirm(admin,appeal,request);source(student,now().minusMinutes(30));source(student,now().minusMinutes(20));source(student,now().minusMinutes(10));db.update("ALTER TABLE restriction_evaluation_sources ADD CONSTRAINT s22_replay_assess CHECK(source_order<>3) NOT VALID");String before=facts();try{assertEquals(true,reviews.confirm(admin,appeal,request).get("replayed"));assertEquals(true,appeals.create(student,v,creation).get("replayed"));assertEquals(before,facts());assertEquals(409,assertThrows(Api.Failure.class,()->reviews.confirm(admin2,appeal,request)).status);}finally{db.update("ALTER TABLE restriction_evaluation_sources DROP CONSTRAINT s22_replay_assess");}
    }
    @Test void twoAdminsRaceOneWinnerAndSameOperationConcurrentRetryHasOneFact()throws Exception{
        long[] ids=batch(student,3);long appeal=submit(student,ids[0]);var a=confirmation(admin,appeal,"approved");var b=confirmation(admin2,appeal,"rejected");var pool=Executors.newFixedThreadPool(2);var latch=new CountDownLatch(1);try{var x=pool.submit(()->{latch.await();try{reviews.confirm(admin,appeal,a);return 200;}catch(Api.Failure e){return e.status;}});var y=pool.submit(()->{latch.await();try{reviews.confirm(admin2,appeal,b);return 200;}catch(Api.Failure e){return e.status;}});latch.countDown();var statuses=new ArrayList<>(List.of(x.get(),y.get()));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);assertEquals(1,db.count("SELECT count(*) FROM appeal_reviews"));assertEquals(1,db.count("SELECT count(*) FROM notifications WHERE appeal_id=?",appeal));}finally{pool.shutdownNow();}
        long v=source(staff,now().minusHours(1)),second=submit(staff,v);var same=confirmation(admin,second,"approved");var otherPool=Executors.newFixedThreadPool(2);try{var x=otherPool.submit(()->reviews.confirm(admin,second,same));var y=otherPool.submit(()->reviews.confirm(admin,second,same));assertNotEquals(x.get().get("replayed"),y.get().get("replayed"));assertEquals(1,db.count("SELECT count(*) FROM appeal_reviews WHERE appeal_id=?",second));}finally{otherPool.shutdownNow();}
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"review","revocation","recalculation","release","new_batch","new_sources","cancel_notification","review_notification","audit","effects"})
    void anyCriticalDependencyFailureRollsBackReviewRevocationRecalculationNewBatchCancellationAndAllMessages(String fault)throws Exception{
        String administrator=token("admin");long[] ids=batch(student,6);long original=automatic(student),appeal=submit(student,ids[0]);recoveredPending();var request=confirmation(admin,appeal,"approved");String table,expression;switch(fault){case "review"->{table="appeal_reviews";expression="decision<>'approved'";}case "effects"->{table="appeal_reviews";expression="booking_effects IS NULL";}case "revocation"->{table="violations";expression="status<>'revoked'";}case "recalculation"->{table="restriction_recalculations";expression="valid_count_after>=3";}case "release"->{table="restrictions";expression="released_at IS NULL";}case "new_batch"->{table="restrictions";expression="source<>'automatic' OR id="+original;}case "new_sources"->{table="restriction_evaluation_sources";expression="source_order<>3";}case "cancel_notification"->{table="notifications";expression="type<>'restriction_cancelled'";}case "review_notification"->{table="notifications";expression="type<>'appeal_reviewed'";}default->{table="audit_events";expression="action<>'appeal.review'";}}String before=facts();db.update("ALTER TABLE "+table+" ADD CONSTRAINT s22_fault CHECK("+expression+") NOT VALID");try{http.perform(body(post(route(appeal,"confirm")),request,administrator)).andExpect(status().isInternalServerError());assertEquals(before,facts());assertEquals("active",state(original));assertEquals("active",db.one("SELECT status FROM violations WHERE id=?",ids[0]).get("status"));assertEquals("submitted",db.one("SELECT status FROM appeals WHERE id=?",appeal).get("status"));}finally{db.update("ALTER TABLE "+table+" DROP CONSTRAINT s22_fault");}
    }
    @Test void authenticatedAdminOnlyAndExactJsonTypesCannotOverrideIdentityOrTruncateVersions()throws Exception{
        String administrator=token("admin"),owner=token("student");long id=source(student,now().minusHours(1)),appeal=submit(student,id);var payload=new LinkedHashMap<String,Object>();payload.put("version",1);payload.put("decision","approved");payload.put("reason","审核公开理由");payload.put("verification_note","私有核实");payload.put("facts_confirmed",true);http.perform(post(route(appeal,"preview")).contentType("application/json").content(json.writeValueAsString(payload))).andExpect(status().isUnauthorized());http.perform(body(post(route(appeal,"preview")),payload,owner)).andExpect(status().isForbidden());for(Object invalid:List.of(1.5,"1")){payload.put("version",invalid);http.perform(body(post(route(appeal,"preview")),payload,administrator)).andExpect(status().isBadRequest());}payload.put("version",1);payload.put("facts_confirmed","true");http.perform(body(post(route(appeal,"preview")),payload,administrator)).andExpect(status().isBadRequest());payload.put("facts_confirmed",false);http.perform(body(post(route(appeal,"preview")),payload,administrator)).andExpect(status().isBadRequest());payload.put("facts_confirmed",true);payload.put("actor_id",admin);http.perform(body(post(route(appeal,"preview")),payload,administrator)).andExpect(status().isBadRequest());assertEquals(0,db.count("SELECT count(*) FROM appeal_reviews"));
    }
}
