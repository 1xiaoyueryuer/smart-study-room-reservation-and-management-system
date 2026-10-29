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
class ComplaintIntegrationTest {
    @TestConfiguration static class TimeConfig { @Bean @Primary MinuteBookingIntegrationTest.TestClock testClock() { var c=new MinuteBookingIntegrationTest.TestClock();c.set(LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1).atTime(9,0));return c; } }
    @Autowired Store db;
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
    long admin,student,staff,other,room,seat1,seat2;
    LocalDate base;
    String remote;
    @BeforeEach void fixture() {
        assertEquals("studyroom_java_test",db.one("SELECT current_database() AS name").get("name"));
        for(String table:List.of("reminders","complaint_attachments","complaint_events","complaints","resource_change_operations","usage_records","seat_codes","violations","resource_unavailability","opening_windows","opening_schedules","audit_events","credit_logs","notifications","waitlist","reservations","seats","rooms","users")) db.update("DELETE FROM "+table);
        base=LocalDate.now(BookingPolicy.CAMPUS_ZONE).minusDays(1);clock.set(base.atTime(9,0));remote="s14-test-"+UUID.randomUUID();
        String hash=new BCryptPasswordEncoder().encode("S02-test-only-password");
        admin=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s14_admin',?,'admin','S09-admin')",hash);
        student=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s14_student',?,'student','S09-student')",hash);
        staff=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s14_staff',?,'staff','S09-staff')",hash);
        other=db.insert("INSERT INTO users(username,password_hash,role,campus_id) VALUES('s14_other',?,'student','S14-other')",hash);
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
    static byte[] image(String format,int width,int height) throws Exception { var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);var output=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,format,output));image.flush();return output.toByteArray(); }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"jpeg","png"})
    void incompleteImageContainerCannotBeAcceptedAsFullyDecoded(String format) throws Exception {
        byte[] complete=image(format,16,16),cut=Arrays.copyOf(complete,complete.length-(format.equals("jpeg")?2:12));String token=token("student");
        http.perform(multipart("/api/complaint-attachments").file(new MockMultipartFile("file","incomplete."+format,"image/"+format,cut)).header("Authorization","Bearer "+token)).andExpect(status().isBadRequest());
        assertEquals(0,db.count("SELECT count(*) FROM complaint_attachments"));
    }
    static byte[] chunk(String type,byte[] data) throws Exception { var output=new ByteArrayOutputStream();var fields=new java.io.DataOutputStream(output);fields.writeInt(data.length);byte[] tag=type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);fields.write(tag);fields.write(data);var crc=new java.util.zip.CRC32();crc.update(tag);crc.update(data);fields.writeInt((int)crc.getValue());return output.toByteArray(); }
    static byte[] insertBeforeEnd(byte[] png,byte[] added) throws Exception { var out=new ByteArrayOutputStream();out.write(png,0,png.length-12);out.write(added);out.write(png,png.length-12,12);return out.toByteArray(); }
    static byte[] paddedPng(int total) throws Exception { byte[] png=image("png",2,2),data=new byte[total-png.length-12];Arrays.fill(data,(byte)'a');System.arraycopy("fixture\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,data,0,8);return insertBeforeEnd(png,chunk("tEXt",data)); }
    static byte[] jpegApp(byte[] jpeg,byte[] payload) throws Exception { var out=new ByteArrayOutputStream();var data=new java.io.DataOutputStream(out);data.write(jpeg,0,2);data.writeShort(0xffe1);data.writeShort(payload.length+2);data.write(payload);data.write(jpeg,2,jpeg.length-2);return out.toByteArray(); }
    Map<String,Object> upload(long actor,byte[] bytes,String name,String mime) { return attachments.upload(actor,new MockMultipartFile("file",name,mime,bytes)); }
    String fileId(Map<String,Object> metadata) { return metadata.get("id").toString(); }
    Requests.Complaint complaint(String type,Long reservation,Long room,String description,List<String> files,String key) { return new Requests.Complaint(type,reservation,room,description,files,key); }
    Requests.Complaint service(List<String> files,String key) { return complaint("service",null,null,"工程模拟的一般服务投诉",files,key); }
    String facts() { var state=new LinkedHashMap<String,Object>();for(String table:List.of("complaints","complaint_events","reservations","notifications","violations","credit_logs","audit_events")) state.put(table,db.list("SELECT to_jsonb(t)::text AS raw FROM "+table+" t ORDER BY to_jsonb(t)::text"));state.put("attachments",db.list("SELECT id,owner_id,complaint_id,position,bound_at,sha256,created_at,expires_at FROM complaint_attachments ORDER BY id"));return state.toString(); }
    @Test void historicalOccupationCreatesSubmittedContextWithoutRevivingOrExtendingAndKnownPositionIsReal() throws Exception {
        db.update("UPDATE rooms SET building='S14模拟楼',floor='B1',location='北廊' WHERE id=?",room);long id=reservation(student,seat1);String token=token("student");http.perform(auth(get("/api/reservations/"+id),token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.building").value("S14模拟楼")).andExpect(jsonPath("$.data.floor").value("B1")).andExpect(jsonPath("$.data.location").value("北廊"));bookings.action(student,id,"cancel");String old=row(id).toString();
        var body=complaint("occupation",id,null,"  工程模拟：历史被占情况留证，并非已核实现场  ",List.of(),UUID.randomUUID().toString());var response=http.perform(auth(post("/api/complaints"),token).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("submitted")).andExpect(jsonPath("$.data.replayed").value(false)).andExpect(jsonPath("$.data.context_snapshot.status_at_submission").value("cancelled")).andExpect(jsonPath("$.data.events[0].type").value("submitted")).andReturn().getResponse().getContentAsString();long complaintId=json.readTree(response).path("data").path("id").asLong();assertEquals(old,row(id).toString());assertEquals(0,db.count("SELECT count(*) FROM notifications WHERE type LIKE 'complaint%'"));assertEquals(0,db.count("SELECT count(*) FROM violations"));
        resources.save(admin,room,new Requests.Room("S14已更名模拟空间","西廊",null,null,1,2,null,"另一模拟楼","2",true,Store.id(resources.room(room),"version"),"模拟资源变更后投诉仍保原上下文"));
        http.perform(auth(get("/api/complaints/"+complaintId),token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.context_snapshot.room_name").value("S14模拟空间")).andExpect(jsonPath("$.data.context_snapshot.location").value("北廊")).andExpect(jsonPath("$.data.attachment_count").value(0));http.perform(auth(get("/api/complaints/mine"),token)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(complaintId)).andExpect(jsonPath("$.data[0].attachments").doesNotExist()).andExpect(jsonPath("$.data[0].events").doesNotExist());
    }
    @Test void creationUsesDaoSnapshotAndDoesNotImplicitlySettleExpiredReservation() {
        long id=db.insert("INSERT INTO reservations(user_id,seat_id,res_date,start_time,end_time,status) VALUES(?,?,?,'08:00','10:00','pending')",student,seat1,java.sql.Date.valueOf(base));at(9,0,0);String before=row(id).toString();var result=complaints.create(student,complaint("occupation",id,room,"工程模拟超时后留证，不要求自动延期或纠错",List.of(),UUID.randomUUID().toString()));assertEquals("submitted",result.get("status"));assertEquals(before,row(id).toString());assertEquals(0,db.count("SELECT count(*) FROM violations"));assertEquals(0,db.count("SELECT count(*) FROM notifications"));
    }
    @Test void serviceCanBeUnrelatedRoomOnlyOrOwnReservationAndRejectsWrongOwnership() {
        var free=complaints.create(student,service(List.of(),UUID.randomUUID().toString()));assertNull(free.get("context_snapshot"));var linked=complaints.create(student,complaint("service",null,room,"工程模拟空间服务问题",List.of(),UUID.randomUUID().toString()));@SuppressWarnings("unchecked") var context=(Map<String,Object>)linked.get("context_snapshot");assertEquals(Set.of("room_id","room_name","building","floor","location"),context.keySet());
        long own=reservation(student,seat1),foreign=reservation(other,seat2);var associated=complaints.create(student,complaint("service",own,null,"服务问题可关联本人预约",List.of(),UUID.randomUUID().toString()));assertEquals(room,Store.id(associated,"room_id"));
        assertEquals(403,assertThrows(Api.Failure.class,()->complaints.create(student,complaint("occupation",foreign,null,"不能关联他人",List.of(),UUID.randomUUID().toString()))).status);assertEquals(400,assertThrows(Api.Failure.class,()->complaints.create(student,complaint("occupation",null,null,"缺预约",List.of(),UUID.randomUUID().toString()))).status);assertEquals(400,assertThrows(Api.Failure.class,()->complaints.create(student,complaint("service",own,Long.MAX_VALUE,"不匹配空间",List.of(),UUID.randomUUID().toString()))).status);assertEquals(404,assertThrows(Api.Failure.class,()->complaints.create(student,complaint("service",null,Long.MAX_VALUE,"不存在空间",List.of(),UUID.randomUUID().toString()))).status);
    }
    @Test void validPngJpegAndOptionalContainersPreserveOriginalBytesAndSafeGeneratedName() throws Exception {
        byte[] png=image("png",8,8);var meta=upload(student,png,"../../untrusted.html","text/html");assertEquals("image/png",meta.get("content_type"));assertTrue(meta.get("display_name").toString().matches("material-[0-9a-f-]{36}[.]png"));assertFalse(meta.containsKey("file_bytes"));assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(png)),meta.get("sha256"));assertArrayEquals(png,attachments.content(student,fileId(meta)).bytes());
        byte[] extra=insertBeforeEnd(png,chunk("vpAg",new byte[]{1,2,3,4}));assertArrayEquals(extra,attachments.content(student,fileId(upload(student,extra,"optional.png","application/octet-stream"))).bytes());
        var out=new ByteArrayOutputStream();out.write(png,0,8);for(int position=8;position<png.length;) { int length=java.nio.ByteBuffer.wrap(png,position,4).getInt();String type=new String(png,position+4,4,java.nio.charset.StandardCharsets.US_ASCII);if(type.equals("IDAT")) { byte[] data=Arrays.copyOfRange(png,position+8,position+8+length+8);Arrays.fill(data,length,data.length,(byte)0);out.write(chunk(type,data)); } else out.write(png,position,length+12);position+=length+12; }byte[] legalUnused=out.toByteArray();assertArrayEquals(legalUnused,attachments.content(student,fileId(upload(student,legalUnused,"unused-idat.png","image/png"))).bytes());
        byte[] exif=new byte[]{69,120,105,102,0,0,73,73,42,0,8,0,0,0,0,0,0,0,0,0};byte[] jpeg=jpegApp(image("jpeg",8,8),exif);var j=upload(student,jpeg,"image.png","image/png");assertEquals("image/jpeg",j.get("content_type"));assertArrayEquals(jpeg,attachments.content(student,fileId(j)).bytes());
    }
    @Test void crcCorruptionMalformedSignatureAndEmbeddedThumbnailCannotFakeMainJpegEnd() throws Exception {
        byte[] png=image("png",4,4),badCrc=png.clone();badCrc[29]^=1;assertEquals(400,assertThrows(Api.Failure.class,()->upload(student,badCrc,"crc.png","image/png")).status);assertEquals(400,assertThrows(Api.Failure.class,()->upload(student,"<script>fake</script>".getBytes(),"fake.png","image/png")).status);assertEquals(400,assertThrows(Api.Failure.class,()->upload(student,new byte[]{(byte)137,80,78,71,13,10,26,10},"fake.png","image/png")).status);
        byte[] jpeg=jpegApp(image("jpeg",4,4),new byte[]{69,120,105,102,0,0,(byte)255,(byte)217});byte[] incomplete=Arrays.copyOf(jpeg,jpeg.length-2);assertEquals(400,assertThrows(Api.Failure.class,()->upload(student,incomplete,"thumbnail-end.jpg","image/jpeg")).status);assertEquals(0,db.count("SELECT count(*) FROM complaint_attachments"));
    }
    @Test void imageDimensionAndByteLimitsAreExactAndCannotAllocateOversizedHeader() throws Exception {
        upload(student,image("png",10000,1),"wide.png","image/png");upload(student,image("png",10000,2000),"20mp.png","image/png");
        for(int[] dimensions:List.of(new int[]{10001,1},new int[]{5000,4001})) { byte[] png=image("png",2,2);java.nio.ByteBuffer.wrap(png,16,8).putInt(dimensions[0]).putInt(dimensions[1]);var crc=new java.util.zip.CRC32();crc.update(png,12,17);java.nio.ByteBuffer.wrap(png,29,4).putInt((int)crc.getValue());assertEquals(400,assertThrows(Api.Failure.class,()->upload(student,png,"too-large.png","image/png")).status); }
        byte[] maximum=paddedPng(AttachmentService.MAX_BYTES);assertEquals(AttachmentService.MAX_BYTES,upload(student,maximum,"5m.png","image/png").get("size_bytes"));assertEquals(400,assertThrows(Api.Failure.class,()->upload(student,Arrays.copyOf(maximum,maximum.length+1),"over.png","image/png")).status);
    }

    @Test void materialsAreOwnerOnlyWhileTemporaryAndAdminCanReadOnlyAfterBinding() throws Exception {
        byte[] bytes=image("png",10,10);var uploaded=upload(student,bytes,"photo.jpg","image/jpeg");String id=fileId(uploaded),owner=token("student"),foreign=token("other"),adminToken=token("admin");String path="/api/complaint-attachments/"+id+"/content";
        http.perform(get(path)).andExpect(status().isUnauthorized());http.perform(auth(get(path),foreign)).andExpect(status().isForbidden());http.perform(auth(get(path),adminToken)).andExpect(status().isForbidden());
        http.perform(auth(get(path),owner)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Content-Type-Options","nosniff")).andExpect(content().contentType("image/png")).andExpect(content().bytes(bytes));
        var complaint=complaints.create(student,service(List.of(id),UUID.randomUUID().toString()));@SuppressWarnings("unchecked") var metadata=(Map<String,Object>)((List<?>)complaint.get("attachments")).get(0);assertNull(metadata.get("expires_at"));assertFalse(metadata.containsKey("owner_id"));assertFalse(metadata.containsKey("file_bytes"));assertFalse(metadata.containsKey("complaint_id"));
        http.perform(auth(get(path),adminToken)).andExpect(status().isOk()).andExpect(content().bytes(bytes));http.perform(auth(get(path),foreign)).andExpect(status().isForbidden());http.perform(auth(get("/api/complaints/"+complaint.get("id")),adminToken)).andExpect(status().isForbidden());
    }
    @Test void descriptionAndMaterialCountOwnershipExpiryAndBindingAreStrict() throws Exception {
        for(String description:List.of("","  ","x".repeat(2001))) assertEquals(400,assertThrows(Api.Failure.class,()->complaints.create(student,complaint("service",null,null,description,List.of(),UUID.randomUUID().toString()))).status);
        assertEquals(2000,complaints.create(student,complaint("service",null,null,"x".repeat(2000),List.of(),UUID.randomUUID().toString())).get("description").toString().length());byte[] bytes=image("png",3,3);String own=fileId(upload(student,bytes,"a.png","image/png")),foreign=fileId(upload(other,bytes,"b.png","image/png"));
        assertEquals(400,assertThrows(Api.Failure.class,()->complaints.create(student,service(List.of(own,own.toUpperCase(Locale.ROOT)),UUID.randomUUID().toString()))).status);assertEquals(400,assertThrows(Api.Failure.class,()->complaints.create(student,service(List.of(own,foreign,UUID.randomUUID().toString(),UUID.randomUUID().toString()),UUID.randomUUID().toString()))).status);
        String before=facts();assertEquals(403,assertThrows(Api.Failure.class,()->complaints.create(student,service(List.of(foreign),UUID.randomUUID().toString()))).status);assertEquals(before,facts());assertEquals(404,assertThrows(Api.Failure.class,()->complaints.create(student,service(List.of(UUID.randomUUID().toString()),UUID.randomUUID().toString()))).status);
        complaints.create(student,service(List.of(own),UUID.randomUUID().toString()));assertEquals(409,assertThrows(Api.Failure.class,()->complaints.create(student,service(List.of(own),UUID.randomUUID().toString()))).status);clock.set(base.plusDays(1).atTime(9,0));assertEquals(409,assertThrows(Api.Failure.class,()->complaints.create(other,service(List.of(foreign),UUID.randomUUID().toString()))).status);
    }
    @Test void threeMaterialsBindInSubmittedOrderAndOnlySubmittedEventExists() throws Exception {
        var ids=new ArrayList<String>();for(int i=0;i<3;i++) ids.add(fileId(upload(student,image("png",i+2,2),"photo.png","image/png")));Collections.reverse(ids);var result=complaints.create(student,service(ids,UUID.randomUUID().toString()));assertEquals("submitted",result.get("status"));assertEquals(3L,result.get("attachment_count"));
        @SuppressWarnings("unchecked") var materials=(List<Map<String,Object>>)result.get("attachments");assertEquals(ids,materials.stream().map(material->material.get("id").toString()).toList());assertEquals(1,((List<?>)result.get("events")).size());assertEquals(1,db.count("SELECT count(*) FROM complaint_events WHERE event_type='submitted'"));assertEquals(0,db.count("SELECT count(*) FROM notifications"));assertEquals(0,db.count("SELECT count(*) FROM violations"));
    }
    @Test void concurrentSameOperationReplaysBeforeBoundOrExpiredMaterialValidation() throws Exception {
        String id=fileId(upload(student,image("png",5,5),"proof.png","image/png"));String key=UUID.randomUUID().toString();var request=service(List.of(id),key);var pool=Executors.newFixedThreadPool(6);var gate=new CountDownLatch(1);
        try { List<Future<Map<String,Object>>> futures=new ArrayList<>();for(int i=0;i<6;i++) futures.add(pool.submit(()->{gate.await();return complaints.create(student,request);}));gate.countDown();int created=0;for(var future:futures) if(Boolean.FALSE.equals(future.get(20,TimeUnit.SECONDS).get("replayed"))) created++;assertEquals(1,created); }
        finally { pool.shutdownNow(); }
        assertEquals(1,db.count("SELECT count(*) FROM complaints"));assertEquals(1,db.count("SELECT count(*) FROM complaint_events"));assertEquals(1,db.count("SELECT count(*) FROM audit_events WHERE action='complaint.submit'"));clock.set(base.plusDays(2).atTime(9,0));String before=facts();assertEquals(true,complaints.create(student,request).get("replayed"));assertEquals(before,facts());
        assertEquals(409,assertThrows(Api.Failure.class,()->complaints.create(student,complaint("service",null,null,"同key换内容",List.of(id),key))).status);assertEquals(409,assertThrows(Api.Failure.class,()->complaints.create(other,request)).status);assertEquals(before,facts());
    }
    @Test void competingOperationsCannotBindSameUploadedMaterialTwice() throws Exception {
        String file=fileId(upload(student,image("png",4,4),"one.png","image/png"));var pool=Executors.newFixedThreadPool(4);var gate=new CountDownLatch(1);
        try { List<Future<Integer>> futures=new ArrayList<>();for(int i=0;i<4;i++) futures.add(pool.submit(()->{gate.await();try { complaints.create(student,service(List.of(file),UUID.randomUUID().toString()));return 200; } catch(Api.Failure e) { return e.status; }}));gate.countDown();var codes=new ArrayList<Integer>();for(var future:futures) codes.add(future.get(20,TimeUnit.SECONDS));Collections.sort(codes);assertEquals(List.of(200,409,409,409),codes); }
        finally { pool.shutdownNow(); }
        assertEquals(1,db.count("SELECT count(*) FROM complaints"));assertEquals(1,db.count("SELECT count(*) FROM complaint_events"));assertEquals(1,db.count("SELECT count(*) FROM complaint_attachments WHERE complaint_id IS NOT NULL"));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"event","binding","audit"})
    void anySubmissionFailureRollsBackComplaintEventAndAllMaterialBindings(String layer) throws Exception {
        String first=fileId(upload(student,image("png",3,3),"one.png","image/png")),second=fileId(upload(student,image("png",4,4),"two.png","image/png"));String token=token("student");var request=service(List.of(first,second),UUID.randomUUID().toString());String before=facts();String table=layer.equals("event")?"complaint_events":layer.equals("binding")?"complaint_attachments":"audit_events";String condition=layer.equals("event")?"event_type<>'submitted'":layer.equals("binding")?"position IS NULL OR position<>1":"action<>'complaint.submit'";db.update("ALTER TABLE "+table+" ADD CONSTRAINT s14_fail CHECK("+condition+") NOT VALID");
        try { http.perform(auth(post("/api/complaints"),token).contentType("application/json").content(json.writeValueAsString(request))).andExpect(status().isInternalServerError());assertEquals(before,facts());assertEquals(0,db.count("SELECT count(*) FROM complaints"));assertEquals(0,db.count("SELECT count(*) FROM complaint_attachments WHERE complaint_id IS NOT NULL")); }
        finally { db.update("ALTER TABLE "+table+" DROP CONSTRAINT s14_fail"); }
        assertEquals(false,complaints.create(student,request).get("replayed"));
    }
    @Test void uploadAndCleanupAuditFailuresDoNotLeaveUntrackedBlobOrLoseTemporaryMaterial() throws Exception {
        String token=token("student");db.update("ALTER TABLE audit_events ADD CONSTRAINT s14_fail CHECK(action<>'complaint_attachment.upload') NOT VALID");
        try { http.perform(multipart("/api/complaint-attachments").file(new MockMultipartFile("file","valid.png","image/png",image("png",3,3))).header("Authorization","Bearer "+token)).andExpect(status().isInternalServerError());assertEquals(0,db.count("SELECT count(*) FROM complaint_attachments")); }
        finally { db.update("ALTER TABLE audit_events DROP CONSTRAINT s14_fail"); }
        String file=fileId(upload(student,image("png",4,4),"temp.png","image/png"));clock.set(base.plusDays(1).atTime(9,0));db.update("ALTER TABLE audit_events ADD CONSTRAINT s14_fail CHECK(action<>'complaint_attachment.cleanup') NOT VALID");String before=facts();
        try { assertThrows(org.springframework.dao.DataIntegrityViolationException.class,attachments::cleanupExpired);assertEquals(before,facts());assertEquals(1,db.count("SELECT count(*) FROM complaint_attachments WHERE id=CAST(? AS UUID)",file)); }
        finally { db.update("ALTER TABLE audit_events DROP CONSTRAINT s14_fail"); }
    }
    @Test void exactly24HoursDeniesUnboundReadAndCleanupNeverDeletesBoundHistory() throws Exception {
        byte[] bytes=image("png",6,6);String unbound=fileId(upload(student,bytes,"temp.png","image/png")),bound=fileId(upload(student,bytes,"bound.png","image/png"));complaints.create(student,service(List.of(bound),UUID.randomUUID().toString()));clock.set(base.plusDays(1).atTime(8,59,59,999999000));assertEquals(0,attachments.cleanupExpired());assertArrayEquals(bytes,attachments.content(student,unbound).bytes());clock.set(base.plusDays(1).atTime(9,0));assertEquals(410,assertThrows(Api.Failure.class,()->attachments.content(student,unbound)).status);
        new ComplaintAttachmentCleanupScheduler(attachments).cleanup();assertEquals(0,db.count("SELECT count(*) FROM complaint_attachments WHERE id=CAST(? AS UUID)",unbound));assertArrayEquals(bytes,attachments.content(admin,bound).bytes());clock.set(base.plusDays(10).atTime(9,0));assertEquals(0,attachments.cleanupExpired());assertEquals(1,db.count("SELECT count(*) FROM complaint_attachments"));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void cleanupAndSubmissionAtExpiryBoundaryHaveAtomicDeterministicOutcomes(boolean expired) throws Exception {
        String file=fileId(upload(student,image("png",3,3),"race.png","image/png"));clock.set(expired?base.plusDays(1).atTime(9,0):base.plusDays(1).atTime(8,59,59,999999000));var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try { var create=pool.submit(()->{gate.await();try { complaints.create(student,service(List.of(file),UUID.randomUUID().toString()));return 200; } catch(Api.Failure e) { return e.status; }});var clean=pool.submit(()->{gate.await();return attachments.cleanupExpired();});gate.countDown();int outcome=create.get(20,TimeUnit.SECONDS);clean.get(20,TimeUnit.SECONDS);assertEquals(expired?0:1,db.count("SELECT count(*) FROM complaints"));if(expired) assertTrue(outcome==404||outcome==409);else assertEquals(200,outcome);assertEquals(expired?0:1,db.count("SELECT count(*) FROM complaint_attachments")); }
        finally { pool.shutdownNow(); }
    }
    @Test void httpAuthorizationWhitelistAndSingleUploadPartRulesAreEnforced() throws Exception {
        String owner=token("student"),foreign=token("other"),adminToken=token("admin");var request=service(List.of(),UUID.randomUUID().toString());http.perform(post("/api/complaints").contentType("application/json").content(json.writeValueAsString(request))).andExpect(status().isUnauthorized());http.perform(auth(post("/api/complaints"),adminToken).contentType("application/json").content(json.writeValueAsString(request))).andExpect(status().isForbidden());
        var unknown=json.convertValue(request,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>() {});for(String key:List.of("owner_id","occupier_id","status","path")) { unknown.put(key,"untrusted");http.perform(auth(post("/api/complaints"),owner).contentType("application/json").content(json.writeValueAsString(unknown))).andExpect(status().isBadRequest());unknown.remove(key); }
        var own=complaints.create(student,request);long id=Store.id(own,"id");complaints.create(other,complaint("service",null,null,"他人私密说明",List.of(),UUID.randomUUID().toString()));http.perform(auth(get("/api/complaints/"+id),foreign)).andExpect(status().isForbidden());http.perform(auth(get("/api/complaints/9223372036854775807"),owner)).andExpect(status().isNotFound());var mine=http.perform(auth(get("/api/complaints/mine").param("user_id",Long.toString(other)),owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1)).andReturn().getResponse().getContentAsString();assertFalse(mine.contains("他人私密说明"));
        byte[] png=image("png",2,2);http.perform(multipart("/api/complaint-attachments").file(new MockMultipartFile("file","a.png","image/png",png)).header("Authorization","Bearer "+adminToken)).andExpect(status().isForbidden());http.perform(multipart("/api/complaint-attachments").header("Authorization","Bearer "+owner)).andExpect(status().isBadRequest());http.perform(multipart("/api/complaint-attachments").file(new MockMultipartFile("file","a.png","image/png",png)).file(new MockMultipartFile("file","b.png","image/png",png)).header("Authorization","Bearer "+owner)).andExpect(status().isBadRequest());for(String key:List.of("path","owner_id")) http.perform(multipart("/api/complaint-attachments").file(new MockMultipartFile("file","a.png","image/png",png)).param(key,"ignored").header("Authorization","Bearer "+owner)).andExpect(status().isBadRequest());
    }
    @Test void associatedComplaintProtectsRoomHistoryEvenWithoutAnyReservation() {
        complaints.create(student,complaint("service",null,room,"工程模拟无预约的空间服务投诉",List.of(),UUID.randomUUID().toString()));var failure=assertThrows(Api.Failure.class,()->resources.deleteRoom(admin,room,new Requests.ResourceDelete(Store.id(resources.room(room),"version"),"投诉关联历史不能删除")));assertEquals(409,failure.status);assertTrue(failure.getMessage().contains("投诉"));assertEquals(2,db.count("SELECT count(*) FROM seats WHERE room_id=?",room));
    }

}
