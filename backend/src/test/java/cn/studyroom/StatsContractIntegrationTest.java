package cn.studyroom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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

/** S29 statistics HTTP contract against real PostgreSQL and Redis.
 * Direct SQL below denotes explicit engineering historical fixtures, never real campus events.
 * The cross-midnight legacy fixture does NOT authorize early checkin or new cross-day bookings.
 */
@EnabledIfEnvironmentVariable(named="STUDYROOM_INTEGRATION",matches="true")
@SpringBootTest(properties={"spring.datasource.url=${STUDYROOM_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5433/studyroom_java_test}","studyroom.seed=false","studyroom.scheduler-enabled=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class StatsContractIntegrationTest {
    static class CountingClock extends MinuteBookingIntegrationTest.TestClock {
        final java.util.concurrent.atomic.AtomicInteger captures=new java.util.concurrent.atomic.AtomicInteger();
        @Override public Instant instant(){captures.incrementAndGet();return super.instant();}
    }
    @TestConfiguration static class TimeConfig {
        @Bean @Primary CountingClock testClock(){var c=new CountingClock();c.set(LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1).atTime(12,0));return c;}
    }
    @Autowired Store db; @Autowired OpeningScheduleService opening;
    @Autowired CountingClock clock; @Autowired MockMvc http;
    @Autowired ObjectMapper json; @Autowired StringRedisTemplate redis;
    LocalDate base; long admin,owner,room,seat1,seat2; String adminToken,ownerToken,remote;
    static final String IDENTITY="S29_ID_PRIVATE",PRIVATE="S29_PRIVATE_SENTINEL";
    static final Set<String> TOP=Set.of("snapshot_at","campus_zone","scope","day","current","trend","peak","top_seats");
    static final Set<String> DAY=Set.of("created_count","cancelled_count","checkin_count","usage_seconds","active_violation_count");
    static final Set<String> CURRENT=Set.of("open_available_seats","in_use_now","usage_ratio","complaint_todo_count","complaint_awaiting_count");
    @BeforeEach void fixture()throws Exception {
        assertEquals("studyroom_java_test",db.one("SELECT current_database() AS name").get("name"));
        TestDatabaseCleanup.clear(db);base=LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1);clock.set(base.atTime(12,0));remote="s29-stats-"+UUID.randomUUID();
        String hash=new BCryptPasswordEncoder().encode("S02-test-only-password");
        admin=db.insert("INSERT INTO users(username,password_hash,role,campus_id,real_name) VALUES('s29_admin',?,'admin',?,?)",hash,IDENTITY,IDENTITY);
        owner=db.insert("INSERT INTO users(username,password_hash,role,campus_id,phone,email) VALUES('s29_owner',?,'student','S29-owner','13800138000','private@example.test')",hash);
        room=db.insert("INSERT INTO rooms(name,seat_rows,seat_cols) VALUES('S29 engineering room',1,2)");opening.initializeDefaults(room);
        seat1=seat(room,"01",1);seat2=seat(room,"02",2);adminToken=login("admin");ownerToken=login("owner");
    }
    @AfterEach void clearRate(){redis.delete("studyroom-java:rate:login:"+remote);}
    long seat(long roomId,String number,int col){return db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,?,1,?)",roomId,number,col);}
    String login(String role)throws Exception {
        var response=http.perform(post("/api/auth/login").with(r->{r.setRemoteAddr(remote);return r;}).contentType("application/json").content(json.writeValueAsString(Map.of("username","s29_"+role,"password","S02-test-only-password")))).andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data").path("token").asText();
    }
    JsonNode data(String path)throws Exception{return json.readTree(http.perform(get(path).header("Authorization","Bearer "+adminToken)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");}
    JsonNode overview()throws Exception{return data("/api/stats/overview?room_id="+room+"&date="+base);}
    long reservation(long seat,LocalDate day,String start,String end,String status,LocalDateTime created,LocalDateTime ended,String reason){
        return db.insert("INSERT INTO reservations(user_id,seat_id,res_date,start_time,end_time,status,source,created_at,updated_at,ended_at,termination_reason) VALUES(?,?,CAST(? AS DATE),CAST(? AS TIME),CAST(? AS TIME),?,'manual',?,?,?,?)",owner,seat,day.toString(),start,end,status,Timestamp.valueOf(created),Timestamp.valueOf(created),stamp(ended),reason);
    }
    Timestamp stamp(LocalDateTime value){return value==null?null:Timestamp.valueOf(value);}
    void usage(long reservation,LocalDateTime checkin,LocalDateTime end,String reason){db.insert("INSERT INTO usage_records(reservation_id,checkin_at,ended_at,end_reason,settled_at,origin,created_at,updated_at) VALUES(?,?,?,?,?,'legacy',?,?)",reservation,stamp(checkin),stamp(end),reason,stamp(end),stamp(checkin),stamp(end==null?checkin:end));}
    Set<String> keys(JsonNode node){var result=new HashSet<String>();node.fieldNames().forEachRemaining(result::add);return result;}
    void decimal(String expected,JsonNode actual){assertTrue(actual.isNumber(),actual.toString());assertEquals(0,new BigDecimal(expected).compareTo(actual.decimalValue()));}
    JsonNode trendDay(JsonNode overview,LocalDate date){for(var day:overview.path("trend"))if(date.toString().equals(day.path("date").asText()))return day;fail("missing trend day "+date);return null;}
    List<Map<String,Object>> rows(String table){return db.list("SELECT to_jsonb(x)::text AS row FROM "+table+" x ORDER BY id");}

    @Test void schemaScopeUsesCampusTodayAndPreviousSixDaysEvenWhenSelectedDateDiffers()throws Exception {
        clock.set(base.atTime(12,0,0,123456789));clock.captures.set(0);var value=data("/api/stats/overview?date="+base.minusDays(2));assertEquals(1,clock.captures.get(),"a successful overview captures the injected application Clock once; authentication itself does not use it");
        assertEquals(TOP,keys(value));assertEquals(DAY,keys(value.path("day")));assertEquals(CURRENT,keys(value.path("current")));
        assertEquals("Asia/Shanghai",value.path("campus_zone").asText());var scope=value.path("scope");assertEquals(Set.of("room_id","selected_date","trend_from","trend_to"),keys(scope));assertTrue(scope.path("room_id").isNull());assertEquals(base.minusDays(2).toString(),scope.path("selected_date").asText());assertEquals(base.minusDays(6).toString(),scope.path("trend_from").asText());assertEquals(base.toString(),scope.path("trend_to").asText());
        var snapshot=OffsetDateTime.parse(value.path("snapshot_at").asText());assertEquals(base.atTime(12,0,0,123456000).atZone(BookingPolicy.CAMPUS_ZONE).toInstant(),snapshot.toInstant());assertEquals(ZoneOffset.ofHours(8),snapshot.getOffset());
        assertEquals(7,value.path("trend").size());for(int i=0;i<7;i++){var day=value.path("trend").get(i);var expected=new HashSet<>(DAY);expected.add("date");assertEquals(expected,keys(day));assertEquals(base.minusDays(6-i).toString(),day.path("date").asText());assertFalse(day.has("utilization"));}
        assertFalse(value.has("trend_14"));assertFalse(value.has("today_utilization"));
    }
    @Test void createdCountUsesCreationDateIncludingLaterCancelledReservationsNotReservationDate()throws Exception {
        reservation(seat1,base.plusDays(1),"10:00","11:00","pending",base.atTime(9,0),null,null);
        reservation(seat2,base,"13:00","14:00","cancelled",base.atTime(8,0),base.atTime(9,0),"user_cancelled");
        reservation(seat1,base,"14:00","15:00","pending",base.minusDays(1).atTime(9,0),null,null);
        var value=overview();assertEquals(2,value.path("day").path("created_count").asInt());assertEquals(2,trendDay(value,base).path("created_count").asInt());assertEquals(1,trendDay(value,base.minusDays(1)).path("created_count").asInt());assertEquals(1,value.path("day").path("cancelled_count").asInt());
    }
    @Test void cancelledCountUsesActualEndDateAndDoesNotInventLegacyMissingEnd()throws Exception {
        reservation(seat1,base,"14:00","15:00","cancelled",base.minusDays(2).atTime(9,0),base.atTime(10,0),"admin_resource_change");
        reservation(seat2,base,"14:00","15:00","cancelled",base.minusDays(2).atTime(9,0),base.minusDays(1).atTime(10,0),"user_cancelled");
        reservation(seat1,base,"16:00","17:00","cancelled",base.atTime(9,0),null,null);
        var value=overview();assertEquals(1,value.path("day").path("cancelled_count").asInt());assertEquals(1,trendDay(value,base.minusDays(1)).path("cancelled_count").asInt());
    }
    @Test void legacyCrossMidnightUsageSplitsRealTimeAndCheckinDayRatherThanResDate()throws Exception {
        // Explicit old engineering fact only: res_date next day, actual old checkin previous 23:50.
        long id=reservation(seat1,base,"00:00","00:30","completed",base.minusDays(1).atTime(9,0),base.atTime(0,20),"early_checkout");usage(id,base.minusDays(1).atTime(23,50),base.atTime(0,20),"early_checkout");
        var value=overview();decimal("1200",value.path("day").path("usage_seconds"));assertEquals(0,value.path("day").path("checkin_count").asInt());decimal("600",trendDay(value,base.minusDays(1)).path("usage_seconds"));assertEquals(1,trendDay(value,base.minusDays(1)).path("checkin_count").asInt());
        assertEquals(24,value.path("peak").size());decimal("1200",value.path("peak").get(0).path("usage_seconds"));decimal("600",value.path("peak").get(23).path("usage_seconds"));assertEquals(1,value.path("peak").get(23).path("checkin_count").asInt());
    }
    @Test void actualEarlyCheckoutAndManagedEndPreserveMicrosecondsAndUnusedIsNotUsage()throws Exception {
        var checkin=base.atTime(9,0,0,123456000);var end=base.atTime(9,10,0,654321000);long one=reservation(seat1,base,"09:00","10:00","completed",base.atTime(8,0),end,"early_checkout");usage(one,checkin,end,"early_checkout");
        long two=reservation(seat2,base,"10:00","11:00","cancelled",base.atTime(8,0),base.atTime(10,5),"admin_resource_change");usage(two,base.atTime(10,0),base.atTime(10,5),"admin_resource_change");
        reservation(seat1,base,"11:00","12:00","unused_ended",base.atTime(8,0),base.atTime(11,15),"no_show");
        var day=overview().path("day");assertEquals(2,day.path("checkin_count").asInt());decimal("900.530865",day.path("usage_seconds"));assertEquals(1,day.path("cancelled_count").asInt());
    }
    @Test void openUsageIsCappedBySharedSnapshotAndPlannedEndWithoutSettlingIt()throws Exception {
        clock.set(base.atTime(10,15,0,500000000));long id=reservation(seat1,base,"10:00","11:00","checked_in",base.atTime(9,0),null,null);usage(id,base.atTime(10,0),null,null);
        var first=overview();decimal("900.5",first.path("day").path("usage_seconds"));assertEquals(1,first.path("current").path("in_use_now").asInt());
        clock.set(base.atTime(12,0));var second=overview();decimal("3600",second.path("day").path("usage_seconds"));assertEquals(0,second.path("current").path("in_use_now").asInt());assertEquals("checked_in",db.one("SELECT status FROM reservations WHERE id=?",id).get("status"));assertNull(db.one("SELECT ended_at FROM usage_records WHERE reservation_id=?",id).get("ended_at"));
    }
    @Test void currentRatioUsesOnlyCurrentlyOpenAvailableUnblockedSeatsAndZeroDenominatorIsNull()throws Exception {
        clock.set(base.atTime(12,0));long id=reservation(seat1,base,"11:00","13:00","checked_in",base.atTime(10,0),null,null);usage(id,base.atTime(11,0),null,null);
        var first=overview().path("current");assertEquals(2,first.path("open_available_seats").asInt());assertEquals(1,first.path("in_use_now").asInt());decimal("0.5",first.path("usage_ratio"));
        db.insert("INSERT INTO resource_unavailability(room_id,seat_id,start_at,end_at,reason,actor_id) VALUES(?,?,?,?,?,?)",room,seat1,stamp(base.atTime(11,0)),stamp(base.atTime(13,0)),"S29 engineering temporary closure",admin);
        var blocked=overview().path("current");assertEquals(1,blocked.path("open_available_seats").asInt());assertEquals(0,blocked.path("in_use_now").asInt());decimal("0",blocked.path("usage_ratio"));
        db.update("UPDATE rooms SET enabled=false WHERE id=?",room);var closed=overview().path("current");assertEquals(0,closed.path("open_available_seats").asInt());assertEquals(0,closed.path("in_use_now").asInt());assertTrue(closed.path("usage_ratio").isNull());
    }
    @Test void zeroDenominatorAtExactClosingBoundaryIsNotApplicable()throws Exception {
        clock.set(base.atTime(22,0));var value=overview();assertEquals(0,value.path("current").path("open_available_seats").asInt());assertTrue(value.path("current").path("usage_ratio").isNull());decimal("0",value.path("day").path("usage_seconds"));
    }
    @Test void revokedViolationsAndRemindersDoNotBecomeActiveViolations()throws Exception {
        long reservation=reservation(seat1,base,"10:00","11:00","unused_ended",base.atTime(9,0),base.atTime(10,15),"no_show");
        for(String status:List.of("active","revoked"))db.insert("INSERT INTO violations(user_id,reservation_id,type,event_key,occurred_at,registered_at,status,reason) VALUES(?,?,'no_show',?,?,?,?,?)",owner,reservation,"s29-"+status,stamp(base.atTime(10,15)),stamp(base.atTime(10,16)),status,"S29 engineering violation");
        long complaint=complaint("processing");long event=db.insert("INSERT INTO complaint_events(complaint_id,actor_id,event_type,source,created_at,operation_id,request_hash,occurred_at,outcome,public_note,private_note) VALUES(?,?,'verification','admin_self_report',?,CAST(? AS UUID),?,?, 'cooperated',?,?)",complaint,admin,stamp(base.atTime(11,0)),UUID.randomUUID().toString(),"a".repeat(64),stamp(base.atTime(10,0)),"S29 engineering reminder",PRIVATE);
        db.insert("INSERT INTO reminders(event_id,subject_user_id,actor_id,occurred_at,registered_at) VALUES(?,?,?,?,?)",event,owner,admin,stamp(base.atTime(10,0)),stamp(base.atTime(11,0)));
        assertEquals(1,overview().path("day").path("active_violation_count").asInt());
    }
    long complaint(String status){boolean assigned=!Set.of("submitted","withdrawn").contains(status);return db.insert("INSERT INTO complaints(user_id,operation_id,request_hash,type,status,description,room_id,submitted_at,handler_id,accepted_at) VALUES(?,CAST(? AS UUID),?,'service',?,?,?,?,?,?)",owner,UUID.randomUUID().toString(),"b".repeat(64),status,PRIVATE,room,stamp(base.atTime(8,0)),assigned?admin:null,assigned?stamp(base.atTime(9,0)):null);}
    @Test void complaintTodoAndAwaitingAreSeparateAndStorageLifecycleIsNotAdvancedByRead()throws Exception {
        for(String state:List.of("submitted","processing","awaiting_confirmation","closed","withdrawn"))complaint(state);
        var before=rows("complaints");var value=overview().path("current");assertEquals(2,value.path("complaint_todo_count").asInt());assertEquals(1,value.path("complaint_awaiting_count").asInt());assertEquals(before,rows("complaints"));
    }
    @Test void heatmapAndTopRetainHistoricalUsageWhenResourceNowDisabledAndExposeNoIdentity()throws Exception {
        long id=reservation(seat1,base,"09:00","10:00","completed",base.atTime(8,0),base.atTime(9,30),"early_checkout");usage(id,base.atTime(9,0),base.atTime(9,30),"early_checkout");db.update("UPDATE rooms SET enabled=false WHERE id=?",room);
        var value=overview();assertEquals(1,value.path("top_seats").size());var top=value.path("top_seats").get(0);assertEquals(Set.of("seat_id","room_id","room_name","seat_no","usage_seconds","checkin_count"),keys(top));assertEquals(seat1,top.path("seat_id").asLong());decimal("1800",top.path("usage_seconds"));
        var heat=data("/api/stats/heatmap?room_id="+room+"&date="+base);assertEquals(Set.of("snapshot_at","campus_zone","date","room","seats","hours","ends","cells"),keys(heat));assertEquals(Set.of("id","name"),keys(heat.path("room")));assertEquals(24,heat.path("hours").size());assertEquals("00:00",heat.path("hours").get(0).asText());assertEquals("24:00",heat.path("ends").get(23).asText());assertEquals(2,heat.path("seats").size());
        int index=-1;for(int i=0;i<heat.path("seats").size();i++){assertEquals(Set.of("id","seat_no","row_no","col_no"),keys(heat.path("seats").get(i)));if(heat.path("seats").get(i).path("id").asLong()==seat1)index=i;}assertTrue(index>=0);assertEquals(24,heat.path("cells").get(index).size());assertEquals(1,heat.path("cells").get(index).get(9).asInt());assertEquals(0,heat.path("cells").get(index).get(8).asInt());
        for(String sentinel:List.of(IDENTITY,PRIVATE,"s29_owner","13800138000","private@example.test","password_hash")){assertFalse(value.toString().contains(sentinel));assertFalse(heat.toString().contains(sentinel));}
    }
    @Test void topTenUsesActualUsageAndStableTieOrderAndPeakHasTwentyFourExplicitHourBuckets()throws Exception {
        db.update("UPDATE rooms SET seat_cols=12 WHERE id=?",room);var seats=new ArrayList<Long>(List.of(seat1,seat2));for(int col=3;col<=12;col++)seats.add(seat(room,String.format("%02d",col),col));clock.set(base.atTime(16,0));
        // Sequential 30-minute historical uses by one owner, never overlapping fake occupancy.
        for(int i=0;i<12;i++){var from=base.atTime(8,0).plusMinutes(i*30L);var end=from.plusMinutes(30);long id=reservation(seats.get(i),base,from.toLocalTime().toString(),end.toLocalTime().toString(),"completed",base.atTime(7,0),end,"auto_end");usage(id,from,end,"auto_end");}
        var value=overview();assertEquals(10,value.path("top_seats").size());for(int i=0;i<10;i++){var row=value.path("top_seats").get(i);assertEquals(seats.get(i).longValue(),row.path("seat_id").asLong());decimal("1800",row.path("usage_seconds"));assertEquals(1,row.path("checkin_count").asInt());}
        assertEquals(24,value.path("peak").size());for(int hour=0;hour<24;hour++){var row=value.path("peak").get(hour);assertEquals(Set.of("hour","usage_seconds","checkin_count"),keys(row));assertEquals(String.format("%02d:00",hour),row.path("hour").asText());decimal(hour>=8&&hour<14?"3600":"0",row.path("usage_seconds"));assertEquals(hour>=8&&hour<14?2:0,row.path("checkin_count").asInt());}
    }
    @Test void allAggregatesAreReadOnlyAndRejectInvalidQueriesOrUnauthorizedRoles()throws Exception {
        long id=reservation(seat1,base,"09:00","10:00","pending",base.atTime(8,0),null,null);var before=new LinkedHashMap<String,List<Map<String,Object>>>();for(String table:List.of("reservations","usage_records","violations","complaints","notifications","audit_events"))before.put(table,rows(table));
        overview();data("/api/stats/heatmap?room_id="+room+"&date="+base);for(var item:before.entrySet())assertEquals(item.getValue(),rows(item.getKey()),item.getKey());assertEquals("pending",db.one("SELECT status FROM reservations WHERE id=?",id).get("status"));
        http.perform(get("/api/stats/overview")).andExpect(status().isUnauthorized());http.perform(get("/api/stats/overview").header("Authorization","Bearer "+ownerToken)).andExpect(status().isForbidden());http.perform(get("/api/stats/heatmap?room_id="+room).header("Authorization","Bearer "+ownerToken)).andExpect(status().isForbidden());
        for(String query:List.of("room_id=0","room_id=abc","date=0000-01-01","date=2026-02-30","date=2026-1-01","extra=1"))http.perform(get("/api/stats/overview?"+query).header("Authorization","Bearer "+adminToken)).andExpect(status().isBadRequest());
        http.perform(get("/api/stats/overview?room_id=9223372036854775807").header("Authorization","Bearer "+adminToken)).andExpect(status().isNotFound());
        data("/api/stats/overview?date=9999-12-31");db.update("UPDATE users SET status='disabled' WHERE id=?",admin);http.perform(get("/api/stats/overview").header("Authorization","Bearer "+adminToken)).andExpect(status().isUnauthorized());
    }
    @Test void equallyUsedSeatsOrderByRoomIdThenSeatNumberDespiteOppositeRoomNames()throws Exception {
        db.update("UPDATE rooms SET name='Z first room by stable ID' WHERE id=?",room);
        long other=db.insert("INSERT INTO rooms(name,seat_rows,seat_cols) VALUES('A later room by stable ID',1,1)");opening.initializeDefaults(other);long otherSeat=seat(other,"00",1);
        // Insertion order, display-name order and seat-number order across rooms all differ.
        for(int i=0;i<3;i++){long seat=List.of(seat2,otherSeat,seat1).get(i);var start=base.atTime(9+i,0);var end=start.plusMinutes(30);long reservation=reservation(seat,base,start.toLocalTime().toString(),end.toLocalTime().toString(),"completed",base.atTime(8,0),end,"early_checkout");usage(reservation,start,end,"early_checkout");}
        var result=data("/api/stats/overview").path("top_seats");assertEquals(3,result.size());
        assertEquals(seat1,result.get(0).path("seat_id").asLong());assertEquals(seat2,result.get(1).path("seat_id").asLong());assertEquals(otherSeat,result.get(2).path("seat_id").asLong());
        assertEquals(room,result.get(0).path("room_id").asLong());assertEquals(other,result.get(2).path("room_id").asLong());for(var item:result)decimal("1800",item.path("usage_seconds"));
    }
}
