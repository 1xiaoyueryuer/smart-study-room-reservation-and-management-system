package cn.studyroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ComplaintManagementController {
    private final ComplaintManagementService management;private final ObjectMapper json;
    public ComplaintManagementController(ComplaintManagementService management,ObjectMapper json) {this.management=management;this.json=json;}
    @GetMapping("/admin/complaints") Object list(HttpServletRequest req,@RequestParam(required=false) String status,@RequestParam(required=false) String type,@RequestParam(name="room_id",required=false) Long room) {return Api.ok(management.list(WebSecurity.uid(req),status,type,room));}
    @GetMapping("/admin/complaints/{id}") Object detail(HttpServletRequest req,@PathVariable long id) {return Api.ok(management.detail(WebSecurity.uid(req),id));}
    @PostMapping("/admin/complaints/{id}/accept") Object accept(HttpServletRequest req,@PathVariable long id,@RequestBody Map<String,Object> body) {return Api.ok(management.accept(WebSecurity.uid(req),id,convert(body,Set.of("version","operation_id"),Requests.ComplaintAccept.class)));}
    @PostMapping("/admin/complaints/{id}/verifications") Object verify(HttpServletRequest req,@PathVariable long id,@RequestBody Map<String,Object> body) {for(String key:List.of("identity_confirmed","facts_confirmed","valid_reservation_confirmed")) Api.require(!body.containsKey(key)||body.get(key) instanceof Boolean,400,key+"须为布尔值");return Api.ok(management.verify(WebSecurity.uid(req),id,convert(body,Set.of("version","operation_id","outcome","occurred_at","public_note","private_note","subject_user_id","identity_confirmed","identity_basis","facts_confirmed","valid_reservation_confirmed"),Requests.ComplaintVerification.class)));}
    @GetMapping("/reminders/mine") Object reminders(HttpServletRequest req) {return Api.ok(management.reminders(WebSecurity.uid(req)));}
    private <T> T convert(Map<String,Object> body,Set<String> allowed,Class<T> type) {Api.require(allowed.containsAll(body.keySet()),400,"请求含不允许的身份、状态或其他字段");for(String key:List.of("operation_id","outcome","occurred_at","public_note","private_note","identity_basis")) Api.require(!body.containsKey(key)||body.get(key)==null||body.get(key) instanceof String,400,key+"须为文本");try {return json.convertValue(body,type);}catch(IllegalArgumentException e) {throw Api.fail(400,"核实参数格式错误");}}
}
