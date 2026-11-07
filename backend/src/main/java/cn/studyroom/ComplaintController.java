package cn.studyroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.*;

@RestController
@RequestMapping("/api")
public class ComplaintController {
    private final ComplaintService complaints;private final AttachmentService attachments;private final ObjectMapper json;private final ComplaintWorkflowService workflow;
    public ComplaintController(ComplaintService complaints,AttachmentService attachments,ObjectMapper json,ComplaintWorkflowService workflow) { this.complaints=complaints;this.attachments=attachments;this.json=json;this.workflow=workflow; }
    @PostMapping("/complaints") Object create(HttpServletRequest req,@RequestBody Map<String,Object> body) {
        var allowed=Set.of("type","reservation_id","room_id","description","attachment_ids","operation_id","service_kind","service_topic","fault_type","impact_scope","safety_risk","unsafe_area");Api.require(allowed.containsAll(body.keySet())&&body.keySet().containsAll(Set.of("type","description","operation_id")),400,"投诉仅接受V1.1类型、空间/本人预约、说明、附件及对应分支字段");Api.require(body.get("description") instanceof String&&body.get("type") instanceof String,400,"投诉类型和说明须为文本");for(String key:List.of("safety_risk","unsafe_area"))Api.require(!body.containsKey(key)||body.get(key)==null||body.get(key) instanceof Boolean,400,key+"须为布尔值");for(String key:List.of("service_kind","service_topic","fault_type","impact_scope"))Api.require(!body.containsKey(key)||body.get(key)==null||body.get(key) instanceof String,400,key+"须为文本");Requests.Complaint request;try { request=json.convertValue(body,Requests.Complaint.class); } catch(IllegalArgumentException e) { throw Api.fail(400,"投诉参数格式错误"); }return Api.ok(complaints.create(WebSecurity.uid(req),request));
    }
    @GetMapping("/complaints/mine") Object mine(HttpServletRequest req,@RequestParam Map<String,String> parameters) { return Api.ok(workflow.records(WebSecurity.uid(req),parameters,false)); }
    @GetMapping("/complaints/{id}") Object detail(HttpServletRequest req,@PathVariable long id) { return Api.ok(workflow.detail(WebSecurity.uid(req),id)); }
    @PostMapping("/complaints/{id}/{action:withdraw|confirm|object}") Object action(HttpServletRequest req,@PathVariable long id,@PathVariable String action,@RequestBody Map<String,Object> body) {
        var allowed=action.equals("object")?Set.of("version","operation_id","description"):Set.of("version","operation_id");Api.require(allowed.containsAll(body.keySet()),400,"投诉操作只接受版本、操作标识和本次允许的说明");for(String key:List.of("operation_id","description")) Api.require(!body.containsKey(key)||body.get(key)==null||body.get(key) instanceof String,400,key+"须为文本");Requests.ComplaintResponse request;try{request=json.convertValue(body,Requests.ComplaintResponse.class);}catch(IllegalArgumentException e){throw Api.fail(400,"投诉操作参数格式错误");}
        var result=workflow.action(WebSecurity.uid(req),id,action,request);if(result.status()!=200)throw Api.fail(result.status(),result.message(),result.details());return Api.ok(result.data());
    }
}
