package cn.studyroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named="STUDYROOM_INTEGRATION",matches="true")
@SpringBootTest(properties={"spring.datasource.url=${STUDYROOM_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5433/studyroom_java_test}","studyroom.seed=false","studyroom.scheduler-enabled=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserImportIntegrationTest {
    @Autowired MockMvc http;
    @Autowired Store db;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    Map<String,Long> ids;
    String remote;
    static final String PASSWORD="S02-test-only-password";
    static final String HEADER="campus_id,real_name,role,phone,email\r\n";
    static final String VALID=HEADER+"202600011,导入模拟学生,student,,student@example.edu\r\n202630012,导入模拟教职工,staff,+86 123,staff@example.edu\r\n";
    @BeforeEach void fixture() {
        assertEquals("studyroom_java_test",db.one("SELECT current_database() AS name").get("name"));
        TestDatabaseCleanup.clear(db);
        ids=new LinkedHashMap<>();remote="s04-test-"+UUID.randomUUID();
        String hash=new BCryptPasswordEncoder().encode(PASSWORD);
        for(String role:List.of("student","staff","admin")) {
            String identity=switch(role){case "student"->"202600801";case "staff"->"202630802";default->"202630803";};
            ids.put(role,db.insert("INSERT INTO users(username,password_hash,real_name,campus_id,role) VALUES(?,?,?,?,?)",identity,hash,"S04模拟"+role,identity,role));
        }
    }
    @AfterEach void clearOwnLoginRate() { if(remote!=null) redis.delete("studyroom-java:rate:login:"+remote); }
    @AfterAll void leaveExplicitFixtures() { fixture(); }
    String token(String username,String password) throws Exception {
        var response=http.perform(post("/api/auth/login").with(r->{r.setRemoteAddr(remote);return r;}).contentType("application/json").content(json.writeValueAsString(Map.of("username",username,"password",password))))
            .andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).path("data").path("token").asText();
    }
    String token(String role) throws Exception { return token((String)db.one("SELECT username FROM users WHERE id=?",ids.get(role)).get("username"),PASSWORD); }
    MockMultipartFile file(String csv) { return new MockMultipartFile("file","campus.csv","text/csv",csv.getBytes(StandardCharsets.UTF_8)); }
    String preview(String admin,String csv) throws Exception {
        var response=http.perform(multipart("/api/admin/users/import/preview").file(file(csv)).header("Authorization","Bearer "+admin))
            .andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).path("data").path("file_sha256").asText();
    }
    org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder confirmation(String csv,String hash,String password) {
        return (org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder)multipart("/api/admin/users/import/confirm").file(file(csv)).param("file_sha256",hash).param("initial_password",password).param("reason","测试导入校园用户");
    }
    @Test void templateAndImportActuallyCreateLoginCapableUsersWithAudit() throws Exception {
        String admin=token("admin");
        var template=http.perform(get("/api/admin/users/import/template").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andReturn();
        assertEquals("\uFEFF"+HEADER,new String(template.getResponse().getContentAsByteArray(),StandardCharsets.UTF_8));
        String hash=preview(admin,VALID);
        assertEquals(3,db.count("SELECT count(*) FROM users"));assertEquals(0,db.count("SELECT count(*) FROM audit_events"));
        var result=http.perform(confirmation(VALID,hash,"Import-test-only-123").header("Authorization","Bearer "+admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.imported_count").value(2)).andExpect(jsonPath("$.data.users[0].campus_id").value("202600011")).andExpect(jsonPath("$.data.users[0].username").value("202600011")).andReturn();
        token("202600011","Import-test-only-123");token("202630012","Import-test-only-123");
        assertFalse(result.getResponse().getContentAsString().contains("Import-test-only-123"));
        assertEquals(3,db.count("SELECT count(*) FROM audit_events"));
        assertEquals(1,db.count("SELECT count(*) FROM audit_events WHERE action='user.import' AND after_values->>'imported_count'='2'"));
        for(var record:db.list("SELECT before_values::text AS before_data,after_values::text AS after_data FROM audit_events")) {
            assertFalse(record.toString().contains("Import-test-only-123"));assertFalse(record.toString().contains("password_hash"));assertFalse(record.toString().contains("$2a$"));
        }
    }
    @Test void rowErrorsLocateDuplicatesIllegalRolesAndExistingIdentityWithoutWriting() throws Exception {
        String csv=HEADER+"202600022,甲,student,,\r\n202600022,乙,student,,\r\n202630003,丙,student,,not-an-email\r\n";
        String admin=token("admin");
        var response=http.perform(multipart("/api/admin/users/import/preview").file(file(csv)).header("Authorization","Bearer "+admin))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3)).andExpect(jsonPath("$.data.valid_count").value(0)).andExpect(jsonPath("$.data.invalid_count").value(3))
            .andExpect(jsonPath("$.data.rows[0].row_number").value(2)).andExpect(jsonPath("$.data.rows[0].errors").isNotEmpty()).andReturn();
        String hash=json.readTree(response.getResponse().getContentAsString()).path("data").path("file_sha256").asText();
        http.perform(confirmation(csv,hash,"Import-test-only-123").header("Authorization","Bearer "+admin)).andExpect(status().isConflict()).andExpect(jsonPath("$.details.rows[0].row_number").value(2));
        assertEquals(3,db.count("SELECT count(*) FROM users"));assertEquals(0,db.count("SELECT count(*) FROM audit_events"));
    }
    @Test void changedFileAndDatabaseConflictBetweenPreviewAndConfirmRejectWholeBatch() throws Exception {
        String admin=token("admin"),hash=preview(admin,VALID);
        http.perform(confirmation(VALID+"\r\n",hash,"Import-test-only-123").header("Authorization","Bearer "+admin)).andExpect(status().isConflict()).andExpect(jsonPath("$.details.reason").value("file_changed"));
        db.update("INSERT INTO users(username,password_hash,real_name,campus_id,role) VALUES('202630012','unused','冲突模拟用户','202630012','staff')");
        http.perform(confirmation(VALID,hash,"Import-test-only-123").header("Authorization","Bearer "+admin)).andExpect(status().isConflict()).andExpect(jsonPath("$.details.rows[0].row_number").value(3));
        assertEquals(0,db.count("SELECT count(*) FROM users WHERE username='202600011'"));
        assertEquals(0,db.count("SELECT count(*) FROM audit_events"));
    }
    @Test void parserAcceptsBomQuotesCommaEscapedQuoteAndMultilineFields() throws Exception {
        String csv="\uFEFF"+HEADER+"202630013,\"模拟,\"\"姓名\"\"\n第二行\",staff,,\r\n";
        String admin=token("admin");
        http.perform(multipart("/api/admin/users/import/preview").file(file(csv)).header("Authorization","Bearer "+admin)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.valid_count").value(1)).andExpect(jsonPath("$.data.rows[0].real_name").value("模拟,\"姓名\"\n第二行"));
    }
    @Test void fileErrorsRejectUnknownDuplicateCredentialHeadersBrokenUtf8FormatAndLimits() throws Exception {
        String admin=token("admin");
        for(String csv:List.of("",HEADER,"username,real_name,role\nuser,姓名,student\n","campus_id,username,real_name,role,role\nA,user,姓名,student,staff\n","campus_id,username,real_name,role,password\nA,user,姓名,student,secret\n",HEADER+"A,user,\"unterminated,student,,",HEADER+"too,few\n")) {
            http.perform(multipart("/api/admin/users/import/preview").file(file(csv)).header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        }
        http.perform(multipart("/api/admin/users/import/preview").file(new MockMultipartFile("file","bad.csv","text/csv",new byte[]{(byte)0xC3,(byte)0x28})).header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        http.perform(multipart("/api/admin/users/import/preview").file(new MockMultipartFile("file","large.csv","text/csv",new byte[1024*1024+1])).header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        StringBuilder huge=new StringBuilder(HEADER);
        for(int i=1;i<=500;i++) { String id=String.format("2026%1d%04d",0,i);huge.append(id).append(",姓名,student,,\n"); }
        http.perform(multipart("/api/admin/users/import/preview").file(file(huge.toString())).header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(500));
        String extra=String.format("2026%1d%04d",0,501);huge.append(extra).append(",姓名,student,,\n");
        http.perform(multipart("/api/admin/users/import/preview").file(file(huge.toString())).header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        assertEquals(3,db.count("SELECT count(*) FROM users"));
    }
    @Test void unauthorizedUsersAndInvalidCredentialsCannotImport() throws Exception {
        http.perform(multipart("/api/admin/users/import/preview").file(file(VALID))).andExpect(status().isUnauthorized());
        for(String role:List.of("student","staff")) {
            String token=token(role);
            http.perform(get("/api/admin/users/import/template").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
            http.perform(multipart("/api/admin/users/import/preview").file(file(VALID)).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
            http.perform(confirmation(VALID,"not-previewed","123456").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        }
        String admin=token("admin"),hash=preview(admin,VALID);
        http.perform(confirmation(VALID,hash,"short").header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        http.perform(multipart("/api/admin/users/import/confirm").file(file(VALID)).param("file_sha256",hash).param("initial_password","123456").header("Authorization","Bearer "+admin)).andExpect(status().isBadRequest());
        assertEquals(3,db.count("SELECT count(*) FROM users"));
    }
    @Test void concurrentConfirmationCommitsOneWholeBatchOnly() throws Exception {
        String admin=token("admin"),hash=preview(admin,VALID);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready=new java.util.concurrent.CountDownLatch(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<Integer>> results=new ArrayList<>();
            for(int i=0;i<2;i++) results.add(pool.submit(()->{
                ready.countDown();start.await();
                return http.perform(confirmation(VALID,hash,"Import-test-only-123").header("Authorization","Bearer "+admin)).andReturn().getResponse().getStatus();
            }));
            assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));start.countDown();
            var statuses=new ArrayList<Integer>();for(var result:results) statuses.add(result.get(30,java.util.concurrent.TimeUnit.SECONDS));
            Collections.sort(statuses);assertEquals(List.of(200,409),statuses);
            assertEquals(2,db.count("SELECT count(*) FROM users WHERE username IN ('202600011','202630012')"));
            assertEquals(1,db.count("SELECT count(*) FROM audit_events WHERE action='user.import'"));
        } finally { start.countDown();pool.shutdownNow(); }
    }
    @Test void auditFailureAfterFirstUserRollsBackEntireBatchAndReportsServerFailure() throws Exception {
        String admin=token("admin"),hash=preview(admin,VALID);
        // 第一条user.create审计可成功，第二条被故障约束拒绝，确保不是“仅第一条没写”测试。
        db.update("ALTER TABLE audit_events ADD CONSTRAINT s04_reject_second CHECK (after_values->>'username' IS DISTINCT FROM '202630012') NOT VALID");
        try {
            http.perform(confirmation(VALID,hash,"Import-test-only-123").header("Authorization","Bearer "+admin)).andExpect(status().isInternalServerError());
            assertEquals(0,db.count("SELECT count(*) FROM users WHERE username IN ('202600011','202630012')"));
            assertEquals(0,db.count("SELECT count(*) FROM audit_events"));
        } finally { db.update("ALTER TABLE audit_events DROP CONSTRAINT s04_reject_second"); }
    }
}
