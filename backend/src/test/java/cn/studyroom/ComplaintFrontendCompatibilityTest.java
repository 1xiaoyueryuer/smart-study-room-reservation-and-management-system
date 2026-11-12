package cn.studyroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ComplaintFrontendCompatibilityTest {
    private final ComplaintService service=mock(ComplaintService.class);
    private final ComplaintController controller=new ComplaintController(service,mock(AttachmentService.class),new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE),mock(ComplaintWorkflowService.class));

    @Test void facilityAliasAndStringSafetyChoicesNormalizeToTheCurrentV11Request() {
        when(service.create(eq(42L),any())).thenReturn(Map.of("id",101L));
        var body=new LinkedHashMap<String,Object>();
        body.put("type","service");body.put("service_kind","facility");body.put("room_id",8);body.put("description","实际故障");body.put("operation_id","frontend-facility-key");body.put("attachment_ids",List.of());body.put("fault_type","插座");body.put("impact_scope","局部影响");body.put("safety_risk","none");body.put("unsafe_area","yes");body.put("priority_preview",2);
        assertEquals(0,create(body).get("code"));
        var captor=org.mockito.ArgumentCaptor.forClass(Requests.Complaint.class);verify(service).create(eq(42L),captor.capture());
        var normalized=captor.getValue();assertEquals("facility_repair",normalized.serviceKind());assertEquals("插座",normalized.faultType());assertEquals("局部影响",normalized.impactScope());assertFalse(normalized.safetyRisk());assertTrue(normalized.unsafeArea());
    }

    @Test void otherServiceAliasMapsRequestTypeAndDiscardsOnlyPresentationMetadata() {
        when(service.create(eq(42L),any())).thenReturn(Map.of("id",102L));
        var body=new LinkedHashMap<String,Object>();body.put("type","service");body.put("service_kind","other");body.put("request_type","环境卫生");body.put("room_id",9);body.put("description","实际情况");body.put("operation_id","frontend-other-key");body.put("attachment_ids",List.of());
        assertEquals(0,create(body).get("code"));
        var captor=org.mockito.ArgumentCaptor.forClass(Requests.Complaint.class);verify(service).create(eq(42L),captor.capture());
        assertEquals("other_service",captor.getValue().serviceKind());assertEquals("环境卫生",captor.getValue().serviceTopic());
    }

    @Test void passwordChangeRequestUsesTheFrontendSnakeCaseContract() throws Exception {
        var mapper=new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var request=mapper.readValue("{\"current_password\":\"old-pass\",\"new_password\":\"new-pass\"}",Requests.ChangePassword.class);
        assertEquals("old-pass",request.currentPassword());assertEquals("new-pass",request.newPassword());
    }

    @Test void passwordChangeChecksCurrentSecretAndAuditsOnlyASecretFreeReceipt() {
        var db=mock(Store.class);var auth=mock(AuthService.class);var audit=mock(AuditService.class);
        var encoder=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();String oldHash=encoder.encode("old-pass-1");
        when(db.one("SELECT password_hash,status FROM users WHERE id=?",42L)).thenReturn(Map.of("password_hash",oldHash,"status","active"));
        when(auth.profile(42L)).thenReturn(Map.of("id",42L,"username","campus-user"));
        var users=new UserService(db,auth,audit,null,java.time.Clock.systemUTC());
        var result=users.changePassword(42L,new Requests.ChangePassword("old-pass-1","new-pass-2"));
        assertEquals("campus-user",result.get("username"));
        var update=org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(db).update(eq("UPDATE users SET password_hash=?,updated_at=CURRENT_TIMESTAMP WHERE id=?"),any(),eq(42L));
        verify(audit).record(eq(42L),eq("user.password.change"),eq("user"),eq("42"),eq("本人修改登录口令"),eq(Map.of()),eq(Map.of("changed",true)));
    }

    private Map<String,Object> create(Map<String,Object> body) {
        var http=new MockHttpServletRequest();http.setAttribute("user",Map.of("id",42L));
        return cast(controller.create(http,body));
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> cast(Object value){return (Map<String,Object>)value;}
}
