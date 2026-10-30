package cn.studyroom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
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
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Read-only administration of existing audit facts; unsafe historical fixtures are explicit. */
@EnabledIfEnvironmentVariable(named="STUDYROOM_INTEGRATION",matches="true")
@SpringBootTest(properties={"spring.datasource.url=${STUDYROOM_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5433/studyroom_java_test}","studyroom.seed=false","studyroom.scheduler-enabled=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class AuditQueryIntegrationTest {
    @TestConfiguration static class TimeConfig {@Bean @Primary MinuteBookingIntegrationTest.TestClock testClock(){var c=new MinuteBookingIntegrationTest.TestClock();c.set(LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1).atTime(9,0));return c;}}
    @Autowired Store db;@Autowired AuditService audit;@Autowired BookingService bookings;@Autowired OpeningScheduleService opening;
    @Autowired TransactionTemplate tx;@Autowired MockMvc http;@Autowired ObjectMapper json;@Autowired StringRedisTemplate redis;
    @Autowired MinuteBookingIntegrationTest.TestClock clock;
    long admin,owner,room,seat;LocalDate base;String remote,adminToken,ownerToken;
    static final String SECRET="S28_SECRET_SENTINEL",PRIVATE="S28_PRIVATE_SENTINEL",IDENTITY="S28_IDENTITY_PRIVATE";
    static final Set<String> META=Set.of("id","actor_id","actor_username","action","object_type","object_id","reason","occurred_at","redaction");
    @BeforeEach void fixture()throws Exception{
        assertEquals("studyroom_java_test",db.one("SELECT current_database() AS name").get("name"));TestDatabaseCleanup.clear(db);base=LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1);clock.set(base.atTime(9,0));remote="s28-audit-"+UUID.randomUUID();String hash=new BCryptPasswordEncoder().encode("S02-test-only-password");
        admin=db.insert("INSERT INTO users(username,password_hash,role,campus_id,real_name) VALUES('s28_admin',?,'admin',?,?)",hash,IDENTITY,IDENTITY);owner=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s28_owner',?,'student','S28-owner')",hash);
        room=db.insert("INSERT INTO rooms(name,seat_rows,seat_cols) VALUES('S28 explicit engineering room',1,1)");opening.initializeDefaults(room);seat=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'01',1,1)",room);adminToken=login("admin");ownerToken=login("owner");
    }
    @AfterEach void clearRate(){redis.delete("studyroom-java:rate:login:"+remote);}
    String login(String name)throws Exception{var response=http.perform(post("/api/auth/login").with(r->{r.setRemoteAddr(remote);return r;}).contentType("application/json").content(json.writeValueAsString(Map.of("username","s28_"+name,"password","S02-test-only-password")))).andExpect(status().isOk()).andReturn();return json.readTree(response.getResponse().getContentAsString()).path("data").path("token").asText();}
    JsonNode getData(String path,String token)throws Exception{return json.readTree(http.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");}
    void page(JsonNode data,int total,int number,int size,int count){assertEquals(total,data.path("total").asInt());assertEquals(number,data.path("page").asInt());assertEquals(size,data.path("page_size").asInt());assertEquals(count,data.path("items").size());OffsetDateTime.parse(data.path("server_now").asText());}
    long event(Long actor,String action,String type,String id,OffsetDateTime time,String before,String after){return db.insert("INSERT INTO audit_events(actor_id,action,object_type,object_id,reason,before_values,after_values,occurred_at) VALUES(?,?,?,?,?,CAST(? AS JSONB),CAST(? AS JSONB),?)",actor,action,type,id,"S28 explicit engineering audit",before,after,Timestamp.from(time.toInstant()));}
    List<Long> ids(JsonNode data){var result=new ArrayList<Long>();data.path("items").forEach(x->result.add(x.path("id").asLong()));return result;}
    Set<String> keys(JsonNode node){var result=new HashSet<String>();node.fieldNames().forEachRemaining(result::add);return result;}
    @Test void realCountStablePaginationAndExactActorObjectActionFilters()throws Exception{
        OffsetDateTime instant=base.atTime(12,0).atOffset(ZoneOffset.ofHours(8));for(int i=0;i<25;i++)event(admin,"reservation.cancel","reservation","S28-"+i,instant,"{\"status\":\"pending\",\"version\":1}","{\"status\":\"cancelled\",\"version\":2}");event(null,"reservation.complete","reservation","system-object",instant,"{}","{}");
        var one=getData("/api/admin/audit-events?page=1&page_size=20",adminToken);var two=getData("/api/admin/audit-events?page=2&page_size=20",adminToken);page(one,26,1,20,20);page(two,26,2,20,6);var all=new ArrayList<>(ids(one));all.addAll(ids(two));assertEquals(26,new HashSet<>(all).size());for(int i=1;i<all.size();i++)assertTrue(all.get(i-1)>all.get(i));page(getData("/api/admin/audit-events?page=99",adminToken),26,99,20,0);
        var exact=getData("/api/admin/audit-events?actor_id="+admin+"&object_type=reservation&object_id=S28-7&action=reservation.cancel&date_from="+base+"&date_to="+base,adminToken);page(exact,1,1,20,1);assertEquals("S28-7",exact.path("items").get(0).path("object_id").asText());page(getData("/api/admin/audit-events?actor_id=999999999",adminToken),0,1,20,0);
        var system=getData("/api/admin/audit-events?object_id=system-object",adminToken).path("items").get(0);assertTrue(system.path("actor_id").isNull());assertTrue(system.path("actor_username").isNull());assertEquals(META,keys(system));assertEquals("s28_admin",exact.path("items").get(0).path("actor_username").asText());assertFalse(one.toString().contains(IDENTITY));
    }
    @Test void timestampFiltersUseCampusDayRatherThanDatabaseSessionDay()throws Exception{
        long inside=event(admin,"resource.update","room","in-day",base.atTime(23,59,59,999999000).atOffset(ZoneOffset.ofHours(8)),"{}","{}");event(admin,"resource.update","room","next-day",base.plusDays(1).atStartOfDay().atOffset(ZoneOffset.ofHours(8)),"{}","{}");
        var result=getData("/api/admin/audit-events?date_from="+base+"&date_to="+base,adminToken);page(result,1,1,20,1);assertEquals(inside,result.path("items").get(0).path("id").asLong());OffsetDateTime.parse(result.path("items").get(0).path("occurred_at").asText());
        page(getData("/api/admin/audit-events?date_from=9999-12-31&date_to=9999-12-31",adminToken),0,1,20,0);
    }
    @Test void detailExposesNecessaryChangesWhileSecretsIdentityAndPrivateEvidenceStayOut()throws Exception{
        // Explicit unsafe historical engineering row bypasses current AuditService write guard;
        // the new read projection must protect history too, without changing the stored row.
        String unsafe=json.writeValueAsString(Map.of("status","pending","version",1,"password_hash",SECRET,"verification_note",PRIVATE,"profile",Map.of("real_name",IDENTITY,"campus_id",IDENTITY,"email","private@example.test","phone","13800138000"),"unknown",Map.of("raw_sql",SECRET,"token",SECRET)));
        long id=event(admin,"restriction.release","restriction","uuid-like-S28-source",base.atTime(12,0).atOffset(ZoneOffset.ofHours(8)),unsafe,"{\"status\":\"released\",\"version\":2}");db.update("UPDATE audit_events SET reason='Contact private@example.test or 13800138000; public release basis' WHERE id=?",id);String before=db.one("SELECT to_jsonb(a)::text AS row FROM audit_events a WHERE id=?",id).get("row").toString();
        var listing=getData("/api/admin/audit-events",adminToken).path("items").get(0);assertFalse(listing.has("before_values"));assertFalse(listing.has("after_values"));
        var detail=getData("/api/admin/audit-events/"+id,adminToken);String publicJson=detail.toString();for(String text:List.of(SECRET,PRIVATE,IDENTITY,"private@example.test","13800138000"))assertFalse(publicJson.contains(text));assertEquals("pending",detail.path("before_values").path("status").asText());assertEquals("released",detail.path("after_values").path("status").asText());assertEquals(2,detail.path("after_values").path("version").asInt());
        var detailKeys=new HashSet<>(META);detailKeys.addAll(Set.of("before_values","after_values"));assertEquals(detailKeys,keys(detail));assertEquals(META,keys(listing));
        assertEquals(Set.of("status","version"),keys(detail.path("before_values")));assertEquals(Set.of("status","version"),keys(detail.path("after_values")));
        var redaction=detail.path("redaction");assertEquals(Set.of("omitted_fields","truncated_values","redacted_values","omitted_items","notice"),keys(redaction));assertEquals(4,redaction.path("omitted_fields").asInt());assertEquals(1,redaction.path("redacted_values").asInt());assertEquals(0,redaction.path("truncated_values").asInt());assertEquals(0,redaction.path("omitted_items").asInt());assertTrue(redaction.path("notice").asText().contains("不代表完整原件"));
        assertEquals(before,db.one("SELECT to_jsonb(a)::text AS row FROM audit_events a WHERE id=?",id).get("row"));
    }
    @Test void boundedProjectionRejectsWrongShapesAndBoundsDepthArraysAndUnicodeText()throws Exception{
        var values=new LinkedHashMap<String,Object>();values.put("name","😀".repeat(350));values.put("status","pending");values.put("version",Map.of("raw_sql",SECRET));values.put("reservation_id",SECRET);values.put("operation_id","not-a-uuid");values.put("source_violation_ids",java.util.stream.LongStream.rangeClosed(1,25).boxed().toList());values.put("effects",Map.of("before",Map.of("after",Map.of("changes",Map.of("status",SECRET)))));
        long id=event(admin,"room.update","room","bounded",base.atTime(12,0).atOffset(ZoneOffset.ofHours(8)),json.writeValueAsString(values),"{}");var detail=getData("/api/admin/audit-events/"+id,adminToken);var before=detail.path("before_values");assertEquals("pending",before.path("status").asText());assertEquals(20,before.path("source_violation_ids").size());assertEquals(1,before.path("source_violation_ids").get(0).asLong());assertEquals(20,before.path("source_violation_ids").get(19).asLong());assertEquals(301,before.path("name").asText().codePointCount(0,before.path("name").asText().length()));assertEquals("😀".repeat(300)+"…",before.path("name").asText());assertTrue(before.path("effects").path("before").path("after").path("changes").isEmpty());assertFalse(detail.toString().contains(SECRET));assertFalse(before.has("version"));assertFalse(before.has("reservation_id"));assertFalse(before.has("operation_id"));assertEquals(3,detail.path("redaction").path("omitted_fields").asInt());assertEquals(5,detail.path("redaction").path("omitted_items").asInt());assertEquals(3,detail.path("redaction").path("truncated_values").asInt());
        long oversized=event(admin,"room.update","room","oversized",base.atTime(12,0).atOffset(ZoneOffset.ofHours(8)),json.writeValueAsString(Map.of("name","x".repeat(65537))),"{}");var large=getData("/api/admin/audit-events/"+oversized,adminToken);assertTrue(large.path("before_values").isEmpty());assertEquals(1,large.path("redaction").path("truncated_values").asInt());
    }
    @Test void digestMetadataAndCredentialOrSqlFreeTextNeverExposeRawValues()throws Exception{
        String digest="abcdef0123456789".repeat(4);long id=event(admin,"user.import","user_batch",digest,base.atTime(12,0).atOffset(ZoneOffset.ofHours(8)),"{}",json.writeValueAsString(Map.of("file_sha256",digest,"imported_count",3,"unknown",PRIVATE)));
        db.update("UPDATE audit_events SET reason=? WHERE id=?","password="+SECRET+"; email private@example.test phone 13800138000",id);var detail=getData("/api/admin/audit-events/"+id,adminToken);var list=getData("/api/admin/audit-events?object_id="+digest,adminToken);page(list,1,1,20,1);assertEquals(id,list.path("items").get(0).path("id").asLong());for(var response:List.of(detail,list)){for(String text:List.of(digest,SECRET,PRIVATE,"private@example.test","13800138000"))assertFalse(response.toString().contains(text));}assertEquals(3,detail.path("after_values").path("imported_count").asInt());assertEquals(2,detail.path("redaction").path("omitted_fields").asInt());
        db.update("UPDATE audit_events SET reason=? WHERE id=?","SELECT password_hash FROM users; "+SECRET,id);assertFalse(getData("/api/admin/audit-events/"+id,adminToken).toString().contains(SECRET));
    }
    @Test void allReadsLeaveOriginalAuditAndBusinessRowsUnchanged()throws Exception{
        bookings.create(owner,new Requests.Reservation(seat,base.toString(),"10:00","11:00"));clock.set(base.atTime(10,30));
        // A now-expired pending reservation must stay untouched by audit reads.
        long id=event(null,"system.inspect","room","S28-system",base.atTime(12,0).atOffset(ZoneOffset.ofHours(8)),"{}","{}");var before=auditRows();var reservationBefore=db.list("SELECT to_jsonb(r)::text AS row FROM reservations r ORDER BY id");var messagesBefore=db.list("SELECT to_jsonb(n)::text AS row FROM notifications n ORDER BY id");getData("/api/admin/audit-events",adminToken);getData("/api/admin/audit-events/"+id,adminToken);assertEquals(before,auditRows());assertEquals(reservationBefore,db.list("SELECT to_jsonb(r)::text AS row FROM reservations r ORDER BY id"));assertEquals(messagesBefore,db.list("SELECT to_jsonb(n)::text AS row FROM notifications n ORDER BY id"));assertEquals("pending",db.one("SELECT status FROM reservations").get("status"));assertEquals(0,db.count("SELECT count(*) FROM violations"));assertEquals(0,db.count("SELECT count(*) FROM usage_records"));
    }
    List<Map<String,Object>> auditRows(){return db.list("SELECT to_jsonb(a)::text AS row FROM audit_events a ORDER BY id");}
    @Test void permissionsAndInvalidQueriesAreRejectedWithoutLeakingDetails()throws Exception{
        long id=event(admin,"resource.update","room","string-object",base.atTime(12,0).atOffset(ZoneOffset.ofHours(8)),"{}","{}");for(String path:List.of("/api/admin/audit-events","/api/admin/audit-events/"+id)){http.perform(get(path)).andExpect(status().isUnauthorized());http.perform(get(path).header("Authorization","Bearer "+ownerToken)).andExpect(status().isForbidden());}
        for(String query:List.of("page=0","page_size=51","actor_id=-1","date_from=bad","date_from=0000-01-01","date_from="+base+"&date_to="+base.minusDays(1),"unexpected=value","object_id=","action=has%20space"))http.perform(get("/api/admin/audit-events?"+query).header("Authorization","Bearer "+adminToken)).andExpect(status().isBadRequest());http.perform(get("/api/admin/audit-events/999999999").header("Authorization","Bearer "+adminToken)).andExpect(status().isNotFound());
        db.update("UPDATE users SET status='disabled' WHERE id=?",admin);http.perform(get("/api/admin/audit-events").header("Authorization","Bearer "+adminToken)).andExpect(status().isUnauthorized());http.perform(get("/api/admin/audit-events/"+id).header("Authorization","Bearer "+adminToken)).andExpect(status().isUnauthorized());
    }
    @Test void rolledBackBusinessDoesNotLeaveSuccessfulAuditOrNotification(){
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->{bookings.create(owner,new Requests.Reservation(seat,base.toString(),"10:00","11:00"));throw new IllegalStateException("S28 intentional transaction rollback");}));assertEquals(0,db.count("SELECT count(*) FROM reservations"));assertEquals(0,db.count("SELECT count(*) FROM notifications"));assertEquals(0,db.count("SELECT count(*) FROM audit_events WHERE action='reservation.create'"));
    }
}
