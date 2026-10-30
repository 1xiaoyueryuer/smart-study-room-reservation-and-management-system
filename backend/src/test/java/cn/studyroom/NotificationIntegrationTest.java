package cn.studyroom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
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
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** S27 engineering fixtures exercise real HTTP, typed events and owner boundaries. */
@EnabledIfEnvironmentVariable(named="STUDYROOM_INTEGRATION",matches="true")
@SpringBootTest(properties={"spring.datasource.url=${STUDYROOM_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5433/studyroom_java_test}","studyroom.seed=false","studyroom.scheduler-enabled=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class NotificationIntegrationTest {
    @TestConfiguration static class TimeConfig {@Bean @Primary MinuteBookingIntegrationTest.TestClock testClock(){var c=new MinuteBookingIntegrationTest.TestClock();c.set(LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1).atTime(9,0));return c;}}
    @Autowired NotificationService notifications;@Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired Store db;@Autowired MockMvc http;@Autowired ObjectMapper json;@Autowired StringRedisTemplate redis;
    @Autowired BookingService bookings;@Autowired OpeningScheduleService opening;@Autowired SeatCodeService codes;
    @Autowired ComplaintService complaints;@Autowired ComplaintManagementService management;@Autowired ComplaintWorkflowService workflow;
    @Autowired MinuteBookingIntegrationTest.TestClock clock;
    long owner,other,admin,room,seat;LocalDate base;String remote,ownerToken,otherToken;
    @BeforeEach void fixture()throws Exception{
        assertEquals("studyroom_java_test",db.one("SELECT current_database() AS name").get("name"));TestDatabaseCleanup.clear(db);base=LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1);clock.set(base.atTime(9,0));remote="s27-msg-"+UUID.randomUUID();String hash=new BCryptPasswordEncoder().encode("S02-test-only-password");
        owner=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s27_owner',?,'student','S27-owner')",hash);other=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s27_other',?,'staff','S27-other')",hash);admin=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s27_admin',?,'admin','S27-admin')",hash);
        room=db.insert("INSERT INTO rooms(name,seat_rows,seat_cols) VALUES('S27 engineering room',1,1)");opening.initializeDefaults(room);seat=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'01',1,1)",room);ownerToken=login("owner");otherToken=login("other");
    }
    @AfterEach void clearRate(){redis.delete("studyroom-java:rate:login:"+remote);}
    String key(){return UUID.randomUUID().toString();}
    String login(String name)throws Exception{var result=http.perform(post("/api/auth/login").with(r->{r.setRemoteAddr(remote);return r;}).contentType("application/json").content(json.writeValueAsString(Map.of("username","s27_"+name,"password","S02-test-only-password")))).andExpect(status().isOk()).andReturn();return json.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();}
    JsonNode getData(String path,String token)throws Exception{return json.readTree(http.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");}
    JsonNode postData(String path,String token)throws Exception{return json.readTree(http.perform(post(path).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");}
    void page(JsonNode data,int total,int number,int size,int count){assertEquals(total,data.path("total").asInt());assertEquals(number,data.path("page").asInt());assertEquals(size,data.path("page_size").asInt());assertEquals(count,data.path("items").size());OffsetDateTime.parse(data.path("server_now").asText());}
    long legacy(long user,String type,boolean read){return db.insert("INSERT INTO notifications(user_id,type,title,content,is_read,created_at) VALUES(?,?,'S27 explicit engineering legacy','Old message mentions reservation #999999; do not infer target',?,?)",user,type,read,Timestamp.valueOf(base.atTime(9,0,0,123456000)));}
    List<Long> ids(JsonNode data){var result=new ArrayList<Long>();data.path("items").forEach(x->result.add(x.path("id").asLong()));return result;}
    @Test void moreThanTwoHundredMessagesHaveTrueTotalsStablePagesAndOwnerWideCounts()throws Exception{
        for(int i=0;i<230;i++)legacy(owner,"system",i<30);for(int i=0;i<4;i++)legacy(other,"system",false);
        var first=getData("/api/notifications?page=1&page_size=50",ownerToken);page(first,230,1,50,50);assertEquals(230,first.path("counts").path("total").asInt());assertEquals(200,first.path("counts").path("unread").asInt());assertEquals(230,first.path("counts").path("by_type").path("system").asInt());
        var all=new ArrayList<Long>();for(int p=1;p<=5;p++){var data=getData("/api/notifications?page="+p+"&page_size=50",ownerToken);page(data,230,p,50,p==5?30:50);all.addAll(ids(data));}assertEquals(230,new HashSet<>(all).size());for(int i=1;i<all.size();i++)assertTrue(all.get(i-1)>all.get(i),"same created_at uses id DESC");
        page(getData("/api/notifications?page=6&page_size=50",ownerToken),230,6,50,0);var filtered=getData("/api/notifications?type=system&is_read=true&page_size=50",ownerToken);page(filtered,30,1,50,30);assertEquals(230,filtered.path("counts").path("total").asInt());assertEquals(200,filtered.path("counts").path("unread").asInt());
    }
    @Test void readsReportActualChangedRowsAndNeverChangeAnotherOwner()throws Exception{
        long one=legacy(owner,"system",false),two=legacy(owner,"system",false),foreign=legacy(other,"system",false);
        var read=postData("/api/notifications/"+one+"/read",ownerToken);assertEquals(one,read.path("id").asLong());assertEquals(1,read.path("marked").asInt());assertEquals(1,read.path("unread_count").asInt());assertEquals(0,postData("/api/notifications/"+one+"/read",ownerToken).path("marked").asInt());
        http.perform(post("/api/notifications/"+foreign+"/read").header("Authorization","Bearer "+ownerToken)).andExpect(status().isForbidden());http.perform(post("/api/notifications/999999999/read").header("Authorization","Bearer "+ownerToken)).andExpect(status().isNotFound());
        var all=postData("/api/notifications/read_all",ownerToken);assertEquals(1,all.path("marked").asInt());assertEquals(0,all.path("unread_count").asInt());assertEquals(0,postData("/api/notifications/read_all",ownerToken).path("marked").asInt());assertEquals(1,getData("/api/notifications/unread_count",otherToken).path("count").asInt());
        assertEquals(1,db.count("SELECT count(*) FROM notifications WHERE id=? AND is_read",two));legacy(owner,"system",false);assertEquals(1,getData("/api/notifications/unread_count",ownerToken).path("count").asInt());
    }
    @Test void oldMessagesWithoutTypedLinksStayUnknownWithoutGuessOrResend()throws Exception{
        long id=legacy(owner,"reservation_success",true);var before=db.one("SELECT to_jsonb(n)::text AS row FROM notifications n WHERE id=?",id).get("row");var response=getData("/api/notifications",ownerToken);page(response,1,1,20,1);var item=response.path("items").get(0);assertEquals(id,item.path("id").asLong());assertTrue(item.path("business_id").isNull()||item.path("business_id").isMissingNode());assertTrue(item.path("business_type").isNull()||item.path("business_type").isMissingNode());assertTrue(item.path("is_read").asBoolean());assertTrue(item.path("content").asText().contains("#999999"));
        assertEquals(before,db.one("SELECT to_jsonb(n)::text AS row FROM notifications n WHERE id=?",id).get("row"));assertEquals(1,db.count("SELECT count(*) FROM notifications WHERE user_id=?",owner));
    }
    @Test void filtersRejectScopeExpansionAndSnapshotHasExplicitCampusOffset()throws Exception{
        legacy(owner,"system",false);var response=getData("/api/notifications",ownerToken);assertEquals(base.atTime(9,0).atZone(BookingPolicy.CAMPUS_ZONE).toInstant(),OffsetDateTime.parse(response.path("server_now").asText()).toInstant());
        for(String query:List.of("page=0","page_size=51","is_read=1","is_read=unknown","type=not_a_type","user_id="+other,"unexpected=value"))http.perform(get("/api/notifications?"+query).header("Authorization","Bearer "+ownerToken)).andExpect(status().isBadRequest());http.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
    }
    @Test void authoritativeBookingEventsHaveTypedReservationAndUniqueEventKeys()throws Exception{
        long reservation=Store.id(bookings.create(owner,new Requests.Reservation(seat,base.toString(),"10:00","11:00")),"id");codes.generate(admin,seat,new Requests.SeatCodeChange(0L,"S27 engineering code"));String code=Store.text(db.one("SELECT code_value FROM seat_codes WHERE seat_id=?",seat),"code_value");clock.set(base.atTime(10,0));bookings.checkin(owner,reservation,code);clock.set(base.atTime(10,5));bookings.action(owner,reservation,"checkout");bookings.tick();bookings.tick();
        var rows=db.list("SELECT * FROM notifications WHERE reservation_id=? ORDER BY id",reservation);assertEquals(3,rows.size());assertEquals(Set.of("reservation_success","reservation_checkin","reservation_completed"),new HashSet<>(rows.stream().map(x->x.get("type")).toList()));assertEquals(3,new HashSet<>(rows.stream().map(x->x.get("event_key")).toList()).size());assertTrue(rows.stream().allMatch(x->x.get("event_key")!=null&&Store.id(x,"user_id")==owner));
        var response=getData("/api/notifications",ownerToken);response.path("items").forEach(x->{assertEquals(reservation,x.path("business_id").asLong());assertTrue(x.path("business_type").isTextual());});
        Object eventKey=rows.get(0).get("event_key");assertThrows(RuntimeException.class,()->db.update("INSERT INTO notifications(user_id,type,title,content,reservation_id,event_key) VALUES(?,'reservation_success','duplicate','duplicate',?,?)",owner,reservation,eventKey));assertThrows(RuntimeException.class,()->db.update("INSERT INTO notifications(user_id,type,title,content,reservation_id,event_key) VALUES(?,'reservation_success','wrong owner','wrong owner',?,?)",other,reservation,"s27-cross-owner"));assertEquals(3,db.count("SELECT count(*) FROM notifications WHERE reservation_id=?",reservation));
    }
    long version(long complaint){return Store.id(db.one("SELECT version FROM complaints WHERE id=?",complaint),"version");}
    @Test void complaintFeedbackRoundsAndAutomaticClosureUseTheirActualEventIdentities()throws Exception{
        long id=Store.id(complaints.create(owner,new Requests.Complaint("service",null,null,"S27 engineering complaint",List.of(),key())),"id");management.accept(admin,id,new Requests.ComplaintAccept(version(id),key()));var first=new Requests.ComplaintFeedback(version(id),key(),"First result","First verified public basis");workflow.feedback(admin,id,first);workflow.feedback(admin,id,first);
        assertEquals(200,workflow.action(owner,id,"object",new Requests.ComplaintResponse(version(id),key(),"Engineering objection")).status());workflow.feedback(admin,id,new Requests.ComplaintFeedback(version(id),key(),"Second result","Second public basis"));clock.set(base.plusDays(7).atTime(9,0));workflow.settleExpired();workflow.settleExpired();
        var rows=db.list("SELECT n.type,n.complaint_event_id,n.event_key,e.event_type,e.complaint_id FROM notifications n JOIN complaint_events e ON e.id=n.complaint_event_id WHERE n.complaint_id=? ORDER BY n.id",id);assertEquals(3,rows.size());assertEquals(2,rows.stream().filter(x->x.get("type").equals("complaint_feedback")).count());assertEquals(1,rows.stream().filter(x->x.get("type").equals("complaint_auto_closed")).count());assertEquals(3,new HashSet<>(rows.stream().map(x->x.get("complaint_event_id")).toList()).size());assertEquals(3,new HashSet<>(rows.stream().map(x->x.get("event_key")).toList()).size());rows.forEach(x->assertEquals(id,Store.id(x,"complaint_id")));
        // Proposed new typed-FK safety: another complaint/event or receiver must not fit.
        long otherComplaint=Store.id(complaints.create(owner,new Requests.Complaint("service",null,null,"Different engineering complaint",List.of(),key())),"id");Object event=rows.get(0).get("complaint_event_id");
        assertThrows(RuntimeException.class,()->db.update("INSERT INTO notifications(user_id,type,title,content,complaint_id,complaint_event_id,event_key) VALUES(?,'complaint_feedback','wrong event','wrong event',?,?,?)",owner,otherComplaint,event,"s27-wrong-complaint-event"));
        assertThrows(RuntimeException.class,()->db.update("INSERT INTO notifications(user_id,type,title,content,complaint_id,complaint_event_id,event_key) VALUES(?,'complaint_feedback','wrong receiver','wrong receiver',?,?,?)",other,id,event,"s27-wrong-complaint-owner"));
    }
    @Test void notificationFailureRollsBackReservationInsteadOfSilentlyDroppingTheMessage(){
        db.update("ALTER TABLE notifications ADD CONSTRAINT s27_fail_publish CHECK(type<>'reservation_success') NOT VALID");try{assertThrows(RuntimeException.class,()->bookings.create(owner,new Requests.Reservation(seat,base.toString(),"10:00","11:00")));assertEquals(0,db.count("SELECT count(*) FROM reservations"));assertEquals(0,db.count("SELECT count(*) FROM audit_events WHERE action='reservation.create'"));assertEquals(0,db.count("SELECT count(*) FROM notifications"));}finally{db.update("ALTER TABLE notifications DROP CONSTRAINT s27_fail_publish");}
    }
    @Test void publisherRequiresBusinessTransactionAndIdenticalEventReplayPreservesOriginalReadTime(){
        long reservation=Store.id(bookings.create(owner,new Requests.Reservation(seat,base.toString(),"10:00","11:00")),"id");clock.set(base.atTime(10,15));bookings.tick();var notice=db.one("SELECT * FROM notifications WHERE user_id=? AND type='violation'",owner);long violation=Store.id(notice,"violation_id"),id=Store.id(notice,"id");String title=Store.text(notice,"title"),content=Store.text(notice,"content");
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->notifications.violation(owner,violation,title,content,base.atTime(10,16)));
        notifications.read(owner,id);var before=db.one("SELECT to_jsonb(n)::text AS row FROM notifications n WHERE id=?",id).get("row");long count=db.count("SELECT count(*) FROM notifications");Long repeated=transactions.execute(t->notifications.violation(owner,violation,title,content,base.atTime(10,16)));assertEquals(id,repeated);assertEquals(before,db.one("SELECT to_jsonb(n)::text AS row FROM notifications n WHERE id=?",id).get("row"));assertEquals(count,db.count("SELECT count(*) FROM notifications"));
        var failure=assertThrows(Api.Failure.class,()->transactions.execute(t->notifications.violation(owner,violation,title,"different event text",base.atTime(10,16))));assertEquals(409,failure.status);assertEquals(before,db.one("SELECT to_jsonb(n)::text AS row FROM notifications n WHERE id=?",id).get("row"));assertEquals("unused_ended",db.one("SELECT status FROM reservations WHERE id=?",reservation).get("status"));
    }
}
