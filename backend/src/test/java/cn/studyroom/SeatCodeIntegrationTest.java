package cn.studyroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.io.ByteArrayInputStream;
import java.time.*;
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
class SeatCodeIntegrationTest {
    @TestConfiguration static class TimeConfig { @Bean @Primary MinuteBookingIntegrationTest.TestClock testClock() { var c=new MinuteBookingIntegrationTest.TestClock();c.set(LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1).atTime(9,0));return c; } }
    @Autowired Store db;
    @Autowired BookingService bookings;
    @Autowired SeatCodeService codes;
    @Autowired OpeningScheduleService opening;
    @Autowired ResourceService resources;
    @Autowired MinuteBookingIntegrationTest.TestClock clock;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    long admin,student,staff,room,seat1,seat2;
    LocalDate base;
    String remote;
    @BeforeEach void fixture() {
        assertEquals("studyroom_java_test",db.one("SELECT current_database() AS name").get("name"));
        for(String table:List.of("notifications","reminders","complaint_attachments","complaint_events","complaints","resource_change_operations","usage_records","seat_codes","violations","resource_unavailability","opening_windows","opening_schedules","audit_events","credit_logs","waitlist","reservations","seats","rooms","users")) db.update("DELETE FROM "+table);
        base=LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1);clock.set(base.atTime(9,0));remote="s09-test-"+UUID.randomUUID();
        String hash=new BCryptPasswordEncoder().encode("S02-test-only-password");
        admin=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s09_admin',?,'admin','S09-admin')",hash);
        student=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s09_student',?,'student','S09-student')",hash);
        staff=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s09_staff',?,'staff','S09-staff')",hash);
        room=db.insert("INSERT INTO rooms(name,seat_rows,seat_cols) VALUES('S09模拟空间',1,2)");opening.initializeDefaults(room);
        seat1=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'01',1,1)",room);seat2=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'02',1,2)",room);
    }
    @AfterEach void clearRate() { redis.delete("studyroom-java:rate:login:"+remote); }
    void at(int h,int m,int s) { clock.set(base.atTime(h,m,s)); }
    String token(String role) throws Exception {
        var result=http.perform(post("/api/auth/login").with(r->{r.setRemoteAddr(remote);return r;}).contentType("application/json").content(json.writeValueAsString(Map.of("username","s09_"+role,"password","S02-test-only-password")))).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }
    long reservation(long user,long seat) { return Store.id(bookings.create(user,new Requests.Reservation(seat,base.toString(),"10:00","11:00")),"id"); }
    String code(long seat) { codes.generate(admin,seat,new Requests.SeatCodeChange(0L,"测试实际生成座位码"));return current(seat); }
    String current(long seat) { return Store.text(db.one("SELECT code_value FROM seat_codes WHERE seat_id=?",seat),"code_value"); }
    String path(long seat) { return "/api/admin/seats/"+seat+"/code"; }
    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder req,String token) { return req.header("Authorization","Bearer "+token); }
    MockHttpServletRequestBuilder checkin(long id,String code,String token) throws Exception { return auth(post("/api/reservations/"+id+"/checkin"),token).contentType("application/json").content(json.writeValueAsString(Map.of("seat_code",code))); }
    Map<String,Object> row(long id) { return db.one("SELECT * FROM reservations WHERE id=?",id); }
    static String decode(byte[] png) throws Exception {
        assertArrayEquals(new byte[]{(byte)137,80,78,71,13,10,26,10},Arrays.copyOf(png,8));var image=ImageIO.read(new ByteArrayInputStream(png));
        int width=image.getWidth(),height=image.getHeight();var source=new RGBLuminanceSource(width,height,image.getRGB(0,0,width,height,null,0,width));
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
    }
    @Test void actualPngRoundTripsAndPublicViewsNeverExposeCode() throws Exception {
        String adminToken=token("admin"),userToken=token("student");
        http.perform(auth(get(path(seat1)),adminToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.configured").value(false)).andExpect(jsonPath("$.data.version").value(0));
        http.perform(auth(get(path(seat1)+"/png"),adminToken)).andExpect(status().isConflict());
        http.perform(auth(post(path(seat1)),adminToken).contentType("application/json").content("{\"version\":0,\"reason\":\"首次贴码\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        var response=http.perform(auth(get(path(seat1)+"/png"),adminToken)).andExpect(status().isOk()).andExpect(content().contentType("image/png")).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse();
        String payload=decode(response.getContentAsByteArray());assertEquals(current(seat1),payload);assertTrue(payload.matches("studyroom-seat:v1:[A-Za-z0-9_-]{43}"));
        var metadata=http.perform(auth(get(path(seat1)),adminToken)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertFalse(metadata.contains(payload));assertFalse(metadata.contains("code_value"));
        for(String url:List.of("/api/rooms","/api/rooms/"+room+"/seats?date="+base+"&start=10:00&end=11:00")) {
            String body=http.perform(auth(get(url),userToken)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertFalse(body.contains(payload));assertFalse(body.contains("code_value"));
        }
        assertEquals(1,db.count("SELECT version FROM rooms WHERE id=?",room));assertEquals(1,db.count("SELECT version FROM seats WHERE id=?",seat1));
        long id=reservation(student,seat1);at(10,0,0);http.perform(checkin(id,payload,userToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("checked_in"));
    }
    @Test void pngExpectedVersionRejectsMetadataSnapshotInvalidatedByConcurrentRotation() throws Exception {
        String token=token("admin"),original=code(seat1);
        http.perform(auth(get(path(seat1)),token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        codes.rotate(admin,seat1,new Requests.SeatCodeChange(1L,"另一管理员在元数据与PNG请求之间轮换"));
        http.perform(auth(get(path(seat1)+"/png?expected_version=1"),token)).andExpect(status().isConflict()).andExpect(content().contentTypeCompatibleWith("application/json"));
        byte[] currentPng=http.perform(auth(get(path(seat1)+"/png?expected_version=2"),token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertEquals(current(seat1),decode(currentPng));assertNotEquals(original,decode(currentPng));
        byte[] compatiblePng=http.perform(auth(get(path(seat1)+"/png"),token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();assertEquals(decode(currentPng),decode(compatiblePng));
        http.perform(auth(get(path(seat1)+"/png?expected_version=0"),token)).andExpect(status().isBadRequest());
    }
    @Test void adminOnlyVersionedRotationEnableAndRedactedAudit() throws Exception {
        String token=token("admin"),user=token("student"),staffToken=token("staff");String old=code(seat1);
        http.perform(get(path(seat1))).andExpect(status().isUnauthorized());
        for(String other:List.of(user,staffToken)) {
            http.perform(auth(get(path(seat1)),other)).andExpect(status().isForbidden());http.perform(auth(get(path(seat1)+"/png"),other)).andExpect(status().isForbidden());http.perform(auth(post(path(seat1)),other).contentType("application/json").content("{\"version\":0,\"reason\":\"越权\"}")).andExpect(status().isForbidden());
        }
        assertEquals(403,assertThrows(Api.Failure.class,()->codes.generate(student,seat2,new Requests.SeatCodeChange(0L,"越权"))).status);
        http.perform(auth(post(path(seat1)+"/rotate"),token).contentType("application/json").content(json.writeValueAsString(Map.of("version",1,"reason","换贴纸 "+old)))).andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(2));
        assertNotEquals(old,current(seat1));http.perform(auth(patch(path(seat1)),token).contentType("application/json").content("{\"version\":1,\"enabled\":false,\"reason\":\"过期\"}")).andExpect(status().isConflict());
        codes.setEnabled(admin,seat1,new Requests.SeatCodeStatus(2L,false,"停用"));assertFalse((Boolean)codes.metadata(admin,seat1).get("enabled"));
        assertEquals(409,assertThrows(Api.Failure.class,()->codes.setEnabled(admin,seat1,new Requests.SeatCodeStatus(3L,false,"重复停用"))).status);
        String disabled=current(seat1);codes.rotate(admin,seat1,new Requests.SeatCodeChange(3L,"停用时换贴纸"));assertFalse((Boolean)codes.metadata(admin,seat1).get("enabled"));assertNotEquals(disabled,decode(codes.png(admin,seat1)));
        String audit=db.list("SELECT * FROM audit_events").toString();assertFalse(audit.contains(old));assertFalse(audit.contains(old.substring(SeatCodeService.PREFIX.length())));assertFalse(audit.contains(current(seat1)));assertTrue(audit.contains("已脱敏"));
        assertEquals(404,assertThrows(Api.Failure.class,()->codes.metadata(admin,Long.MAX_VALUE)).status);assertEquals(400,assertThrows(Api.Failure.class,()->codes.rotate(admin,seat1,new Requests.SeatCodeChange(4L," "))).status);
    }
    @Test void checkinRequiresOwnerCurrentCodeAndCannotUseOldGenericBypass() throws Exception {
        String token=token("student"),other=token("staff"),adminToken=token("admin");long id=reservation(student,seat1);String expected=code(seat1),wrong=code(seat2);at(10,0,0);
        http.perform(checkin(id,expected,other)).andExpect(status().isForbidden());http.perform(checkin(id,expected,adminToken)).andExpect(status().isForbidden());
        http.perform(post("/api/reservations/"+id+"/checkin")).andExpect(status().isUnauthorized());
        assertEquals(400,assertThrows(Api.Failure.class,()->bookings.action(student,id,"checkin")).status);
        http.perform(auth(post("/api/reservations/"+id+"/checkin"),token)).andExpect(status().isBadRequest());
        for(String body:List.of("{}","{\"seat_id\":1}","{\"seat_code\":123}","{\"seat_code\":\"x\",\"seat_id\":1}","{\"seat_code\":null}")) http.perform(auth(post("/api/reservations/"+id+"/checkin"),token).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        http.perform(checkin(id,"x".repeat(200),token)).andExpect(status().isBadRequest());http.perform(checkin(id,wrong,token)).andExpect(status().isConflict());assertEquals("pending",row(id).get("status"));
        http.perform(checkin(id,expected,token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("checked_in"));Object original=db.one("SELECT checkin_at FROM usage_records WHERE reservation_id=?",id).get("checkin_at");long audits=db.count("SELECT count(*) FROM audit_events WHERE action='reservation.checkin'");
        at(10,1,0);http.perform(checkin(id,expected,token)).andExpect(status().isConflict());assertEquals(original,db.one("SELECT checkin_at FROM usage_records WHERE reservation_id=?",id).get("checkin_at"));assertEquals(audits,db.count("SELECT count(*) FROM audit_events WHERE action='reservation.checkin'"));assertEquals(1,db.count("SELECT count(*) FROM notifications WHERE type='reservation_checkin'"));
    }
    @Test void startAndFourteenFiftyNineAreAllowedButBeforeStartIsConflict() throws Exception {
        long id=reservation(student,seat1),second=reservation(staff,seat2);String token=token("student"),staffToken=token("staff"),firstCode=code(seat1),secondCode=code(seat2);
        at(9,59,59);http.perform(checkin(id,firstCode,token)).andExpect(status().isConflict());assertEquals("pending",row(id).get("status"));
        at(10,0,0);http.perform(checkin(id,firstCode,token)).andExpect(status().isOk());
        at(10,14,59);http.perform(checkin(second,secondCode,staffToken)).andExpect(status().isOk());assertEquals(base.atTime(10,14,59),LocalDateTime.parse(db.one("SELECT checkin_at FROM usage_records WHERE reservation_id=?",second).get("checkin_at").toString()));
    }
    @Test void deadlineAndDetailCommitTruthAndForeignDetailCannotSettle() throws Exception {
        long id=reservation(student,seat1),second=reservation(staff,seat2);String token=token("student"),staffToken=token("staff"),payload=code(seat1);at(10,15,0);
        http.perform(auth(get("/api/reservations/"+second),token)).andExpect(status().isForbidden());assertEquals("pending",row(second).get("status"));
        http.perform(checkin(id,payload,token)).andExpect(status().isConflict()).andExpect(jsonPath("$.details.status").value("unused_ended"));assertEquals("no_show",row(id).get("termination_reason"));assertNull(row(id).get("checkin_at"));
        http.perform(auth(get("/api/reservations/"+second),staffToken)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("unused_ended")).andExpect(jsonPath("$.data.allowed_actions").isEmpty());assertEquals(2,db.count("SELECT count(*) FROM violations"));
        http.perform(auth(get("/api/reservations/"+Long.MAX_VALUE),token)).andExpect(status().isNotFound());http.perform(auth(get("/api/reservations/"+id),token)).andExpect(status().isOk());assertEquals(2,db.count("SELECT count(*) FROM violations"));
    }
    @Test void missingDisabledRotatedAndUnavailableResourcesRefuseWithoutChanges() throws Exception {
        long id=reservation(student,seat1);String token=token("student"),fake=SeatCodeService.PREFIX+"A".repeat(43);at(10,0,0);
        http.perform(checkin(id,fake,token)).andExpect(status().isConflict());String old=code(seat1);
        codes.rotate(admin,seat1,new Requests.SeatCodeChange(1L,"换码"));http.perform(checkin(id,old,token)).andExpect(status().isConflict());String value=current(seat1);
        codes.setEnabled(admin,seat1,new Requests.SeatCodeStatus(2L,false,"停用码"));http.perform(checkin(id,value,token)).andExpect(status().isConflict());codes.setEnabled(admin,seat1,new Requests.SeatCodeStatus(3L,true,"恢复码"));
        db.update("UPDATE seats SET status='maintenance' WHERE id=?",seat1);http.perform(checkin(id,value,token)).andExpect(status().isConflict());db.update("UPDATE seats SET status='available' WHERE id=?",seat1);
        db.update("UPDATE rooms SET enabled=FALSE WHERE id=?",room);http.perform(checkin(id,value,token)).andExpect(status().isConflict());assertEquals("pending",row(id).get("status"));assertNull(row(id).get("checkin_at"));assertEquals(0,db.count("SELECT count(*) FROM notifications WHERE type='reservation_checkin'"));
    }
    @Test void auditAndNotificationFailuresRollbackCheckinAndDetailSettlement() throws Exception {
        long id=reservation(student,seat1);String token=token("student"),payload=code(seat1);at(10,0,0);
        for(boolean auditFault:List.of(true,false)) {
            String table=auditFault?"audit_events":"notifications",expression=auditFault?"action<>'reservation.checkin'":"type<>'reservation_checkin'";
            db.update("ALTER TABLE "+table+" ADD CONSTRAINT s09_fail CHECK("+expression+") NOT VALID");
            try { http.perform(checkin(id,payload,token)).andExpect(status().isInternalServerError());assertEquals("pending",row(id).get("status"));assertNull(row(id).get("checkin_at"));assertEquals(0,db.count("SELECT count(*) FROM usage_records"));assertEquals(0,db.count("SELECT count(*) FROM audit_events WHERE action='reservation.checkin'"));assertEquals(0,db.count("SELECT count(*) FROM notifications WHERE type='reservation_checkin'")); }
            finally { db.update("ALTER TABLE "+table+" DROP CONSTRAINT s09_fail"); }
        }
        at(10,15,0);db.update("ALTER TABLE audit_events ADD CONSTRAINT s09_fail CHECK(action<>'reservation.unused_end') NOT VALID");
        try { http.perform(auth(get("/api/reservations/"+id),token)).andExpect(status().isInternalServerError());assertEquals("pending",row(id).get("status"));assertEquals(0,db.count("SELECT count(*) FROM violations")); }
        finally { db.update("ALTER TABLE audit_events DROP CONSTRAINT s09_fail"); }
    }
    @Test void codeManagementAuditFailureAndConcurrentVersionChangeAreAtomic() throws Exception {
        db.update("ALTER TABLE audit_events ADD CONSTRAINT s09_fail CHECK(action NOT LIKE 'seat_code.%') NOT VALID");
        try { assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->codes.generate(admin,seat1,new Requests.SeatCodeChange(0L,"生成故障")));assertEquals(0,db.count("SELECT count(*) FROM seat_codes")); }
        finally { db.update("ALTER TABLE audit_events DROP CONSTRAINT s09_fail"); }
        String old=code(seat1);var original=db.one("SELECT * FROM seat_codes WHERE seat_id=?",seat1);
        db.update("ALTER TABLE audit_events ADD CONSTRAINT s09_fail CHECK(action NOT LIKE 'seat_code.%') NOT VALID");
        try {
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->codes.rotate(admin,seat1,new Requests.SeatCodeChange(1L,"轮换故障")));assertEquals(original,db.one("SELECT * FROM seat_codes WHERE seat_id=?",seat1));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->codes.setEnabled(admin,seat1,new Requests.SeatCodeStatus(1L,false,"启停故障")));assertEquals(original,db.one("SELECT * FROM seat_codes WHERE seat_id=?",seat1));
        } finally { db.update("ALTER TABLE audit_events DROP CONSTRAINT s09_fail"); }
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results=new ArrayList<>();for(int n=0;n<2;n++) results.add(pool.submit(()->{ready.countDown();start.await();try { codes.rotate(admin,seat1,new Requests.SeatCodeChange(1L,"并发轮换"));return 200; } catch(Api.Failure failure) { return failure.status; }}));
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();List<Integer> actual=new ArrayList<>();for(var result:results) actual.add(result.get(20,TimeUnit.SECONDS));Collections.sort(actual);assertEquals(List.of(200,409),actual);
        } finally { pool.shutdownNow(); }
        assertNotEquals(old,current(seat1));assertEquals(2,db.count("SELECT version FROM seat_codes WHERE seat_id=?",seat1));assertEquals(1,db.count("SELECT count(*) FROM audit_events WHERE action='seat_code.rotate'"));
    }
    @Test void unusedSeatDeletionCascadesOnlyCurrentCodeAndReservationHistoryStillProtects() {
        code(seat1);code(seat2);long id=reservation(student,seat1);
        assertEquals(409,assertThrows(Api.Failure.class,()->resources.deleteSeat(admin,seat1,new Requests.ResourceDelete(1L,"有预约历史拒删"))).status);
        assertEquals(1,db.count("SELECT count(*) FROM seat_codes WHERE seat_id=?",seat1));
        resources.deleteSeat(admin,seat2,new Requests.ResourceDelete(1L,"删除无历史模拟座位"));assertEquals(0,db.count("SELECT count(*) FROM seat_codes WHERE seat_id=?",seat2));
        assertEquals("pending",row(id).get("status"));assertEquals(1,db.count("SELECT count(*) FROM audit_events WHERE action='seat_code.generate' AND object_id=?",Long.toString(seat2)));
    }
    @Test void codeRotationAndCheckinUseSameMutexWithoutStaleAcceptance() throws Exception {
        long id=reservation(student,seat1);String old=code(seat1);at(10,0,0);var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var rotation=pool.submit(()->{gate.await();codes.rotate(admin,seat1,new Requests.SeatCodeChange(1L,"并发换码"));return 200;});
            var checkin=pool.submit(()->{gate.await();try { return bookings.checkin(student,id,old).status(); } catch(Api.Failure e) { return e.status; }});gate.countDown();assertEquals(200,rotation.get(20,TimeUnit.SECONDS));int result=checkin.get(20,TimeUnit.SECONDS);
            assertTrue(result==200||result==409);assertEquals(result==200?"checked_in":"pending",row(id).get("status"));assertEquals(result==200?1:0,db.count("SELECT count(*) FROM audit_events WHERE action='reservation.checkin'"));
            assertEquals(409,assertThrows(Api.Failure.class,()->bookings.checkin(student,id,old)).status);
        } finally { pool.shutdownNow(); }
    }
}
