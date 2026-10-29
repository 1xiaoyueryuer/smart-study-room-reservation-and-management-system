package cn.studyroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import static org.junit.jupiter.api.Assertions.*;

/** 真嵌入式Tomcat HTTP；MockMultipart不经过servlet传输限制，不能替代这里。 */
@EnabledIfEnvironmentVariable(named="STUDYROOM_INTEGRATION",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=${STUDYROOM_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5433/studyroom_java_test}","studyroom.seed=false","studyroom.scheduler-enabled=false"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ComplaintTransportIntegrationTest {
    @LocalServerPort int port;
    @Autowired Store db;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    @Autowired javax.servlet.MultipartConfigElement multipart;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    @BeforeEach void fixture() {
        assertEquals("studyroom_java_test",db.one("SELECT current_database() AS name").get("name"));for(String table:List.of("notifications","violations","complaint_target_events","complaint_targets","reminders","complaint_attachments","complaint_events","complaints","resource_change_operations","usage_records","seat_codes","resource_unavailability","opening_windows","opening_schedules","audit_events","credit_logs","waitlist","reservations","seats","rooms","users")) db.update("DELETE FROM "+table);String hash=new BCryptPasswordEncoder().encode("S02-test-only-password");db.update("INSERT INTO users(username,password_hash,role) VALUES('transport_student',?,'student'),('transport_admin',?,'admin')",hash,hash);redis.delete("studyroom-java:rate:login:127.0.0.1");
    }
    @AfterEach void cleanupRate() { redis.delete("studyroom-java:rate:login:127.0.0.1"); }
    String login(String role) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/auth/login")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username","transport_"+role,"password","S02-test-only-password")))).build();var response=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));assertEquals(200,response.statusCode());return json.readTree(response.body()).path("data").path("token").asText();
    }
    HttpResponse<String> upload(String path,String token,byte[] bytes,String mime) throws Exception {
        String boundary="s14-"+UUID.randomUUID();var body=new ByteArrayOutputStream();body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"transport-test\"\r\nContent-Type: "+mime+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));body.write(bytes);body.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(30)).header("Authorization","Bearer "+token).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();return client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    @Test void actualTransportAllowsFiveMiBImageAndPreservesSeparateCsvOneMiBRule() throws Exception {
        assertEquals(6L*1024*1024,multipart.getMaxFileSize());assertEquals(7L*1024*1024,multipart.getMaxRequestSize());String user=login("student"),admin=login("admin");byte[] maximum=ComplaintIntegrationTest.paddedPng(AttachmentService.MAX_BYTES);
        var valid=upload("/api/complaint-attachments",user,maximum,"image/png");assertEquals(200,valid.statusCode());assertEquals(AttachmentService.MAX_BYTES,json.readTree(valid.body()).path("data").path("size_bytes").asInt());
        var over=upload("/api/complaint-attachments",user,Arrays.copyOf(maximum,maximum.length+1),"image/png");assertEquals(400,over.statusCode());assertTrue(over.body().contains("5MiB"));
        byte[] csv=new byte[1024*1024+1];Arrays.fill(csv,(byte)'a');var importFile=upload("/api/admin/users/import/preview",admin,csv,"text/csv");assertEquals(400,importFile.statusCode());assertTrue(importFile.body().contains("1MiB"));assertEquals(1,db.count("SELECT count(*) FROM complaint_attachments"));
    }
    @Test void actualServletFileLimitReturnsChinese413InsteadOfCatchAll500() throws Exception {
        byte[] excess=new byte[6*1024*1024+1];var response=upload("/api/complaint-attachments",login("student"),excess,"image/png");assertEquals(413,response.statusCode());assertTrue(response.body().contains("上传请求过大"));assertEquals(0,db.count("SELECT count(*) FROM complaint_attachments"));
    }
}
