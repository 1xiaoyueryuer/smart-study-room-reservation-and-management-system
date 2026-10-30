package cn.studyroom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
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

/** S31 cross-domain HTTP engineering journey against the dedicated test database.
 * MockMvc and a simulated Clock exercise real HTTP, PostgreSQL, Redis and PNG decoding.
 * This is not a campus incident, real mobile scan, independent human PV or network test.
 * SQL only supplies clearly labelled foundation users/room/seats; all business facts use HTTP.
 */
@EnabledIfEnvironmentVariable(named="STUDYROOM_INTEGRATION",matches="true")
@SpringBootTest(properties={"spring.datasource.url=${STUDYROOM_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5433/studyroom_java_test}","studyroom.seed=false","studyroom.scheduler-enabled=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class FullJourneyIntegrationTest {
    @TestConfiguration static class TimeConfig {
        @Bean @Primary MinuteBookingIntegrationTest.TestClock testClock(){var value=new MinuteBookingIntegrationTest.TestClock();value.set(LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1).atTime(9,0));return value;}
    }
    @Autowired Store db;@Autowired OpeningScheduleService opening;@Autowired MockMvc http;
    @Autowired ObjectMapper json;@Autowired StringRedisTemplate redis;@Autowired MinuteBookingIntegrationTest.TestClock clock;
    long admin,owner,reporter,room,seat1,seat2,lastCreateNanos;
    LocalDate base;String adminToken,ownerToken,reporterToken,remote;
    static final String PASSWORD="S02-test-only-password",PRIVATE="S31_PRIVATE_FACTS",IDENTITY="S31_IDENTITY_PRIVATE";
    @BeforeEach void fixture()throws Exception {
        assertEquals("studyroom_java_test",db.one("SELECT current_database() AS name").get("name"),"never clean a development database");
        TestDatabaseCleanup.clear(db);base=LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1);clock.set(base.atTime(9,0));remote="s31-journey-"+UUID.randomUUID();
        String hash=new BCryptPasswordEncoder().encode(PASSWORD);
        admin=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s31_j_admin',?,'admin','S31-J-ADMIN')",hash);
        owner=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s31_j_owner',?,'student','S31-J-OWNER')",hash);
        reporter=db.insert("INSERT INTO users(username,password_hash,role,campus_id,real_name) VALUES('s31_j_reporter',?,'staff','S31-J-REPORTER',?)",hash,IDENTITY);
        room=db.insert("INSERT INTO rooms(name,seat_rows,seat_cols) VALUES('S31 explicit engineering journey',1,2)");opening.initializeDefaults(room);
        seat1=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'01',1,1)",room);
        seat2=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'02',1,2)",room);
        adminToken=login("admin");ownerToken=login("owner");reporterToken=login("reporter");
    }
    @AfterEach void cleanupOwnRateKeys(){redis.delete("studyroom-java:rate:login:"+remote);for(long user:List.of(owner,reporter))redis.delete("studyroom-java:rate:reserve:"+user);}
    String key(){return UUID.randomUUID().toString();}
    void at(int hour,int minute){clock.set(base.atTime(hour,minute));}
    String login(String role)throws Exception{return call(post("/api/auth/login").with(request->{request.setRemoteAddr(remote);return request;}),null,Map.of("username","s31_j_"+role,"password",PASSWORD)).path("token").asText();}
    JsonNode call(MockHttpServletRequestBuilder request,String token,Object body)throws Exception {
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(body!=null)request.contentType("application/json").content(json.writeValueAsString(body));
        var response=http.perform(request).andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("data");
    }
    JsonNode getData(String path,String token)throws Exception{return call(get(path),token,null);}
    long reserve(String token,long seat,String start,String end)throws Exception {
        // Respect the real 2/s Redis limiter; never delete or bypass it within the journey.
        long delay=lastCreateNanos==0?1100:600-(System.nanoTime()-lastCreateNanos)/1_000_000;
        if(delay>0)Thread.sleep(delay);lastCreateNanos=System.nanoTime();
        return call(post("/api/reservations"),token,new Requests.Reservation(seat,base.toString(),start,end)).path("id").asLong();
    }
    String configureAndDecodePng(long seat)throws Exception {
        var metadata=call(post("/api/admin/seats/"+seat+"/code"),adminToken,new Requests.SeatCodeChange(0L,"S31 explicit engineering code configuration"));
        assertTrue(metadata.path("configured").asBoolean());assertFalse(metadata.has("code_value"));
        byte[] png=http.perform(get("/api/admin/seats/"+seat+"/code/png").param("expected_version","1").header("Authorization","Bearer "+adminToken)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        return UsageIntegrationTest.decode(png);
    }
    String state(long reservation){return db.one("SELECT status FROM reservations WHERE id=?",reservation).get("status").toString();}
    void noPrivate(JsonNode value){for(String sentinel:List.of(PRIVATE,IDENTITY,"identity_basis","verification_note","private_note"))assertFalse(value.toString().contains(sentinel),sentinel);}
    String facts(){var result=new TreeMap<String,Object>();for(String table:List.of("reservations","usage_records","reviews","review_operations","violations","restrictions","restriction_evaluations","restriction_evaluation_sources","restriction_recalculations","appeals","appeal_reviews","complaints","complaint_events","resource_change_operations","notifications","audit_events"))result.put(table,db.list("SELECT to_jsonb(t)::text AS row FROM "+table+" t ORDER BY to_jsonb(t)::text"));return result.toString();}

    @Test void genuineHttpJourneyKeepsEachDomainFactAndDoesNotReviveCancelledHistory()throws Exception {
        String firstCode=configureAndDecodePng(seat1),secondCode=configureAndDecodePng(seat2);
        long used=reserve(ownerToken,seat1,"09:10","10:00");at(9,10);
        var checked=call(post("/api/reservations/"+used+"/checkin"),ownerToken,Map.of("seat_code",firstCode));assertEquals("checked_in",checked.path("status").asText());
        at(9,40);var completed=call(post("/api/reservations/"+used+"/checkout"),ownerToken,null);
        assertEquals("completed",completed.path("status").asText());assertEquals(0,new BigDecimal("1800").compareTo(completed.path("usage_record").path("duration_seconds").decimalValue()));
        assertEquals(1,db.count("SELECT count(*) FROM usage_records WHERE reservation_id=?",used));

        String reviewPath="/api/reservations/"+used+"/review";
        var created=call(post(reviewPath),ownerToken,Map.of("operation_id",key(),"rating",5,"content","S31 engineering first review"));long review=created.path("review").path("id").asLong();
        call(put(reviewPath),ownerToken,Map.of("operation_id",key(),"version",1,"rating",4,"content","S31 engineering corrected review"));
        call(post("/api/admin/reviews/"+review+"/reply"),adminToken,Map.of("operation_id",key(),"version",2,"reply","S31 public management reply"));
        call(post("/api/admin/reviews/"+review+"/hide"),adminToken,Map.of("operation_id",key(),"version",3,"reason","S31 public moderation reason"));
        call(put(reviewPath),ownerToken,Map.of("operation_id",key(),"version",4,"rating",3,"content","S31 owner can edit a hidden review"));
        var currentReview=getData(reviewPath,ownerToken).path("review");assertTrue(currentReview.path("hidden").asBoolean());assertEquals(5,currentReview.path("version").asInt());assertEquals("S31 public management reply",currentReview.path("admin_reply").asText());
        assertEquals(0,getData("/api/rooms/"+room+"/reviews",reporterToken).path("total").asInt());

        // Reporter owns a different actual reservation. The affected owner is the self-reported subject.
        long reportedReservation=reserve(reporterToken,seat2,"10:00","11:00");at(10,5);
        long complaint=call(post("/api/complaints"),reporterToken,new Requests.Complaint("occupation",reportedReservation,null,"S31 engineering occupation report, not an established physical campus incident",List.of(),key())).path("id").asLong();
        call(post("/api/admin/complaints/"+complaint+"/accept"),adminToken,new Requests.ComplaintAccept(1L,key()));at(10,6);
        long event=call(post("/api/admin/complaints/"+complaint+"/verifications"),adminToken,new Requests.ComplaintVerification(2L,key(),"verified_refusal",base+"T10:05:00+08:00","S31 public engineering verification",PRIVATE,owner,true,IDENTITY,true,true)).path("event_id").asLong();
        long manual=call(post("/api/admin/complaint-events/"+event+"/violations"),adminToken,new Requests.ManualViolation(key(),"S31 public manually confirmed violation basis")).path("violation").path("id").asLong();
        noPrivate(getData("/api/violations/"+manual,ownerToken));
        // Later actual code operations finish the reporter's booking without inventing another violation.
        at(10,7);call(post("/api/reservations/"+reportedReservation+"/checkin"),reporterToken,Map.of("seat_code",secondCode));
        at(10,8);call(post("/api/reservations/"+reportedReservation+"/checkout"),reporterToken,null);
        call(post("/api/admin/complaints/"+complaint+"/feedback"),adminToken,new Requests.ComplaintFeedback(3L,key(),"S31 public engineering handling result","S31 public handling basis"));
        call(post("/api/complaints/"+complaint+"/confirm"),reporterToken,Map.of("version",4,"operation_id",key()));
        assertEquals("closed",db.one("SELECT status FROM complaints WHERE id=?",complaint).get("status"));assertEquals(1,db.count("SELECT count(*) FROM notifications WHERE complaint_id=? AND complaint_event_id IS NOT NULL AND type='complaint_feedback'",complaint));
        assertFalse(getData("/api/complaints/"+complaint,reporterToken).toString().contains(PRIVATE));

        long noShowOne=reserve(ownerToken,seat1,"11:00","11:30"),noShowTwo=reserve(ownerToken,seat1,"11:30","12:00"),pending=reserve(ownerToken,seat1,"13:00","14:00");
        at(12,0);getData("/api/reservations/mine",ownerToken);
        assertEquals("unused_ended",state(noShowOne));assertEquals("unused_ended",state(noShowTwo));assertEquals("cancelled",state(pending));
        assertEquals(3,db.count("SELECT count(*) FROM violations WHERE user_id=? AND status='active'",owner));
        var restriction=db.one("SELECT id,starts_at,ends_at FROM restrictions WHERE user_id=? AND source='automatic'",owner);long restrictionId=Store.id(restriction,"id");
        assertEquals(Duration.ofDays(7),Duration.between(LocalDateTime.parse(restriction.get("starts_at").toString()),LocalDateTime.parse(restriction.get("ends_at").toString())));
        var evaluation=db.one("SELECT threshold,duration_hours,window_start,window_end FROM restriction_evaluations WHERE restriction_id=?",restrictionId);assertEquals(3,Store.id(evaluation,"threshold"));assertEquals(168,Store.id(evaluation,"duration_hours"));assertEquals(Duration.ofDays(30),Duration.between(LocalDateTime.parse(evaluation.get("window_start").toString()),LocalDateTime.parse(evaluation.get("window_end").toString())));
        var sources=db.list("SELECT * FROM restriction_evaluation_sources WHERE restriction_id=? ORDER BY source_order",restrictionId);assertEquals(3,sources.size());assertEquals(manual,Store.id(sources.get(0),"violation_id"));
        assertFalse(getData("/api/booking-eligibility",ownerToken).path("can_book").asBoolean());
        assertEquals(1,db.count("SELECT count(*) FROM notifications WHERE reservation_id=? AND restriction_id=? AND type='restriction_cancelled'",pending,restrictionId));

        assertTrue(getData("/api/violations/"+manual+"/appeal-eligibility",ownerToken).path("eligibility").path("can_submit").asBoolean());
        long appeal=call(post("/api/violations/"+manual+"/appeals"),ownerToken,new Requests.Appeal(key(),"S31 engineering appeal for rechecking the original manually registered source",List.of())).path("appeal").path("id").asLong();
        String reviewRoute="/api/admin/appeals/"+appeal+"/review/";
        var intent=new Requests.AppealReview(1L,"approved","S31 public appeal decision",PRIVATE,true);String before=facts();
        var preview=call(post(reviewRoute+"preview"),adminToken,intent);assertEquals(before,facts());assertEquals("released",preview.path("automatic_recalculations").get(0).path("outcome").asText());
        var confirmation=new Requests.AppealReviewConfirm(1L,"approved",intent.reason(),intent.verificationNote(),true,preview.path("impact_hash").asText(),key());
        var decision=call(post(reviewRoute+"confirm"),adminToken,confirmation);assertTrue(decision.path("eligibility").path("can_book").asBoolean());assertEquals("revoked",db.one("SELECT status FROM violations WHERE id=?",manual).get("status"));
        assertEquals("released",getData("/api/restrictions/"+restrictionId,ownerToken).path("status").asText());assertNotNull(db.one("SELECT released_at FROM restrictions WHERE id=?",restrictionId).get("released_at"));assertEquals(sources,db.list("SELECT * FROM restriction_evaluation_sources WHERE restriction_id=? ORDER BY source_order",restrictionId));
        assertEquals("unused_ended",state(noShowOne));assertEquals("unused_ended",state(noShowTwo));assertEquals("cancelled",state(pending));assertEquals("completed",state(used));
        noPrivate(getData("/api/appeals/"+appeal,ownerToken));noPrivate(getData("/api/restrictions/"+restrictionId,ownerToken));
        at(12,1);long recovered=reserve(ownerToken,seat1,"14:00","15:00");assertEquals("pending",state(recovered));

        long version=Store.id(db.one("SELECT version FROM rooms WHERE id=?",room),"version");
        var roomBody=new Requests.Room("S31 explicit engineering journey",null,null,null,1,2,null,null,null,false,version,"S31 public resource closure");
        @SuppressWarnings("unchecked") var payload=json.convertValue(roomBody,Map.class);
        var proposal=new Requests.ResourceChangeProposal("room.update",room,payload);String preResource=facts();
        var impact=call(post("/api/admin/resource-changes/preview"),adminToken,proposal);assertEquals(preResource,facts());assertEquals(1,impact.path("pending").size());assertEquals(0,impact.path("in_use").size());
        var resourceBody=Map.of("proposal",impact.path("normalized_proposal"),"impact_hash",impact.path("impact_hash").asText(),"operation_id",key());
        assertEquals(1,call(post("/api/admin/resource-changes/confirm"),adminToken,resourceBody).path("cancelled_count").asInt());assertEquals("cancelled",state(recovered));
        String afterResource=facts();assertTrue(call(post("/api/admin/resource-changes/confirm"),adminToken,resourceBody).path("replayed").asBoolean());assertEquals(afterResource,facts());
        assertEquals(3,db.count("SELECT count(*) FROM violations WHERE user_id=?",owner));assertEquals(1,db.count("SELECT count(*) FROM notifications WHERE reservation_id=? AND type='management_cancelled'",recovered));
        assertEquals(1,db.count("SELECT count(*) FROM notifications WHERE violation_id=?",manual));assertEquals(1,db.count("SELECT count(*) FROM notifications WHERE appeal_id=? AND type='appeal_reviewed'",appeal));
        assertEquals(0,db.count("SELECT count(*) FROM (SELECT user_id,event_key FROM notifications GROUP BY user_id,event_key HAVING count(*)>1) duplicates"));
        noPrivate(getData("/api/notifications?page_size=50",ownerToken));
        assertTrue(call(post("/api/notifications/read_all"),ownerToken,null).path("marked").asInt()>0);assertEquals(0,call(post("/api/notifications/read_all"),ownerToken,null).path("marked").asInt());
        var audits=getData("/api/admin/audit-events?page_size=50",adminToken);noPrivate(audits);
        long auditId=Store.id(db.one("SELECT id FROM audit_events WHERE action='violation.register_manual'"),"id");noPrivate(getData("/api/admin/audit-events/"+auditId,adminToken));
        assertEquals(1,db.count("SELECT count(*) FROM audit_events WHERE action='resource_change.confirm'"));assertEquals(1,db.count("SELECT count(*) FROM audit_events WHERE action='appeal.review'"));
        assertEquals(0,db.count("SELECT count(*) FROM credit_logs"));
        assertTrue(getData("/api/stats/overview?room_id="+room,adminToken).path("current").path("usage_ratio").isNull());
    }
}
