package cn.studyroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/resource-changes")
public class ResourceChangeController {
    private final ResourceChangeService changes;private final ObjectMapper json;
    public ResourceChangeController(ResourceChangeService changes,ObjectMapper json) { this.changes=changes;this.json=json; }
    @PostMapping("/preview") Object preview(HttpServletRequest req,@RequestBody Map<String,Object> body) { proposal(body);return Api.ok(changes.preview(WebSecurity.uid(req),convert(body,Requests.ResourceChangeProposal.class))); }
    @PostMapping("/confirm") Object confirm(HttpServletRequest req,@RequestBody Map<String,Object> body) {
        Api.require(Set.of("proposal","impact_hash","operation_id","coordination").containsAll(body.keySet())&&body.keySet().containsAll(Set.of("proposal","impact_hash","operation_id")),400,"确认参数包含不支持字段或缺少必填字段");
        Api.require(body.get("proposal") instanceof Map<?,?>,400,"变更提案格式错误");proposal((Map<?,?>)body.get("proposal"));
        if(body.get("coordination")!=null) Api.require(body.get("coordination") instanceof Map<?,?> fields&&fields.keySet().equals(Set.of("confirmed","note")),400,"现场协调仅接受confirmed和note自报字段");
        return Api.ok(changes.confirm(WebSecurity.uid(req),convert(body,Requests.ResourceChangeConfirm.class)));
    }
    private void proposal(Map<?,?> body) { Api.require(body.keySet().equals(Set.of("change_type","target_id","payload")),400,"提案仅接受change_type、target_id和payload"); }
    private <T> T convert(Object body,Class<T> target) { try { return json.convertValue(body,target); } catch(IllegalArgumentException e) { throw Api.fail(400,"资源变更参数格式错误"); } }
}
