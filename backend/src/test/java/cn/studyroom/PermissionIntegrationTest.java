package cn.studyroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDate;
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

/** 使用真实HTTP授权链、PostgreSQL和Redis；禁止连接/清理开发库。 */
@EnabledIfEnvironmentVariable(named="STUDYROOM_INTEGRATION",matches="true")
@SpringBootTest(properties={"spring.datasource.url=${STUDYROOM_TEST_DB_URL:jdbc:postgresql://127.0.0.1:5433/studyroom_java_test}","studyroom.seed=false","studyroom.scheduler-enabled=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class PermissionIntegrationTest {
    @Autowired MockMvc http;
    @Autowired Store db;
    @Autowired OpeningScheduleService opening;
    @Autowired ObjectMapper json;
    @Autowired AuthService auth;
    @Autowired BookingService bookings;
    @Autowired Clock clock;
    @Autowired StringRedisTemplate redis;
    Map<String,Long> ids;
    String remote;
    long room, seat1, seat2;
    List<String> revokedTokens=new ArrayList<>();
    static final String PASSWORD="S02-test-only-password";

    @BeforeEach void fixture() {
        assertEquals("studyroom_java_test",db.one("SELECT current_database() AS name").get("name"),"禁止操作其他数据库");
        TestDatabaseCleanup.clear(db);
        remote="s02-test-"+UUID.randomUUID();
        ids=new LinkedHashMap<>();
        String hash=new BCryptPasswordEncoder().encode(PASSWORD);
        for(String role:List.of("student","staff","admin")) ids.put(role,db.insert("INSERT INTO users(username,password_hash,real_name,role) VALUES(?,?,?,?)","s02_"+role,hash,"S02模拟"+role,role));
        room=db.insert("INSERT INTO rooms(name,seat_rows,seat_cols) VALUES('S02模拟空间',1,2)");
        opening.initializeDefaults(room);
        seat1=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'01',1,1)",room);
        seat2=db.insert("INSERT INTO seats(room_id,seat_no,row_no,col_no) VALUES(?,'02',1,2)",room);
    }
    @AfterEach void clearOwnRedisKeys() throws Exception {
        if(remote!=null) redis.delete("studyroom-java:rate:login:"+remote);
        if(ids!=null) for(long id:ids.values()) redis.delete("studyroom-java:rate:reserve:"+id);
        for(String token:revokedTokens) {
            String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            redis.delete("studyroom-java:revoked:"+hash);
        }
        revokedTokens.clear();
    }
    MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder req) { return req.with(r->{r.setRemoteAddr(remote);return r;}); }
    String token(String role) throws Exception {
        var result=http.perform(from(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("username","s02_"+role,"password",PASSWORD)))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.user.role").value(role)).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }
    String booking(long seat) throws Exception {
        return json.writeValueAsString(Map.of("seat_id",seat,"date",LocalDate.now(clock).plusDays(1).toString(),"start_time","10:00","end_time","11:00"));
    }
    @Test void ordinaryRolesCannotAccessManagementOrAnyStats() throws Exception {
        http.perform(get("/api/stats/overview")).andExpect(status().isUnauthorized());
        for(String role:List.of("student","staff")) {
            String token=token(role);
            http.perform(get("/api/auth/profile").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value(role));
            http.perform(get("/api/admin/users").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
            for(String path:List.of("overview","heatmap")) http.perform(get("/api/stats/"+path).param("room_id",""+room).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
            // Spring MVC accepts matrix parameters; authorization uses the matched route, not raw URI prefixes.
            http.perform(get("/api/stats;ignored/overview").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        }
        assertEquals(0,db.count("SELECT count(*) FROM reservations"));
    }
    @Test void adminCanManageAndReadStatsButCannotBook() throws Exception {
        String token=token("admin");
        http.perform(get("/api/admin/users").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        for(String path:List.of("overview","heatmap")) http.perform(get("/api/stats/"+path).param("room_id",""+room).header("Authorization","Bearer "+token)).andExpect(status().isOk());
        // These retired endpoints have no parallel aggregation implementation.
        for(String path:List.of("trend","peak","top-seats")) http.perform(get("/api/stats/"+path).header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
        http.perform(post("/api/reservations").header("Authorization","Bearer "+token).contentType("application/json").content(booking(seat1))).andExpect(status().isForbidden());
        http.perform(post("/api/reservations/auto").header("Authorization","Bearer "+token).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        http.perform(post("/api/reservations/999/cancel").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        http.perform(post("/api/waitlist").header("Authorization","Bearer "+token).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        var req=json.readValue(booking(seat1),Requests.Reservation.class);
        assertEquals(403,assertThrows(Api.Failure.class,()->bookings.create(ids.get("admin"),req)).status);
        assertEquals(0,db.count("SELECT count(*) FROM reservations"));
    }
    @Test void studentAndStaffShareActualBookingFlow() throws Exception {
        int index=0;
        for(String role:List.of("student","staff")) {
            String token=token(role);
            var result=http.perform(post("/api/reservations").header("Authorization","Bearer "+token).contentType("application/json").content(booking(index++==0?seat1:seat2)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("pending")).andReturn();
            long id=json.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
            http.perform(post("/api/reservations/"+id+"/cancel").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        }
        assertEquals(2,db.count("SELECT count(*) FROM reservations WHERE status='cancelled'"));
    }
    @Test void publicRegistrationIsRejectedWithoutCreatingIdentity() throws Exception {
        long before=db.count("SELECT count(*) FROM users");
        http.perform(post("/api/auth/register").contentType("application/json").content("{\"username\":\"intruder\",\"password\":\"123456\",\"real_name\":\"test\"}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        http.perform(post("/api/auth/register")).andExpect(status().isForbidden());
        assertEquals(403,assertThrows(Api.Failure.class,()->auth.register(new Requests.Register("intruder","123456","test",null))).status);
        assertEquals(before,db.count("SELECT count(*) FROM users"));
    }
    @Test void deletedLoginIdentityIsUnauthorizedButMissingBusinessResourceStaysNotFound() throws Exception {
        String deleted=token("staff"),valid=token("student");long id=ids.get("staff");
        // 此用户无任何业务引用；仅删除专用测试库的当前测试夹具。
        assertEquals(1,db.update("DELETE FROM users WHERE id=?",id));
        http.perform(get("/api/rooms").header("Authorization","Bearer "+deleted)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(401));
        var failure=assertThrows(Api.Failure.class,()->auth.authenticate(deleted));assertEquals(401,failure.status);assertEquals("登录身份已失效，请重新登录",failure.getMessage());
        assertEquals(404,assertThrows(Api.Failure.class,()->auth.profile(id)).status);
        http.perform(get("/api/rooms/999999999/seats").param("date",LocalDate.now(clock).plusDays(1).toString()).param("start","10:00").param("end","11:00").header("Authorization","Bearer "+valid)).andExpect(status().isNotFound());
    }
    @Test void logoutAndDisableInvalidateExistingTokens() throws Exception {
        String student=token("student");revokedTokens.add(student);
        http.perform(post("/api/auth/logout").header("Authorization","Bearer "+student)).andExpect(status().isOk());
        http.perform(get("/api/auth/profile").header("Authorization","Bearer "+student)).andExpect(status().isUnauthorized());
        String staff=token("staff"), admin=token("admin");
        http.perform(put("/api/admin/users/"+ids.get("staff")+"/status").header("Authorization","Bearer "+admin).contentType("application/json").content("{\"status\":\"disabled\",\"reason\":\"测试禁用\"}"))
            .andExpect(status().isOk());
        http.perform(get("/api/auth/profile").header("Authorization","Bearer "+staff)).andExpect(status().isUnauthorized());
        http.perform(from(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(Map.of("username","s02_staff","password",PASSWORD)))))
            .andExpect(status().isForbidden());
    }
}
