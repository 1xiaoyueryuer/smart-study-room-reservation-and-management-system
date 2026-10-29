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
        for(String table:List.of("notifications","violations","complaint_target_events","complaint_targets","reminders","complaint_attachments","complaint_events","complaints","resource_change_operations","usage_records","seat_codes","resource_unavailability","opening_windows","opening_schedules","audit_events","credit_logs","waitlist","reservations","seats","rooms","users")) db.update("DELETE FROM "+table);
        ids=new LinkedHashMap<>();remote="s03-test-"+UUID.randomUUID();
        String hash=new BCryptPasswordEncoder().encode(PASSWORD);
        for(String role:List.of("student","staff","admin")) ids.put(role,db.insert("INSERT INTO users(username,password_hash,real_name,campus_id,role) VALUES(?,?,?,?,?)","s03_"+role,hash,"S03模拟"+role,"S03-"+role,role));
    }
    @AfterEach void clearOwnLoginRate() { if(remote!=null) redis.delete("studyroom-java:rate:login:"+remote); }
    @AfterAll void leaveExplicitPageFixtures() { fixture(); }
    String token(String username,String password) throws Exception {
        var result=http.perform(post("/api/auth/login").with(r->{r.setRemoteAddr(remote);return r;}).contentType("application/json").content(json.writeValueAsString(Map.of("username",username,"password",password))))
            .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }
    String token(String role) throws Exception { return token("s03_"+role,PASSWORD); }
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
        for(String role:List.of("student","staff")) {
            var response=http.perform(payload(post("/api/admin/users"),admin,user(role,"NEW-"+role,"new_"+role)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.campus_id").value("NEW-"+role)).andExpect(jsonPath("$.data.password_hash").doesNotExist()).andExpect(jsonPath("$.data.password").doesNotExist()).andReturn();
            assertFalse(response.getResponse().getContentAsString().contains("test-pass-123"));
            token("new_"+role,"test-pass-123");
        }
        assertEquals(2,db.count("SELECT count(*) FROM audit_events WHERE action='user.create'"));
        assertEquals(2,db.count("SELECT count(*) FROM users WHERE username LIKE 'new_%' AND student_no IS NULL"));
        for(var record:db.list("SELECT before_values::text AS before_data,after_values::text AS after_data FROM audit_events")) {
            String values=record.toString();assertFalse(values.contains("test-pass-123"));assertFalse(values.contains("password_hash"));assertFalse(values.contains("$2a$"));
        }
    }
    @Test void duplicateOrInvalidCreationIsAtomicAndReportsConflictOrBadRequest() throws Exception {
        String admin=token("admin");
        http.perform(payload(post("/api/admin/users"),admin,user("student","S03-staff","unique_name"))).andExpect(status().isConflict());
        http.perform(payload(post("/api/admin/users"),admin,user("staff","unique-campus","s03_student"))).andExpect(status().isConflict());
        var missing=user("student",null,"new_one");
        http.perform(payload(post("/api/admin/users"),admin,missing)).andExpect(status().isBadRequest());
        var oversized=user("student","NEW","new_one");oversized.put("password","密".repeat(25));
        http.perform(payload(post("/api/admin/users"),admin,oversized)).andExpect(status().isBadRequest());
        var noReason=user("student","NEW","new_one");noReason.put("reason"," ");
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
        var changes=user("staff","UPDATED-STAFF","s03_staff");changes.remove("password");changes.put("real_name","已编辑模拟姓名");
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
    @Test void cannotDisableOrDemoteSelfAndPasswordUpdateRemainsSafe() throws Exception {
        String admin=token("admin");long uid=ids.get("admin");
        http.perform(payload(put("/api/admin/users/"+uid+"/status"),admin,Map.of("status","disabled","reason","试图禁用本人"))).andExpect(status().isBadRequest());
        var demote=user("student","S03-admin","s03_admin");demote.remove("password");
        http.perform(payload(put("/api/admin/users/"+uid),admin,demote)).andExpect(status().isBadRequest());
        var reset=user("staff","S03-staff","s03_staff");reset.put("password","updated-pass-123");
        http.perform(payload(put("/api/admin/users/"+ids.get("staff")),admin,reset)).andExpect(status().isOk());
        token("s03_staff","updated-pass-123");
        http.perform(payload(put("/api/admin/users/"+ids.get("student")+"/status"),admin,Map.of("status","disabled"))).andExpect(status().isBadRequest());
    }
    @Test void auditFailureRollsBackCreateAndContactChanges() throws Exception {
        String admin=token("admin"),student=token("student");
        // 数据库真实故障注入，验证审计与业务共享同一事务。
        db.update("ALTER TABLE audit_events ADD CONSTRAINT s03_reject_audit CHECK (false) NOT VALID");
        try {
            http.perform(payload(post("/api/admin/users"),admin,user("student","ROLLBACK","rollback_user"))).andExpect(status().isInternalServerError());
            http.perform(payload(patch("/api/auth/profile"),student,Map.of("phone","not-saved"))).andExpect(status().isInternalServerError());
            assertEquals(0,db.count("SELECT count(*) FROM users WHERE username='rollback_user'"));
            assertNull(db.one("SELECT phone FROM users WHERE id=?",ids.get("student")).get("phone"));
            assertEquals(0,db.count("SELECT count(*) FROM audit_events"));
        } finally { db.update("ALTER TABLE audit_events DROP CONSTRAINT s03_reject_audit"); }
    }
}
