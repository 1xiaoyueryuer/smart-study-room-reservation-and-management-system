package cn.studyroom;

import com.fasterxml.jackson.databind.ObjectMapper;
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserIntegrationTest {
    @Autowired MockMvc http;
    @Autowired Store db;
    @Autowired OpeningScheduleService opening;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate redis;
    Map<String,Long> ids;
    String remote;
    static final String PASSWORD="S02-test-only-password";

    @BeforeEach void fixture() {
        assertEquals("studyroom_java_test",db.one("SELECT current_database() AS name").get("name"),"禁止操作其他数据库");
        TestDatabaseCleanup.clear(db);
        ids=new LinkedHashMap<>();remote="s03-test-"+UUID.randomUUID();
        String hash=new BCryptPasswordEncoder().encode(PASSWORD);
        for(String role:List.of("student","staff","admin")) {
            String identity=switch(role){case "student"->"202600901";case "staff"->"202630902";default->"202630903";};
            ids.put(role,db.insert("INSERT INTO users(username,password_hash,real_name,campus_id,role) VALUES(?,?,?,?,?)",identity,hash,"S03模拟"+role,identity,role));
        }
    }
    @AfterEach void clearOwnLoginRate() { if(remote!=null) redis.delete("studyroom-java:rate:login:"+remote); }
    @AfterAll void leaveExplicitPageFixtures() { fixture(); }
    String token(String username,String password) throws Exception {
        var result=http.perform(post("/api/auth/login").with(r->{r.setRemoteAddr(remote);return r;}).contentType("application/json").content(json.writeValueAsString(Map.of("username",username,"password",password))))
            .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }
    String token(String role) throws Exception { return token((String)db.one("SELECT username FROM users WHERE id=?",ids.get(role)).get("username"),PASSWORD); }
    Map<String,Object> user(String role,String campus,String username) {
        var fields=new LinkedHashMap<String,Object>();
        fields.put("username",username);fields.put("real_name","新建模拟校园用户");fields.put("campus_id",campus);fields.put("role",role);fields.put("status","active");
        fields.put("phone","+86 123 456");fields.put("email","test@example.edu");fields.put("password","test-pass-123");fields.put("reason","测试账号维护");return fields;
    }
    MockHttpServletRequestBuilder payload(MockHttpServletRequestBuilder request,String token,Object body) throws Exception {
        return request.header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(body));
    }
    @Test void createCampusUsersCanActuallyLoginAndNeverExposeCredentials() throws Exception {
        String admin=token("admin");
        for(String role:List.of("student","staff","admin")) {
            String campus=switch(role){case "student"->"202600201";case "staff"->"202630201";default->"202630202";};
            var response=http.perform(payload(post("/api/admin/users"),admin,user(role,campus,campus)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.campus_id").value(campus)).andExpect(jsonPath("$.data.username").value(campus)).andExpect(jsonPath("$.data.password_hash").doesNotExist()).andExpect(jsonPath("$.data.password").doesNotExist()).andReturn();
            assertFalse(response.getResponse().getContentAsString().contains("test-pass-123"));
            token(campus,"test-pass-123");
        }
        assertEquals(3,db.count("SELECT count(*) FROM audit_events WHERE action='user.create'"));
        assertEquals(3,db.count("SELECT count(*) FROM users WHERE campus_id IN ('202600201','202630201','202630202') AND username=campus_id AND student_no IS NULL"));
        for(var record:db.list("SELECT before_values::text AS before_data,after_values::text AS after_data FROM audit_events")) {
            String values=record.toString();assertFalse(values.contains("test-pass-123"));assertFalse(values.contains("password_hash"));assertFalse(values.contains("$2a$"));
        }
    }
    @Test void duplicateOrInvalidCreationIsAtomicAndReportsConflictOrBadRequest() throws Exception {
        String admin=token("admin");
        http.perform(payload(post("/api/admin/users"),admin,user("student","202600901","202600901"))).andExpect(status().isConflict());
        http.perform(payload(post("/api/admin/users"),admin,user("student","202600202","not-the-campus-id"))).andExpect(status().isBadRequest());
        http.perform(payload(post("/api/admin/users"),admin,user("student","202630202","202630202"))).andExpect(status().isBadRequest());
        http.perform(payload(post("/api/admin/users"),admin,user("staff","202600203","202600203"))).andExpect(status().isBadRequest());
        http.perform(payload(post("/api/admin/users"),admin,user("student","199900204","199900204"))).andExpect(status().isBadRequest());
        var missing=user("student",null,"new_one");
        http.perform(payload(post("/api/admin/users"),admin,missing)).andExpect(status().isBadRequest());
        var oversized=user("student","202600204","202600204");oversized.put("password","密".repeat(25));
        http.perform(payload(post("/api/admin/users"),admin,oversized)).andExpect(status().isBadRequest());
        var noReason=user("student","202600205","202600205");noReason.put("reason"," ");
        http.perform(payload(post("/api/admin/users"),admin,noReason)).andExpect(status().isBadRequest());
        assertEquals(3,db.count("SELECT count(*) FROM users"));assertEquals(0,db.count("SELECT count(*) FROM audit_events"));
    }
    @Test void editKeepsHashHistoryAndUnknownIdentityButCannotEraseKnownIdentity() throws Exception {
        String admin=token("admin");long uid=ids.get("staff");
        String hash=Store.text(db.one("SELECT password_hash FROM users WHERE id=?",uid),"password_hash");
        long room=db.insert("INSERT INTO rooms(name,seat_rows,seat_cols) VALUES('history-room',1,1)");
        opening.initializeDefaults(room);
        long seat=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'01',1,1)",room);
        db.update("INSERT INTO reservations(user_id,seat_id,res_date,start_time,end_time,status) VALUES(?,?,'2026-10-20','10:00','11:00','completed')",uid,seat);
        var changes=user("staff","202630902","202630902");changes.remove("password");changes.put("real_name","已编辑模拟姓名");
        http.perform(payload(put("/api/admin/users/"+uid),admin,changes)).andExpect(status().isOk()).andExpect(jsonPath("$.data.real_name").value("已编辑模拟姓名"));
        assertEquals(hash,db.one("SELECT password_hash FROM users WHERE id=?",uid).get("password_hash"));
        assertEquals(1,db.count("SELECT count(*) FROM reservations WHERE user_id=?",uid));
        changes.put("campus_id",null);
        http.perform(payload(put("/api/admin/users/"+uid),admin,changes)).andExpect(status().isBadRequest());
        db.update("UPDATE users SET campus_id=NULL WHERE id=?",uid); // 明确构造遗留未知身份夹具。
        http.perform(payload(put("/api/admin/users/"+uid),admin,changes)).andExpect(status().isOk()).andExpect(jsonPath("$.data.campus_id").isEmpty());
        http.perform(payload(put("/api/admin/users/999999999"),admin,changes)).andExpect(status().isNotFound());
    }
    @Test void selfContactPatchRejectsEveryIdentityFieldAndNeverUpdatesAnotherUser() throws Exception {
        String student=token("student");
        for(String field:List.of("role","id","campus_id","username","real_name","status","password","student_no")) {
            var patch=new LinkedHashMap<String,Object>();patch.put("phone","123");patch.put(field,"forbidden");
            http.perform(payload(patch("/api/auth/profile"),student,patch)).andExpect(status().isBadRequest());
        }
        http.perform(payload(patch("/api/auth/profile"),student,Map.of("email","invalid-address"))).andExpect(status().isBadRequest());
        http.perform(payload(patch("/api/auth/profile"),student,Map.of("phone",123))).andExpect(status().isBadRequest());
        http.perform(payload(patch("/api/auth/profile"),student,Map.of("phone"," 123 ","email","contact@example.edu"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.phone").value("123"));
        http.perform(payload(patch("/api/auth/profile"),student,Map.of("phone",""))).andExpect(status().isOk()).andExpect(jsonPath("$.data.phone").isEmpty()).andExpect(jsonPath("$.data.email").value("contact@example.edu"));
        assertNull(db.one("SELECT phone FROM users WHERE id=?",ids.get("staff")).get("phone"));
        assertEquals("student",db.one("SELECT role FROM users WHERE id=?",ids.get("student")).get("role"));
        http.perform(payload(post("/api/admin/users"),student,user("student","FORBIDDEN","forbidden"))).andExpect(status().isForbidden());
    }
    @Test void ownPasswordCanBeChangedWithoutLoggingCredentials() throws Exception {
        String student=token("student");
        http.perform(payload(patch("/api/auth/password"),student,Map.of("current_password","wrong-password","new_password","updated-pass-456"))).andExpect(status().isBadRequest());
        http.perform(payload(patch("/api/auth/password"),student,Map.of("current_password",PASSWORD,"new_password","updated-pass-456"))).andExpect(status().isOk());
        http.perform(post("/api/auth/login").with(r->{r.setRemoteAddr(remote);return r;}).contentType("application/json").content(json.writeValueAsString(Map.of("username","202600901","password",PASSWORD)))).andExpect(status().isUnauthorized());
        token("202600901","updated-pass-456");
        var audit=db.one("SELECT reason,before_values::text AS before_data,after_values::text AS after_data FROM audit_events WHERE action='user.password.change'");
        String values=audit.toString();assertFalse(values.contains(PASSWORD));assertFalse(values.contains("updated-pass-456"));assertTrue(values.contains("changed"));
    }
    @Test void cannotDisableOrDemoteSelfAndPasswordUpdateRemainsSafe() throws Exception {
        String admin=token("admin");long uid=ids.get("admin");
        http.perform(payload(put("/api/admin/users/"+uid+"/status"),admin,Map.of("status","disabled","reason","试图禁用本人"))).andExpect(status().isBadRequest());
        var demote=user("student","202600301","202600301");demote.remove("password");
        http.perform(payload(put("/api/admin/users/"+uid),admin,demote)).andExpect(status().isBadRequest());
        var reset=user("staff","202630902","202630902");reset.put("password","updated-pass-123");
        http.perform(payload(put("/api/admin/users/"+ids.get("staff")),admin,reset)).andExpect(status().isOk());
        token("202630902","updated-pass-123");
        http.perform(payload(put("/api/admin/users/"+ids.get("student")+"/status"),admin,Map.of("status","disabled"))).andExpect(status().isBadRequest());
    }
    @Test void auditFailureRollsBackCreateAndContactChanges() throws Exception {
        String admin=token("admin"),student=token("student");
        // 数据库真实故障注入，验证审计与业务共享同一事务。
        db.update("ALTER TABLE audit_events ADD CONSTRAINT s03_reject_audit CHECK (false) NOT VALID");
        try {
            http.perform(payload(post("/api/admin/users"),admin,user("student","202600401","202600401"))).andExpect(status().isInternalServerError());
            http.perform(payload(patch("/api/auth/profile"),student,Map.of("phone","not-saved"))).andExpect(status().isInternalServerError());
            assertEquals(0,db.count("SELECT count(*) FROM users WHERE username='202600401'"));
            assertNull(db.one("SELECT phone FROM users WHERE id=?",ids.get("student")).get("phone"));
            assertEquals(0,db.count("SELECT count(*) FROM audit_events"));
        } finally { db.update("ALTER TABLE audit_events DROP CONSTRAINT s03_reject_audit"); }
    }
}
