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

/** Explicit engineering historical fixtures; no campus observation or production database. */
@EnabledIfEnvironmentVariable(named="STUDYROOM_INTEGRATION",matches="true")
@SpringBootTest(properties={"spring.datasource.url=${STUDYROOM_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5433/studyroom_java_test}","studyroom.seed=false","studyroom.scheduler-enabled=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class RecordPaginationIntegrationTest {
    @TestConfiguration static class TimeConfig {@Bean @Primary MinuteBookingIntegrationTest.TestClock testClock(){var c=new MinuteBookingIntegrationTest.TestClock();c.set(LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1).atTime(12,0));return c;}}
    @Autowired Store db;@Autowired OpeningScheduleService opening;@Autowired ComplaintService complaints;@Autowired AppealService appeals;
    @Autowired MinuteBookingIntegrationTest.TestClock clock;@Autowired MockMvc http;@Autowired ObjectMapper json;@Autowired StringRedisTemplate redis;
    long owner,other,admin,room,secondRoom,seat,secondSeat;LocalDate base;String remote,ownerToken,otherToken,adminToken;
    @BeforeEach void fixture()throws Exception{
        assertEquals("studyroom_java_test",db.one("SELECT current_database() AS name").get("name"));TestDatabaseCleanup.clear(db);
        base=LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1);clock.set(base.atTime(12,0));remote="s26-pages-"+UUID.randomUUID();String hash=new BCryptPasswordEncoder().encode("S02-test-only-password");
        owner=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s26_owner',?,'student','S26-owner')",hash);
        other=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s26_other',?,'staff','S26-other')",hash);
        admin=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s26_admin',?,'admin','S26-admin')",hash);
        room=db.insert("INSERT INTO rooms(name,seat_rows,seat_cols) VALUES('S26 engineering history A',1,1)");secondRoom=db.insert("INSERT INTO rooms(name,seat_rows,seat_cols) VALUES('S26 engineering history B',1,1)");opening.initializeDefaults(room);opening.initializeDefaults(secondRoom);
        seat=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'A-01',1,1)",room);secondSeat=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'B-01',1,1)",secondRoom);
        ownerToken=login("owner");otherToken=login("other");adminToken=login("admin");
    }
    @AfterEach void clearRate(){redis.delete("studyroom-java:rate:login:"+remote);}
    String login(String name)throws Exception{var result=http.perform(post("/api/auth/login").with(r->{r.setRemoteAddr(remote);return r;}).contentType("application/json").content(json.writeValueAsString(Map.of("username","s26_"+name,"password","S02-test-only-password")))).andExpect(status().isOk()).andReturn();return json.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();}
    JsonNode getData(String url,String token)throws Exception{return json.readTree(http.perform(get(url).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");}
    void rejected(String url,String token,int code)throws Exception{http.perform(get(url).header("Authorization","Bearer "+token)).andExpect(status().is(code));}
    void page(JsonNode data,int total,int number,int size,int count){assertTrue(data.path("items").isArray());assertEquals(total,data.path("total").asInt());assertEquals(number,data.path("page").asInt());assertEquals(size,data.path("page_size").asInt());assertEquals(count,data.path("items").size());assertTrue(data.path("server_now").isTextual());OffsetDateTime.parse(data.path("server_now").asText());}
    List<Long> ids(JsonNode page){var ids=new ArrayList<Long>();page.path("items").forEach(x->ids.add(x.path("id").asLong()));return ids;}
    void completePages(String path,String token,int total)throws Exception{
        var one=getData(path+"?page=1&page_size=20",token);var two=getData(path+"?page=2&page_size=20",token);page(one,total,1,20,20);page(two,total,2,20,total-20);
        var all=new ArrayList<>(ids(one));all.addAll(ids(two));assertEquals(total,new HashSet<>(all).size());assertEquals(ids(one),ids(getData(path+"?page=1&page_size=20",token)));page(getData(path+"?page=99&page_size=20",token),total,99,20,0);
        var items=new ArrayList<JsonNode>();one.path("items").forEach(items::add);two.path("items").forEach(items::add);for(int i=1;i<items.size();i++){var a=items.get(i-1);var b=items.get(i);String key=a.has("res_date")?"res_date":a.has("occurred_at")?"occurred_at":a.has("created_at")?"created_at":"submitted_at";if(a.has(key)&&a.get(key).equals(b.get(key))&&(!a.has("start_time")||a.get("start_time").equals(b.get("start_time"))))assertTrue(a.path("id").asLong()>b.path("id").asLong(),"same time rows require descending id tie break");}
    }
    long reservation(long user,long targetSeat,LocalDate date,boolean used){
        long id=db.insert("INSERT INTO reservations(user_id,seat_id,res_date,start_time,end_time,status,ended_at,termination_reason) VALUES(?,?,?,'09:00','10:00',?,?,?)",user,targetSeat,java.sql.Date.valueOf(date),used?"completed":"cancelled",Timestamp.valueOf(date.atTime(9,30)),used?"early_checkout":"user_cancelled");
        if(used)db.update("INSERT INTO usage_records(reservation_id,checkin_at,ended_at,end_reason,settled_at,origin,created_at,updated_at) VALUES(?,?,?,'early_checkout',?,'demo_seed',?,?)",id,Timestamp.valueOf(date.atTime(9,2)),Timestamp.valueOf(date.atTime(9,30)),Timestamp.valueOf(date.atTime(9,30)),Timestamp.valueOf(date.atTime(9,2)),Timestamp.valueOf(date.atTime(9,30)));return id;
    }
    long violation(long user,LocalDateTime occurred,boolean active){long r=db.insert("INSERT INTO reservations(user_id,seat_id,res_date,start_time,end_time,status,ended_at,termination_reason) VALUES(?,?,?,'08:00','09:00','unused_ended',?,'no_show')",user,seat,java.sql.Date.valueOf(occurred.toLocalDate()),Timestamp.valueOf(occurred));return db.insert("INSERT INTO violations(user_id,reservation_id,type,event_key,occurred_at,registered_at,status,reason,source) VALUES(?,?,'no_show',?,?,?,?,'S26 engineering violation','automatic')",user,r,"unused:"+r,Timestamp.valueOf(occurred),Timestamp.valueOf(base.atTime(11,0)),active?"active":"revoked");}
    long restriction(long user,boolean active){LocalDateTime start=active?base.atTime(11,0):base.minusDays(2).atTime(11,0),end=active?base.plusDays(1).atTime(11,0):base.minusDays(1).atTime(11,0);return db.insert("INSERT INTO restrictions(user_id,source,starts_at,ends_at,reason,creator_id,verification_note,facts_confirmed,created_at,effects_applied_at) VALUES(?,'manual',?,?,'S26 engineering restriction',?,'S26_PRIVATE_TEST',true,?,?)",user,Timestamp.valueOf(start),Timestamp.valueOf(end),admin,Timestamp.valueOf(start),Timestamp.valueOf(start));}
    @Test void reservationCountFiltersUsageAndDisabledResourceHistoryAreServerScoped()throws Exception{
        for(int i=0;i<25;i++)reservation(owner,i<15?seat:secondSeat,base.minusDays(i<15?1:2),i<2);for(int i=0;i<4;i++)reservation(other,seat,base.minusDays(1),false);
        db.update("UPDATE rooms SET enabled=false WHERE id=?",room);db.update("UPDATE seats SET status='maintenance' WHERE id=?",seat);
        completePages("/api/reservations/mine",ownerToken,25);page(getData("/api/admin/reservations?user_id="+owner+"&page_size=50",adminToken),25,1,50,25);
        page(getData("/api/reservations/mine?date_from="+base.minusDays(1)+"&date_to="+base.minusDays(1)+"&room_id="+room+"&seat_no=A-01&status=cancelled&page_size=50",ownerToken),13,1,50,13);
        var usage=getData("/api/reservations/mine?record_type=usage&page_size=50",ownerToken);page(usage,2,1,50,2);usage.path("items").forEach(x->assertFalse(x.path("usage_record").isNull()));
        long target=ids(getData("/api/reservations/mine?page=2",ownerToken)).get(0);getData("/api/reservations/"+target,ownerToken);getData("/api/admin/reservations/"+target,adminToken);rejected("/api/reservations/"+target,otherToken,403);
    }
    @Test void violationPagesUseOccurredDayAndIndependentOwnerDetail()throws Exception{
        for(int i=0;i<25;i++)violation(owner,base.minusDays(i<15?1:2).atTime(8,15),i<2);violation(other,base.minusDays(1).atTime(8,15),false);
        completePages("/api/violations/mine",ownerToken,25);page(getData("/api/admin/violations?user_id="+owner+"&status=revoked&page_size=50",adminToken),23,1,50,23);
        page(getData("/api/violations/mine?date_from="+base.minusDays(1)+"&date_to="+base.minusDays(1)+"&type=no_show&page_size=50",ownerToken),15,1,50,15);
        long target=ids(getData("/api/violations/mine?page=2",ownerToken)).get(0);var detail=getData("/api/violations/"+target,ownerToken);assertTrue(detail.toString().contains(Long.toString(target)));assertFalse(detail.toString().contains("S26_PRIVATE"));rejected("/api/violations/"+target,otherToken,403);
        rejected("/api/violations/mine?room_id="+room,ownerToken,400);
    }
    @Test void restrictionPagesClassifyNowAndKeepNonCurrentPageDetailPrivate()throws Exception{
        for(int i=0;i<25;i++)restriction(owner,i<2);restriction(other,false);
        completePages("/api/restrictions/mine",ownerToken,25);page(getData("/api/restrictions/mine?status=active&page_size=50",ownerToken),2,1,50,2);page(getData("/api/admin/restrictions?user_id="+owner+"&status=expired&page_size=50",adminToken),23,1,50,23);
        page(getData("/api/restrictions/mine?date_from="+base+"&date_to="+base+"&page_size=50",ownerToken),2,1,50,2);
        var adminRows=getData("/api/admin/restrictions?user_id="+owner,adminToken);adminRows.path("items").forEach(item->{assertFalse(item.has("verification_note"));assertFalse(item.has("release_verification_note"));assertFalse(item.has("facts_confirmed"));});
        long target=ids(getData("/api/restrictions/mine?page=2",ownerToken)).get(0);var detail=getData("/api/restrictions/"+target,ownerToken);assertEquals("S26_PRIVATE_TEST",getData("/api/admin/restrictions/"+target,adminToken).path("verification_note").asText());rejected("/api/admin/restrictions/"+target,ownerToken,403);assertFalse(detail.toString().contains("S26_PRIVATE_TEST"));rejected("/api/restrictions/"+target,otherToken,403);
    }
    @Test void complaintPagesPreserveFrozenTargetAndFullOwnerScope()throws Exception{
        for(int i=0;i<25;i++)complaints.create(owner,new Requests.Complaint("service",null,null,"S26 engineering service "+i,List.of(),UUID.randomUUID().toString()));complaints.create(other,new Requests.Complaint("service",null,null,"S26 other engineering service",List.of(),UUID.randomUUID().toString()));
        var before=db.list("SELECT to_jsonb(t)::text AS row FROM complaint_targets t ORDER BY complaint_id").toString();completePages("/api/complaints/mine",ownerToken,25);
        page(getData("/api/admin/complaints?user_id="+owner+"&status=submitted&type=service&date_from="+base+"&date_to="+base+"&page_size=50",adminToken),25,1,50,25);
        assertEquals(before,db.list("SELECT to_jsonb(t)::text AS row FROM complaint_targets t ORDER BY complaint_id").toString());long target=ids(getData("/api/complaints/mine?page=2",ownerToken)).get(0);getData("/api/complaints/"+target,ownerToken);rejected("/api/complaints/"+target,otherToken,403);
    }
    @Test void appealPagesKeepPrivateContextAndQualificationOutOfLists()throws Exception{
        for(int i=0;i<25;i++){long v=violation(owner,base.minusDays(1).atTime(8,15),true);db.insert("INSERT INTO notifications(user_id,type,title,content,violation_id,created_at) VALUES(?,'violation','S26 engineering typed notice','test not physical evidence',?,?)",owner,v,Timestamp.valueOf(base.atTime(11,0)));appeals.create(owner,v,new Requests.Appeal(UUID.randomUUID().toString(),"S26 engineering appeal "+i,List.of()));}
        completePages("/api/appeals/mine",ownerToken,25);var managed=getData("/api/admin/appeals?user_id="+owner+"&status=submitted&date_from="+base+"&date_to="+base+"&page_size=50",adminToken);page(managed,25,1,50,25);
        managed.path("items").forEach(x->{assertFalse(x.has("current_eligibility"));assertFalse(x.has("management_context"));assertFalse(x.toString().contains("verification_note"));});long target=ids(getData("/api/appeals/mine?page=2",ownerToken)).get(0);getData("/api/appeals/"+target,ownerToken);rejected("/api/appeals/"+target,otherToken,403);
    }
    @Test void allFiveDomainsRejectScopeExpansionAndMalformedPaginationBeforeReturningData()throws Exception{
        for(String domain:List.of("reservations","violations","restrictions","complaints","appeals")){
            String mine="/api/"+domain+"/mine",management="/api/admin/"+domain;
            rejected(mine+"?user_id="+other,ownerToken,400);rejected(management,ownerToken,403);
            for(String query:List.of("page=0","page_size=51","date_from=bad","date_from="+base+"&date_to="+base.minusDays(1),"unexpected=value","status=not_a_state"))rejected(mine+"?"+query,ownerToken,400);
            page(getData(management+"?user_id=999999999&page_size=1",adminToken),0,1,1,0);
        }
    }
    @Test void positiveFourDigitHistoricalAndMaximumDatesStayExactWithoutJavaHybridCalendarShift()throws Exception{
        var constants=db.one("SELECT DATE '1582-10-10' AS res_date,DATE '0001-01-01' AS plain_date,TIMESTAMP '1582-10-10 09:30:00.123456' AS historical_time,TIMESTAMP '2026-10-30 12:34:56.123456' AS business_time,TIMESTAMPTZ '2026-10-30 12:34:56.123456+08:00' AS actual_instant");
        assertEquals("1582-10-10",constants.get("res_date"));assertEquals("0001-01-01",constants.get("plain_date"));assertEquals("1582-10-10T09:30:00.123456",constants.get("historical_time"));assertEquals("2026-10-30T12:34:56.123456",constants.get("business_time"));assertEquals(OffsetDateTime.parse("2026-10-30T12:34:56.123456+08:00").toInstant(),OffsetDateTime.parse(constants.get("actual_instant").toString()).toInstant());
        long historical=db.insert("INSERT INTO reservations(user_id,seat_id,res_date,start_time,end_time,status,ended_at,termination_reason) VALUES(?,?,DATE '1582-10-10','09:00','10:00','cancelled',TIMESTAMP '1582-10-10 09:30','user_cancelled')",owner,seat);
        page(getData("/api/reservations/mine?date_from=1582-10-10&date_to=1582-10-10",ownerToken),1,1,20,1);
        assertEquals(historical,getData("/api/reservations/mine?date_from=1582-10-10&date_to=1582-10-10",ownerToken).path("items").get(0).path("id").asLong());
        var historicalRow=getData("/api/reservations/mine?date_from=1582-10-10&date_to=1582-10-10",ownerToken).path("items").get(0);assertEquals("1582-10-10",historicalRow.path("res_date").asText());assertTrue(historicalRow.path("ended_at").asText().startsWith("1582-10-10T09:30"));
        for(String domain:List.of("reservations","violations","restrictions","complaints","appeals")){
            String path="/api/"+domain+"/mine";rejected(path+"?date_from=0000-01-01",ownerToken,400);page(getData(path+"?date_from=9999-12-31&date_to=9999-12-31",ownerToken),0,1,20,0);
            if(!domain.equals("reservations"))page(getData(path+"?date_from=1582-10-10&date_to=1582-10-10",ownerToken),0,1,20,0);
        }
    }
}
