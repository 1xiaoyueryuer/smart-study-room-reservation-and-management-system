package cn.studyroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named="STUDYROOM_INTEGRATION",matches="true")
@SpringBootTest(properties={"spring.datasource.url=${STUDYROOM_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5433/studyroom_java_test}","studyroom.seed=false","studyroom.scheduler-enabled=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class ResourceIntegrationTest {
    @Autowired MockMvc http;
    @Autowired Store db;
    @Autowired OpeningScheduleService opening;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    @Autowired BookingService bookings;
    @Autowired ResourceService resources;
    @Autowired Clock clock;
    Map<String,Long> ids;
    String remote;
    long room,seat1,seat2;
    static final String PASSWORD="S02-test-only-password";
    @BeforeEach void fixture() {
        assertEquals("studyroom_java_test",db.one("SELECT current_database() AS name").get("name"));
        for(String table:List.of("usage_records","seat_codes","violations","resource_unavailability","opening_windows","opening_schedules","audit_events","credit_logs","notifications","waitlist","reservations","seats","rooms","users")) db.update("DELETE FROM "+table);
        ids=new LinkedHashMap<>();remote="s05-test-"+UUID.randomUUID();String hash=new BCryptPasswordEncoder().encode(PASSWORD);
        for(String role:List.of("student","staff","admin")) ids.put(role,db.insert("INSERT INTO users(username,password_hash,real_name,campus_id,role) VALUES(?,?,?,?,?)","s05_"+role,hash,"S05模拟"+role,"S05-"+role,role));
        room=db.insert("INSERT INTO rooms(name,building,floor,location,seat_rows,seat_cols) VALUES('S05模拟空间','模拟楼','B1','东侧',2,3)");
        opening.initializeDefaults(room);
        seat1=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'A1',1,1)",room);
        seat2=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'A2',1,2)",room);
    }
    @AfterEach void clearRate() { if(remote!=null) redis.delete("studyroom-java:rate:login:"+remote); }
    String token(String role) throws Exception {
        var response=http.perform(post("/api/auth/login").with(r->{r.setRemoteAddr(remote);return r;}).contentType("application/json").content(json.writeValueAsString(Map.of("username","s05_"+role,"password",PASSWORD))))
            .andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).path("data").path("token").asText();
    }
    Map<String,Object> roomBody(long version,boolean enabled) {
        var body=new LinkedHashMap<String,Object>();body.put("name","S05模拟空间");body.put("building","模拟楼");body.put("floor","B1");body.put("location","东侧");body.put("seat_rows",2);body.put("seat_cols",3);body.put("description","模拟资源维护");body.put("enabled",enabled);body.put("version",version);body.put("reason","测试维护资源");return body;
    }
    Map<String,Object> seatBody(String number,int row,int col,long version,String status) {
        return new LinkedHashMap<>(Map.of("seat_no",number,"row_no",row,"col_no",col,"version",version,"status",status,"zone","regular","has_power",false,"near_window",false,"reason","测试座位维护"));
    }
    MockHttpServletRequestBuilder payload(MockHttpServletRequestBuilder request,String token,Object body) throws Exception { return request.header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(body)); }
    long roomVersion() { return Store.id(resources.room(room),"version"); }
    Requests.Reservation booking(long seat) { return new Requests.Reservation(seat,LocalDate.now(clock).plusDays(1).toString(),"10:00","11:00"); }
    @Test void seatLifecycleAndResourceCapacityReflectActualStatusNotLayoutOrBookings() throws Exception {
        String admin=token("admin"),student=token("student");
        bookings.create(ids.get("student"),booking(seat1));
        var publicRooms=http.perform(get("/api/rooms").header("Authorization","Bearer "+student)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].seat_count").value(2)).andExpect(jsonPath("$.data[0].capacity").value(2)).andExpect(jsonPath("$.data[0].bookable_capacity").value(2)).andReturn();
        assertFalse(publicRooms.getResponse().getContentAsString().contains("user_id"));
        var created=http.perform(payload(post("/api/admin/rooms/"+room+"/seats"),admin,seatBody("B1",2,1,1,"available"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1)).andReturn();
        long id=json.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();
        assertEquals(2,roomVersion());
        http.perform(payload(put("/api/admin/seats/"+id),admin,seatBody("B2",2,2,1,"maintenance"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2));
        http.perform(get("/api/admin/rooms/"+room+"/seats").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.room.seat_count").value(3)).andExpect(jsonPath("$.data.room.capacity").value(2));
        http.perform(get("/api/rooms/"+room+"/seats").param("date",LocalDate.now(clock).plusDays(1).toString()).param("start","10:00").param("end","11:00").header("Authorization","Bearer "+student)).andExpect(status().isOk()).andExpect(jsonPath("$.data.room.capacity").value(2));
        http.perform(payload(delete("/api/admin/seats/"+id),admin,Map.of("version",2,"reason","测试删除未使用座位"))).andExpect(status().isOk());
        assertEquals(4,roomVersion());assertEquals(2,Store.id(resources.room(room),"seat_count"));
        assertEquals(3,db.count("SELECT count(*) FROM audit_events WHERE object_type='seat'"));
    }
    @Test void uniquenessBoundsVersionAndPermissionsRejectWithoutPartialChanges() throws Exception {
        String admin=token("admin"),student=token("student");
        http.perform(payload(post("/api/admin/rooms/"+room+"/seats"),student,seatBody("B1",2,1,1,"available"))).andExpect(status().isForbidden());
        http.perform(payload(post("/api/admin/rooms/"+room+"/seats"),admin,seatBody("A1",2,1,1,"available"))).andExpect(status().isConflict());
        http.perform(payload(post("/api/admin/rooms/"+room+"/seats"),admin,seatBody("B1",1,1,1,"available"))).andExpect(status().isConflict());
        http.perform(payload(post("/api/admin/rooms/"+room+"/seats"),admin,seatBody("B1",3,1,1,"available"))).andExpect(status().isBadRequest());
        var missingReason=seatBody("B1",2,1,1,"available");missingReason.remove("reason");
        http.perform(payload(post("/api/admin/rooms/"+room+"/seats"),admin,missingReason)).andExpect(status().isBadRequest());
        http.perform(payload(put("/api/admin/seats/999999999"),admin,seatBody("B1",2,1,1,"available"))).andExpect(status().isNotFound());
        assertEquals(1,roomVersion());assertEquals(2,db.count("SELECT count(*) FROM seats"));assertEquals(0,db.count("SELECT count(*) FROM audit_events"));
    }
    @Test void actualConcurrentSeatEditsAllowOnlyOneExpectedVersion() throws Exception {
        String admin=token("admin");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var ready=new java.util.concurrent.CountDownLatch(2);var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var futures=new ArrayList<java.util.concurrent.Future<Integer>>();
            for(int i=0;i<2;i++) { final int n=i;futures.add(pool.submit(()->{ready.countDown();start.await();return http.perform(payload(put("/api/admin/seats/"+seat2),admin,seatBody("RENAMED-"+n,1,2,1,"available"))).andReturn().getResponse().getStatus();})); }
            assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));start.countDown();
            var statuses=new ArrayList<Integer>();for(var f:futures) statuses.add(f.get(30,java.util.concurrent.TimeUnit.SECONDS));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);
            assertEquals(2,roomVersion());assertEquals(1,db.count("SELECT count(*) FROM audit_events"));
            http.perform(payload(put("/api/admin/rooms/"+room),admin,roomBody(1,true))).andExpect(status().isConflict());
        } finally { start.countDown();pool.shutdownNow(); }
    }
    @Test void activeReservationImpactAndHistoricalDeletionAreRejectedButDescriptionsAllowed() throws Exception {
        String admin=token("admin");long reservation=Store.id(bookings.create(ids.get("student"),booking(seat1)),"id");
        http.perform(payload(put("/api/admin/seats/"+seat1),admin,seatBody("A1",1,1,1,"disabled"))).andExpect(status().isConflict());
        http.perform(payload(put("/api/admin/seats/"+seat1),admin,seatBody("MOVED",2,1,1,"available"))).andExpect(status().isConflict());
        http.perform(payload(put("/api/admin/rooms/"+room),admin,roomBody(1,false))).andExpect(status().isConflict());
        var move=roomBody(1,true);move.put("floor","2F");http.perform(payload(put("/api/admin/rooms/"+room),admin,move)).andExpect(status().isConflict());
        http.perform(payload(put("/api/admin/rooms/"+room+"/opening-windows"),admin,Map.of("date",booking(seat1).date(),"windows",List.of(Map.of("start","12:00","end","22:00")),"version",1,"reason","测试缩短开放"))).andExpect(status().isConflict());
        var layout=roomBody(1,true);layout.put("seat_cols",4);http.perform(payload(put("/api/admin/rooms/"+room),admin,layout)).andExpect(status().isOk());
        assertEquals("pending",db.one("SELECT status FROM reservations WHERE id=?",reservation).get("status"));
        var describe=roomBody(2,true);describe.put("description","只修改说明，不中止预约");http.perform(payload(put("/api/admin/rooms/"+room),admin,describe)).andExpect(status().isOk());
        db.update("UPDATE reservations SET status='completed' WHERE id=?",reservation);
        http.perform(payload(delete("/api/admin/seats/"+seat1),admin,Map.of("version",1,"reason","测试历史保护"))).andExpect(status().isConflict());
        http.perform(payload(delete("/api/admin/rooms/"+room),admin,Map.of("version",3,"reason","测试历史保护"))).andExpect(status().isConflict());
        assertEquals(1,db.count("SELECT count(*) FROM reservations"));assertEquals(0,db.count("SELECT count(*) FROM credit_logs"));
    }
    @Test void disabledRoomCannotBeBookedAndRetiredQueuesNeverPromote() throws Exception {
        String admin=token("admin");
        db.update("INSERT INTO waitlist(user_id,room_id,res_date,start_time,end_time) VALUES(?,?,?,TIME '10:00',TIME '11:00')",ids.get("student"),room,java.sql.Date.valueOf(booking(seat1).date()));
        http.perform(payload(put("/api/admin/rooms/"+room),admin,roomBody(1,false))).andExpect(status().isOk()).andExpect(jsonPath("$.data.capacity").value(2)).andExpect(jsonPath("$.data.bookable_capacity").value(0));
        assertEquals(409,assertThrows(Api.Failure.class,()->bookings.create(ids.get("staff"),booking(seat1))).status);
        assertEquals(410,assertThrows(Api.Failure.class,()->bookings.allocate(ids.get("staff"),new Requests.Allocation(room,booking(seat1).date(),"10:00","11:00",null,null,null,0,true))).status);
        assertEquals(410,assertThrows(Api.Failure.class,()->bookings.join(ids.get("staff"),new Requests.Wait(room,booking(seat1).date(),"10:00","11:00",null,null,null))).status);
        var maintenance=seatBody("A2",1,2,1,"maintenance");maintenance.put("reason","仅维护座位，空间仍保持原原因");
        http.perform(payload(put("/api/admin/seats/"+seat2),admin,maintenance)).andExpect(status().isOk());
        assertEquals("测试维护资源",resources.room(room).get("resource_reason"));
        assertEquals(3,roomVersion());
        long closedEmpty=db.insert("INSERT INTO rooms(name,enabled,seat_rows,seat_cols,resource_reason) VALUES('停用空空间模拟',false,1,1,'装修闭馆')");
        http.perform(payload(post("/api/admin/rooms/"+closedEmpty+"/seats/batch"),admin,Map.of("seat_rows",1,"seat_cols",2,"version",1,"reason","生成模拟座位"))).andExpect(status().isOk());
        assertEquals("装修闭馆",resources.room(closedEmpty).get("resource_reason"));
        assertEquals(2,Store.id(resources.room(closedEmpty),"version"));
        assertEquals(0,Store.id(resources.room(closedEmpty),"bookable_capacity"));
        assertEquals(2,db.count("SELECT count(*) FROM seats WHERE room_id=? AND resource_reason='生成模拟座位'",closedEmpty));
        assertEquals(1,db.count("SELECT count(*) FROM audit_events WHERE action='room.seats.batch' AND object_id=? AND reason='生成模拟座位'",Long.toString(closedEmpty)));
        long other=db.insert("INSERT INTO rooms(name,seat_rows,seat_cols) VALUES('另一个模拟空间',1,1)");
        opening.initializeDefaults(other);
        db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'01',1,1)",other);
        db.update("INSERT INTO waitlist(user_id,room_id,res_date,start_time,end_time) VALUES(?,?,?,TIME '10:00',TIME '11:00')",ids.get("staff"),other,java.sql.Date.valueOf(booking(seat1).date()));
        bookings.tick();
        assertEquals("waiting",db.one("SELECT status FROM waitlist WHERE user_id=?",ids.get("student")).get("status"));
        assertEquals("waiting",db.one("SELECT status FROM waitlist WHERE user_id=?",ids.get("staff")).get("status"));
        assertEquals(0,db.count("SELECT count(*) FROM reservations"));
        http.perform(get("/api/stats/overview").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total_seats").value(1));
    }
    @Test void auditFailureRollsBackResourceAndRoomVersionTogether() throws Exception {
        String admin=token("admin");db.update("ALTER TABLE audit_events ADD CONSTRAINT s05_reject_audit CHECK (false) NOT VALID");
        try {
            http.perform(payload(post("/api/admin/rooms/"+room+"/seats"),admin,seatBody("B1",2,1,1,"available"))).andExpect(status().isInternalServerError());
            http.perform(payload(put("/api/admin/seats/"+seat2),admin,seatBody("A2",1,2,1,"maintenance"))).andExpect(status().isInternalServerError());
            var roomEdit=roomBody(1,true);roomEdit.put("description","不应保存");http.perform(payload(put("/api/admin/rooms/"+room),admin,roomEdit)).andExpect(status().isInternalServerError());
            assertEquals(1,roomVersion());assertEquals(2,db.count("SELECT count(*) FROM seats"));assertEquals("available",db.one("SELECT status FROM seats WHERE id=?",seat2).get("status"));
            assertNull(resources.room(room).get("description"));assertEquals(0,db.count("SELECT count(*) FROM audit_events"));
        } finally { db.update("ALTER TABLE audit_events DROP CONSTRAINT s05_reject_audit"); }
    }
    @Test void createBatchEmptyResourceAndDeleteRequireCurrentVersionsAndReasons() throws Exception {
        String admin=token("admin");var newRoom=roomBody(1,true);newRoom.put("name","S05新模拟空间");newRoom.remove("version");
        var response=http.perform(payload(post("/api/admin/rooms"),admin,newRoom)).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1)).andExpect(jsonPath("$.data.capacity").value(0)).andReturn();
        long id=json.readTree(response.getResponse().getContentAsString()).path("data").path("id").asLong();
        http.perform(payload(post("/api/admin/rooms/"+id+"/seats/batch"),admin,Map.of("seat_rows",2,"seat_cols",3,"version",1,"reason","生成模拟座位"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.created").value(6)).andExpect(jsonPath("$.data.room.capacity").value(6));
        http.perform(payload(delete("/api/admin/rooms/"+id),admin,Map.of("version",1,"reason","过期删除"))).andExpect(status().isConflict());
        http.perform(payload(delete("/api/admin/rooms/"+id),admin,Map.of("version",2,"reason","删除空闲模拟空间"))).andExpect(status().isOk());
        assertEquals(0,db.count("SELECT count(*) FROM rooms WHERE id=?",id));assertEquals(3,db.count("SELECT count(*) FROM audit_events"));
    }
}
