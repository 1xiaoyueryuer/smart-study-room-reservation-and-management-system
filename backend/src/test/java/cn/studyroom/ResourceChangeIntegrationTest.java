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
class ResourceChangeIntegrationTest {
    @TestConfiguration static class TimeConfig { @Bean @Primary MinuteBookingIntegrationTest.TestClock testClock() { var c=new MinuteBookingIntegrationTest.TestClock();c.set(LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1).atTime(9,0));return c; } }
    @Autowired Store db;
    @Autowired BookingService bookings;
    @Autowired SeatCodeService codes;
    @Autowired OpeningScheduleService opening;
    @Autowired ResourceService resources;
    @Autowired ResourceChangeService changes;
    @Autowired UsageService usage;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired MinuteBookingIntegrationTest.TestClock clock;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    long admin,student,staff,room,seat1,seat2;
    LocalDate base;
    String remote;
    @BeforeEach void fixture() {
        assertEquals("studyroom_java_test",db.one("SELECT current_database() AS name").get("name"));
        for(String table:List.of("resource_change_operations","usage_records","seat_codes","violations","resource_unavailability","opening_windows","opening_schedules","audit_events","credit_logs","notifications","waitlist","reservations","seats","rooms","users")) db.update("DELETE FROM "+table);
        base=LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1);clock.set(base.atTime(9,0));remote="s13-test-"+UUID.randomUUID();
        String hash=new BCryptPasswordEncoder().encode("S02-test-only-password");
        admin=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s13_admin',?,'admin','S09-admin')",hash);
        student=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s13_student',?,'student','S09-student')",hash);
        staff=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s13_staff',?,'staff','S09-staff')",hash);
        room=db.insert("INSERT INTO rooms(name,seat_rows,seat_cols) VALUES('S13模拟空间',1,2)");opening.initializeDefaults(room);
        seat1=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'01',1,1)",room);seat2=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'02',1,2)",room);
    }
    @AfterEach void clearRate() { redis.delete("studyroom-java:rate:login:"+remote); }
    void at(int h,int m,int s) { clock.set(base.atTime(h,m,s)); }
    String token(String role) throws Exception {
        var result=http.perform(post("/api/auth/login").with(r->{r.setRemoteAddr(remote);return r;}).contentType("application/json").content(json.writeValueAsString(Map.of("username","s13_"+role,"password","S02-test-only-password")))).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }
    long reservation(long user,long seat) { return Store.id(bookings.create(user,new Requests.Reservation(seat,base.toString(),"10:00","11:00")),"id"); }
    String code(long seat) { codes.generate(admin,seat,new Requests.SeatCodeChange(0L,"测试实际生成座位码"));return current(seat); }
    String current(long seat) { return Store.text(db.one("SELECT code_value FROM seat_codes WHERE seat_id=?",seat),"code_value"); }
    String path(long seat) { return "/api/admin/seats/"+seat+"/code"; }
    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder req,String token) { return req.header("Authorization","Bearer "+token); }
    MockHttpServletRequestBuilder checkin(long id,String code,String token) throws Exception { return auth(post("/api/reservations/"+id+"/checkin"),token).contentType("application/json").content(json.writeValueAsString(Map.of("seat_code",code))); }
    Map<String,Object> row(long id) { return db.one("SELECT * FROM reservations WHERE id=?",id); }
    Requests.ResourceChangeProposal proposal(String type,long id,Object body) { return new Requests.ResourceChangeProposal(type,id,json.convertValue(body,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>() {})); }
    Requests.Room roomBody(boolean enabled,String reason) { return new Requests.Room("S13模拟空间",null,null,null,1,2,null,null,null,enabled,Store.id(db.one("SELECT version FROM rooms WHERE id=?",room),"version"),reason); }
    @Test void fractionalTargetIdAndIntegerPayloadCannotBeSilentlyTruncated() throws Exception {
        String token=token("admin");var body=new LinkedHashMap<String,Object>();body.put("change_type","room.update");body.put("target_id",room+0.5);body.put("payload",json.convertValue(roomBody(false,"S13整数精度验证"),Map.class));
        http.perform(auth(post("/api/admin/resource-changes/preview"),token).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
        body.put("target_id",room);
        for(String field:List.of("seat_rows","seat_cols","version")) {
            var payload=json.convertValue(roomBody(false,"S13整数精度验证"),new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>() {});payload.put(field,1.5);body.put("payload",payload);
            http.perform(auth(post("/api/admin/resource-changes/preview"),token).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isBadRequest());
            http.perform(auth(put("/api/admin/rooms/"+room),token).contentType("application/json").content(json.writeValueAsString(payload))).andExpect(status().isBadRequest());
        }
        var seat=json.convertValue(new Requests.Seat("regular",false,false,"maintenance","01",1,1,1L,"S13座位整数精度"),new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>() {});
        for(String field:List.of("row_no","col_no","version")) { var payload=new LinkedHashMap<>(seat);payload.put(field,1.5);var change=new Requests.ResourceChangeProposal("seat.update",seat1,payload);http.perform(auth(post("/api/admin/resource-changes/preview"),token).contentType("application/json").content(json.writeValueAsString(change))).andExpect(status().isBadRequest()); }
        assertEquals(0,db.count("SELECT count(*) FROM resource_change_operations"));assertEquals(1,db.count("SELECT version FROM rooms WHERE id=?",room));
    }
    @SuppressWarnings("unchecked") Requests.ResourceChangeConfirm request(Map<String,Object> preview,String key,Requests.Coordination coordination) { return new Requests.ResourceChangeConfirm((Requests.ResourceChangeProposal)preview.get("normalized_proposal"),preview.get("impact_hash").toString(),key,coordination); }
    Map<String,Object> confirm(Map<String,Object> preview,String key,Requests.Coordination coordination) { return changes.confirm(admin,request(preview,key,coordination)); }
    Requests.Coordination simulatedCoordination() { return new Requests.Coordination(true,"工程模拟管理员现场协调自报，不是实际校园现场核验"); }
    long current(long user,long seat) { return Store.id(bookings.create(user,new Requests.Reservation(seat,base.toString(),"09:00","10:00")),"id"); }
    void sign(long user,long id,long seat) { String payload=code(seat);bookings.checkin(user,id,payload); }
    String snapshot() {
        var state=new LinkedHashMap<String,Object>();for(String table:List.of("rooms","seats","reservations","usage_records","resource_change_operations","opening_schedules","opening_windows","resource_unavailability","notifications","audit_events","violations","credit_logs")) state.put(table,db.list("SELECT to_jsonb(t)::text AS raw FROM "+table+" t ORDER BY to_jsonb(t)::text"));return state.toString();
    }
    long roomVersion() { return Store.id(db.one("SELECT version FROM rooms WHERE id=?",room),"version"); }
    Requests.Seat seatBody(long seat,String no,int col,String status,String reason) { var before=db.one("SELECT * FROM seats WHERE id=?",seat);return new Requests.Seat("regular",false,false,status,no,1,col,Store.id(before,"version"),reason); }
    @Test void previewIsPureAndPendingManagementCancellationKeepsPublicReasonForever() throws Exception {
        long id=reservation(student,seat1);String original=snapshot();var preview=changes.preview(admin,proposal("room.update",room,roomBody(false,"公开的闭馆原因")));assertEquals(original,snapshot());assertEquals(1,((List<?>)preview.get("pending")).size());assertEquals(0,((List<?>)preview.get("in_use")).size());assertEquals("S13模拟空间",preview.get("target_label"));
        String key=UUID.randomUUID().toString();var result=confirm(preview,key,null);assertEquals(1,result.get("cancelled_count"));assertEquals(false,result.get("replayed"));assertEquals("cancelled",row(id).get("status"));assertEquals("admin_resource_change",row(id).get("termination_reason"));assertEquals("公开的闭馆原因",row(id).get("termination_note"));assertEquals(0,db.count("SELECT count(*) FROM usage_records"));assertEquals(0,db.count("SELECT count(*) FROM violations"));assertEquals(0,db.count("SELECT count(*) FROM credit_logs"));
        String after=snapshot();assertEquals(true,confirm(preview,key,null).get("replayed"));assertEquals(after,snapshot());assertEquals(1,db.count("SELECT count(*) FROM notifications WHERE type='management_cancelled'"));
        resources.save(admin,room,roomBody(true,"恢复开放的新说明"));assertEquals("cancelled",row(id).get("status"));assertEquals("公开的闭馆原因",row(id).get("termination_note"));
        String token=token("student");http.perform(auth(get("/api/reservations/"+id),token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.termination_note").value("公开的闭馆原因")).andExpect(jsonPath("$.data.usage_record").isEmpty());
        assertEquals(409,assertThrows(Api.Failure.class,()->resources.deleteSeat(admin,seat1,new Requests.ResourceDelete(1L,"取消后不能物理删历史"))).status);
    }
    @Test void futureUnavailabilityImmediatelyCancelsInUseAndPreservesFractionalUsageWithoutPenalty() throws Exception {
        long id=current(student,seat1);clock.set(base.atTime(9,0,0,123456789));sign(student,id,seat1);clock.set(base.atTime(9,10,0,987654321));
        var preview=changes.preview(admin,proposal("block.create",room,new Requests.Unavailability(seat1,base.atTime(9,30).toString(),base.atTime(9,45).toString(),roomVersion(),"未来设备维护公开原因")));
        assertEquals(1,((List<?>)preview.get("in_use")).size());String before=snapshot();assertEquals(400,assertThrows(Api.Failure.class,()->confirm(preview,UUID.randomUUID().toString(),null)).status);assertEquals(400,assertThrows(Api.Failure.class,()->confirm(preview,UUID.randomUUID().toString(),new Requests.Coordination(false,"未协调"))).status);assertEquals(before,snapshot());
        var result=confirm(preview,UUID.randomUUID().toString(),simulatedCoordination());assertEquals("admin_self_report",result.get("coordination_source"));assertEquals("cancelled",row(id).get("status"));assertEquals("未来设备维护公开原因",row(id).get("termination_note"));assertNull(row(id).get("checkin_at"));assertNull(row(id).get("checkout_at"));
        var record=usage.view(id,base.atTime(9,10,0,987654000));assertEquals("admin_resource_change",record.get("end_reason"));assertEquals(0,new java.math.BigDecimal("600.864198").compareTo((java.math.BigDecimal)record.get("duration_seconds")));assertEquals(base.atTime(9,10,0,987654000),LocalDateTime.parse(record.get("ended_at").toString()));
        assertEquals(0,db.count("SELECT count(*) FROM violations"));assertEquals(0,db.count("SELECT count(*) FROM credit_logs"));String token=token("student");var response=http.perform(auth(get("/api/reservations/"+id),token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("cancelled")).andReturn().getResponse().getContentAsString();assertFalse(response.contains(simulatedCoordination().note()));assertFalse(db.list("SELECT content FROM notifications").toString().contains(simulatedCoordination().note()));assertTrue(db.list("SELECT coordination FROM resource_change_operations").toString().contains(simulatedCoordination().note()));
    }
    @Test void onlyPastOpeningRemovalDoesNotHarmFiveMinutePendingAndRealCodeCheckinStillWorks() {
        long first=current(student,seat1),second=current(staff,seat2);String payload=code(seat1);at(9,5,0);
        var preview=changes.preview(admin,proposal("opening.save",room,new Requests.OpeningChange(null,base.toString(),List.of(new Requests.OpeningWindow("09:05","22:00")),roomVersion(),"仅移除已经过去开放片段")));assertEquals(List.of(),preview.get("pending"));assertEquals(0,confirm(preview,UUID.randomUUID().toString(),null).get("cancelled_count"));assertEquals("pending",row(first).get("status"));assertEquals(200,bookings.checkin(student,first,payload).status());
        var future=proposal("opening.save",room,new Requests.OpeningChange(null,base.toString(),List.of(new Requests.OpeningWindow("09:15","22:00")),roomVersion(),"删除未来剩余段"));var projected=changes.preview(admin,future);assertEquals(1,((List<?>)projected.get("pending")).size());assertEquals(1,((List<?>)projected.get("in_use")).size());
        String before=snapshot();assertEquals(409,assertThrows(Api.Failure.class,()->opening.save(admin,room,new Requests.OpeningChange(null,base.toString(),List.of(new Requests.OpeningWindow("09:15","22:00")),roomVersion(),"旧入口不得绕过"))).status);assertEquals(before,snapshot());assertEquals("pending",row(second).get("status"));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"room","seat_number","seat_position"})
    void physicalLocationAndSeatIdentityChangesAreExplicitlyAffected(String kind) {
        long pending=reservation(student,seat1),used=current(staff,seat2);sign(staff,used,seat2);Requests.ResourceChangeProposal proposal;
        if(kind.equals("room")) proposal=proposal("room.update",room,new Requests.Room("S13模拟空间","搬到东侧",null,null,1,2,null,"新楼","夹层",true,roomVersion(),"空间位置变更"));
        else { if(kind.equals("seat_position")) resources.save(admin,room,new Requests.Room("S13模拟空间",null,null,null,1,3,null,null,null,true,roomVersion(),"仅扩大布局先不影响预约"));proposal=proposal("seat.update",seat1,seatBody(seat1,kind.equals("seat_number")?"NEW-01":"01",kind.equals("seat_position")?3:1,"available","座位身份或位置变更")); }
        var preview=changes.preview(admin,proposal);assertEquals(1,((List<?>)preview.get("pending")).size());assertEquals(kind.equals("room")?1:0,((List<?>)preview.get("in_use")).size());confirm(preview,UUID.randomUUID().toString(),kind.equals("room")?simulatedCoordination():null);assertEquals("cancelled",row(pending).get("status"));assertEquals(kind.equals("room")?"cancelled":"checked_in",row(used).get("status"));
    }
    @Test void descriptionAttributesAndLayoutExpansionDoNotMechanicallyCancel() {
        long pending=reservation(student,seat1),used=current(staff,seat2);sign(staff,used,seat2);
        var preview=changes.preview(admin,proposal("room.update",room,new Requests.Room("S13模拟空间",null,null,null,1,3,"新说明",null,null,true,roomVersion(),"纯说明和布局扩展")));assertEquals(0,confirm(preview,UUID.randomUUID().toString(),null).get("cancelled_count"));
        var seat=db.one("SELECT * FROM seats WHERE id=?",seat1);var attribute=changes.preview(admin,proposal("seat.update",seat1,new Requests.Seat("quiet",true,true,"available","01",1,1,Store.id(seat,"version"),"属性说明更新")));assertEquals(0,confirm(attribute,UUID.randomUUID().toString(),null).get("cancelled_count"));assertEquals("pending",row(pending).get("status"));assertEquals("checked_in",row(used).get("status"));assertEquals(0,db.count("SELECT count(*) FROM notifications WHERE type='management_cancelled'"));
    }
    @Test void weeklyProjectionRespectsExceptionsAndExaminesAllEffectiveFutureDates() {
        LocalDate near=base.plusDays(1),far=near.plusWeeks(1);opening.save(admin,room,new Requests.OpeningChange(null,near.toString(),List.of(new Requests.OpeningWindow("08:00","24:00")),roomVersion(),"明确模拟日期例外"));long protectedId=Store.id(bookings.create(student,new Requests.Reservation(seat1,near.toString(),"22:00","23:00")),"id");
        long imported=db.insert("INSERT INTO reservations(user_id,seat_id,res_date,start_time,end_time,status) VALUES(?,?,?,'10:00','11:00','pending')",staff,seat2,java.sql.Date.valueOf(far));
        var proposal=proposal("opening.save",room,new Requests.OpeningChange(near.getDayOfWeek().getValue(),null,List.of(new Requests.OpeningWindow("08:00","08:30"),new Requests.OpeningWindow("08:30","09:00")),roomVersion(),"周安排缩时，日期例外仍优先"));var preview=changes.preview(admin,proposal);assertEquals(1,((List<?>)preview.get("pending")).size());confirm(preview,UUID.randomUUID().toString(),null);assertEquals("pending",row(protectedId).get("status"));assertEquals("cancelled",row(imported).get("status"));
    }
    @Test void deletingExceptionCancelsOnlyLostFutureWindowAndRestoringDoesNotRevive() {
        LocalDate day=base.plusDays(1);opening.save(admin,room,new Requests.OpeningChange(null,day.toString(),List.of(new Requests.OpeningWindow("08:00","24:00")),roomVersion(),"初始扩展"));long id=Store.id(bookings.create(student,new Requests.Reservation(seat1,day.toString(),"22:00","23:00")),"id");
        var preview=changes.preview(admin,proposal("opening.delete_exception",room,new Requests.OpeningDelete(day.toString(),roomVersion(),"撤销日期扩展开放")));assertEquals(1,confirm(preview,UUID.randomUUID().toString(),null).get("cancelled_count"));assertEquals("cancelled",row(id).get("status"));
        var restore=changes.preview(admin,proposal("opening.save",room,new Requests.OpeningChange(null,day.toString(),List.of(new Requests.OpeningWindow("08:00","24:00")),roomVersion(),"恢复开放不复活预约")));assertEquals(0,confirm(restore,UUID.randomUUID().toString(),null).get("cancelled_count"));assertEquals("撤销日期扩展开放",row(id).get("termination_note"));assertEquals("cancelled",row(id).get("status"));
    }
    @Test void blockEditCancelAndOverlappingIndependentReasonsHaveCorrectScope() {
        LocalDate day=base.plusDays(1);opening.saveBlock(admin,room,null,new Requests.Unavailability(null,day.atTime(8,0).toString(),day.atTime(9,0).toString(),roomVersion(),"原全空间时段"));long block=Store.id(db.one("SELECT id FROM resource_unavailability"),"id");long first=Store.id(bookings.create(student,new Requests.Reservation(seat1,day.toString(),"10:00","11:00")),"id"),second=Store.id(bookings.create(staff,new Requests.Reservation(seat2,day.toString(),"10:00","11:00")),"id");
        var edit=changes.preview(admin,proposal("block.update",block,new Requests.Unavailability(seat1,day.atTime(10,30).toString(),day.atTime(10,45).toString(),roomVersion(),"编辑为单座临停")));assertTrue(edit.get("target_label").toString().contains("全空间"));assertTrue(edit.get("target_label").toString().contains("08:00"));assertEquals(1,confirm(edit,UUID.randomUUID().toString(),null).get("cancelled_count"));assertEquals("cancelled",row(first).get("status"));assertEquals("pending",row(second).get("status"));
        var create=changes.preview(admin,proposal("block.create",room,new Requests.Unavailability(null,day.atTime(10,30).toString(),day.atTime(10,45).toString(),roomVersion(),"另一个全空间临停原因")));confirm(create,UUID.randomUUID().toString(),null);long global=Store.id(db.one("SELECT id FROM resource_unavailability WHERE seat_id IS NULL"),"id");
        var cancel=changes.preview(admin,proposal("block.cancel",global,new Requests.ResourceDelete(roomVersion(),"只解除全空间这条原因")));assertEquals(0,confirm(cancel,UUID.randomUUID().toString(),null).get("cancelled_count"));assertEquals("cancelled",row(second).get("status"));assertFalse(opening.calendar(room).day(day,seat1).contains(day,day.atTime(10,30),day.atTime(10,45)));assertTrue(opening.calendar(room).day(day,seat2).contains(day,day.atTime(10,30),day.atTime(10,45)));
    }
    @Test void crossDayWholeRoomBlockIncludes24AndNextDayZeroBookings() {
        LocalDate first=base.plusDays(1),second=base.plusDays(2);for(LocalDate date:List.of(first,second)) opening.save(admin,room,new Requests.OpeningChange(null,date.toString(),List.of(new Requests.OpeningWindow("00:00","24:00")),roomVersion(),"全天模拟"));
        long a=Store.id(bookings.create(student,new Requests.Reservation(seat1,first.toString(),"23:30","24:00")),"id"),b=Store.id(bookings.create(staff,new Requests.Reservation(seat2,second.toString(),"00:00","00:30")),"id");var preview=changes.preview(admin,proposal("block.create",room,new Requests.Unavailability(null,first.atTime(23,45).toString(),second.atTime(0,15).toString(),roomVersion(),"跨日全空间临停")));assertEquals(2,confirm(preview,UUID.randomUUID().toString(),null).get("cancelled_count"));assertEquals("24:00:00",row(a).get("end_time"));assertEquals("cancelled",row(b).get("status"));assertEquals(0,db.count("SELECT count(*) FROM violations"));
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"pending","checked_in"})
    void deadlineOrScheduledEndMakesOldPreviewStaleWithoutSettlingAnything(String state) {
        long id=current(student,seat1);if(state.equals("checked_in")) sign(student,id,seat1);clock.set(base.atTime(9,state.equals("pending")?14:59,59));
        var preview=changes.preview(admin,proposal("room.update",room,roomBody(false,"边界变更")));assertEquals(1,((List<?>)preview.get(state.equals("pending")?"pending":"in_use")).size());clock.set(base.atTime(state.equals("pending")?9:10,state.equals("pending")?15:0));String before=snapshot();assertEquals(409,assertThrows(Api.Failure.class,()->confirm(preview,UUID.randomUUID().toString(),state.equals("pending")?null:simulatedCoordination())).status);assertEquals(before,snapshot());assertEquals(state,row(id).get("status"));assertEquals(0,db.count("SELECT count(*) FROM resource_change_operations"));
        bookings.mine(student);assertEquals(state.equals("pending")?"unused_ended":"completed",row(id).get("status"));assertFalse("admin_resource_change".equals(row(id).get("termination_reason")));
    }
    @Test void newAffectedReservationAndCheckinStatusChangesRequireFreshPreview() {
        var empty=changes.preview(admin,proposal("room.update",room,roomBody(false,"新增预约需重新预览")));long id=reservation(student,seat1);String before=snapshot();assertEquals(409,assertThrows(Api.Failure.class,()->confirm(empty,UUID.randomUUID().toString(),null)).status);assertEquals(before,snapshot());
        db.update("UPDATE reservations SET start_time='09:00',end_time='10:00' WHERE id=?",id);var pending=changes.preview(admin,proposal("room.update",room,roomBody(false,"签到变化需重新预览")));sign(student,id,seat1);String checked=snapshot();assertEquals(409,assertThrows(Api.Failure.class,()->confirm(pending,UUID.randomUUID().toString(),null)).status);assertEquals(checked,snapshot());assertEquals("checked_in",row(id).get("status"));
    }
    @Test void versionChangesAreStaleButElapsedSecondsDoNotInvalidateSameImpactSet() {
        long id=current(student,seat1);sign(student,id,seat1);at(9,10,0);var preview=changes.preview(admin,proposal("block.create",room,new Requests.Unavailability(seat1,base.atTime(9,30).toString(),base.atTime(9,45).toString(),roomVersion(),"未来维护")));
        clock.set(base.atTime(9,11,12,123456000));var same=changes.preview(admin,(Requests.ResourceChangeProposal)preview.get("normalized_proposal"));assertEquals(preview.get("impact_hash"),same.get("impact_hash"));confirm(preview,UUID.randomUUID().toString(),simulatedCoordination());assertEquals(base.atTime(9,11,12,123456000),LocalDateTime.parse(row(id).get("ended_at").toString()));
        var old=changes.preview(admin,proposal("room.update",room,roomBody(true,"旧版本")));resources.save(admin,room,roomBody(true,"另一管理员更新说明"));String before=snapshot();assertEquals(409,assertThrows(Api.Failure.class,()->confirm(old,UUID.randomUUID().toString(),null)).status);assertEquals(before,snapshot());
    }
    @Test void concurrentSameKeyOnlyExecutesOnceAndReplaysBeforeVersionChecks() throws Exception {
        long id=reservation(student,seat1);var preview=changes.preview(admin,proposal("room.update",room,roomBody(false,"并发幂等关闭")));String key=UUID.randomUUID().toString();var request=request(preview,key,null);var pool=Executors.newFixedThreadPool(6);var start=new CountDownLatch(1);
        try { List<Future<Map<String,Object>>> results=new ArrayList<>();for(int i=0;i<6;i++) results.add(pool.submit(()->{start.await();return changes.confirm(admin,request);}));start.countDown();int original=0;for(var result:results) if(Boolean.FALSE.equals(result.get(20,TimeUnit.SECONDS).get("replayed"))) original++;assertEquals(1,original); }
        finally { pool.shutdownNow(); }
        assertEquals("cancelled",row(id).get("status"));assertEquals(2,roomVersion());assertEquals(1,db.count("SELECT count(*) FROM resource_change_operations"));assertEquals(1,db.count("SELECT count(*) FROM notifications WHERE type='management_cancelled'"));assertEquals(1,db.count("SELECT count(*) FROM audit_events WHERE action='resource_change.confirm'"));assertEquals(1,db.count("SELECT count(*) FROM audit_events WHERE action='reservation.admin_cancel'"));String before=snapshot();
        assertEquals(true,changes.confirm(admin,request).get("replayed"));var altered=new LinkedHashMap<>(request.proposal().payload());altered.put("reason","同key换原因");assertEquals(409,assertThrows(Api.Failure.class,()->changes.confirm(admin,new Requests.ResourceChangeConfirm(new Requests.ResourceChangeProposal("room.update",room,altered),request.impactHash(),key,null))).status);
        assertEquals(409,assertThrows(Api.Failure.class,()->confirm(preview,key,simulatedCoordination())).status);long otherAdmin=db.insert("INSERT INTO users(username,password_hash,role) VALUES('another-admin','unused','admin')");assertEquals(409,assertThrows(Api.Failure.class,()->changes.confirm(otherAdmin,request)).status);assertEquals(before,snapshot());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"audit","notification","resource","operation"})
    void anyWriteFailureRollsBackCancellationUsageResourceAndPersistentOperation(String layer) throws Exception {
        long id=current(student,seat1);sign(student,id,seat1);reservation(staff,seat2);at(9,10,0);String token=token("admin");var preview=changes.preview(admin,proposal("room.update",room,roomBody(false,"故障资源写")));String key=UUID.randomUUID().toString();var request=request(preview,key,simulatedCoordination());String before=snapshot();
        String table=switch(layer) {case "audit"->"audit_events";case "notification"->"notifications";case "resource"->"rooms";case "operation"->"resource_change_operations";default->throw new AssertionError(layer);};String condition=switch(layer) {case "audit"->"action<>'reservation.admin_cancel'";case "notification"->"type<>'management_cancelled'";case "resource"->"resource_reason<>'故障资源写'";case "operation"->"operation_id<>CAST('"+key+"' AS UUID)";default->throw new AssertionError(layer);};
        db.update("ALTER TABLE "+table+" ADD CONSTRAINT s13_fail CHECK("+condition+") NOT VALID");
        try { http.perform(auth(post("/api/admin/resource-changes/confirm"),token).contentType("application/json").content(json.writeValueAsString(request))).andExpect(status().isInternalServerError());assertEquals(before,snapshot());assertEquals("checked_in",row(id).get("status"));assertNull(db.one("SELECT ended_at FROM usage_records WHERE reservation_id=?",id).get("ended_at"));assertEquals(0,db.count("SELECT count(*) FROM resource_change_operations")); }
        finally { db.update("ALTER TABLE "+table+" DROP CONSTRAINT s13_fail"); }
        assertEquals(false,changes.confirm(admin,request).get("replayed"));assertEquals("cancelled",row(id).get("status"));
    }
    @Test void previewPermissionsValidationAndOldDirectGuardsAreAllRealHttp() throws Exception {
        long id=reservation(student,seat1);String token=token("admin"),user=token("student"),staffToken=token("staff");var body=proposal("room.update",room,roomBody(false,"普通关闭"));
        http.perform(post("/api/admin/resource-changes/preview").contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isUnauthorized());for(String ordinary:List.of(user,staffToken)) http.perform(auth(post("/api/admin/resource-changes/preview"),ordinary).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isForbidden());
        http.perform(auth(post("/api/admin/resource-changes/preview"),token).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isOk()).andExpect(jsonPath("$.data.target_label").value("S13模拟空间"));String before=snapshot();
        var force=json.convertValue(roomBody(false,"旧直接入口仍保护"),new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>() {});force.put("force",true);http.perform(auth(put("/api/admin/rooms/"+room),token).contentType("application/json").content(json.writeValueAsString(force))).andExpect(status().isConflict());
        http.perform(auth(put("/api/admin/seats/"+seat1),token).contentType("application/json").content(json.writeValueAsString(seatBody(seat1,"01",1,"maintenance","旧直接座位保护")))).andExpect(status().isConflict());
        http.perform(auth(put("/api/admin/rooms/"+room+"/opening-windows"),token).contentType("application/json").content(json.writeValueAsString(new Requests.OpeningChange(null,base.toString(),List.of(),roomVersion(),"旧直接开放入口保护")))).andExpect(status().isConflict());assertEquals(before,snapshot());assertEquals("pending",row(id).get("status"));
        var unknown=json.convertValue(body,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>() {});unknown.put("force",true);http.perform(auth(post("/api/admin/resource-changes/preview"),token).contentType("application/json").content(json.writeValueAsString(unknown))).andExpect(status().isBadRequest());
        assertEquals(400,assertThrows(Api.Failure.class,()->changes.preview(admin,proposal("seat.update",seat1,seatBody(seat1,"01",3,"available","越界")))).status);assertEquals(409,assertThrows(Api.Failure.class,()->changes.preview(admin,proposal("seat.update",seat1,seatBody(seat1,"02",1,"available","重复编号")))).status);
        assertEquals(404,assertThrows(Api.Failure.class,()->changes.preview(admin,proposal("room.update",Long.MAX_VALUE,roomBody(true,"未知空间")))).status);assertEquals(0,db.count("SELECT count(*) FROM resource_change_operations"));
    }
    @Test void malformedInUseFactIsDiagnosedWithoutInventingZeroDurationOrWritingPreview() throws Exception {
        long id=current(student,seat1);sign(student,id,seat1);at(9,10,0);db.update("DELETE FROM usage_records WHERE reservation_id=?",id);var body=proposal("room.update",room,roomBody(false,"缺失使用事实"));String before=snapshot();assertThrows(IllegalStateException.class,()->changes.preview(admin,body));assertEquals(before,snapshot());
        transactions.executeWithoutResult(tx->{usage.start(id,base.atTime(9,0),"demo_seed");usage.finish(id,base.atTime(10,0),base.atTime(9,5),"demo_completed",base.atTime(9,5));});String ended=snapshot();assertThrows(IllegalStateException.class,()->changes.preview(admin,body));assertEquals(ended,snapshot());db.update("UPDATE reservations SET status='pending' WHERE id=?",id);String pending=snapshot();assertThrows(IllegalStateException.class,()->changes.preview(admin,body));assertEquals(pending,snapshot());
    }
    @Test void historicalGuardsStayPreciselyAssociatedWithRoomWaitlistAndPromotedSeat() {
        db.update("INSERT INTO waitlist(user_id,room_id,res_date,start_time,end_time,status) VALUES(?,?,?,'10:00','11:00','waiting')",student,room,java.sql.Date.valueOf(base.plusDays(1)));assertEquals(409,assertThrows(Api.Failure.class,()->resources.deleteRoom(admin,room,new Requests.ResourceDelete(roomVersion(),"候补历史禁删房间"))).status);
        resources.deleteSeat(admin,seat2,new Requests.ResourceDelete(1L,"无座位关联历史允许删除该空座"));assertEquals(0,db.count("SELECT count(*) FROM seats WHERE id=?",seat2));long id=db.insert("INSERT INTO reservations(user_id,seat_id,res_date,start_time,end_time,status,source) VALUES(?,?,?,'10:00','11:00','pending','waitlist')",student,seat1,java.sql.Date.valueOf(base.plusDays(1)));assertEquals(409,assertThrows(Api.Failure.class,()->resources.deleteSeat(admin,seat1,new Requests.ResourceDelete(1L,"递补预约关联保护座位"))).status);
        var preview=changes.preview(admin,proposal("seat.update",seat1,seatBody(seat1,"01",1,"maintenance","取消不移除历史")));confirm(preview,UUID.randomUUID().toString(),null);assertEquals("cancelled",row(id).get("status"));assertEquals(409,assertThrows(Api.Failure.class,()->resources.deleteSeat(admin,seat1,new Requests.ResourceDelete(2L,"取消后仍不可删除"))).status);
    }

}
