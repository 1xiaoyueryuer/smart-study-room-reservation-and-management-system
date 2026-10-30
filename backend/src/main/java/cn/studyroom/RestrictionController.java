package cn.studyroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class RestrictionController {
    private final RestrictionWorkflow workflow;private final ObjectMapper json;
    public RestrictionController(RestrictionWorkflow workflow,ObjectMapper json){this.workflow=workflow;this.json=json;}
    @GetMapping("/restrictions/mine")Object mine(HttpServletRequest req,@RequestParam Map<String,String> parameters){return Api.ok(workflow.records(WebSecurity.uid(req),parameters,false));}
    @GetMapping("/restrictions/{id}")Object detail(HttpServletRequest req,@PathVariable long id){return Api.ok(workflow.detail(WebSecurity.uid(req),id));}
    @GetMapping("/booking-eligibility")Object eligibility(HttpServletRequest req){return Api.ok(workflow.eligibility(WebSecurity.uid(req)));}
    @GetMapping("/admin/restrictions")Object list(HttpServletRequest req,@RequestParam Map<String,String> parameters){return Api.ok(workflow.records(WebSecurity.uid(req),parameters,true));}
    @GetMapping("/admin/restrictions/{id}")Object detailAdmin(HttpServletRequest req,@PathVariable long id){return Api.ok(workflow.managementDetail(WebSecurity.uid(req),id));}
    @PostMapping("/admin/restrictions")Object create(HttpServletRequest req,@RequestBody Map<String,Object> body){return Api.ok(workflow.create(WebSecurity.uid(req),convert(body,Set.of("user_id","ends_at","reason","verification_note","facts_confirmed","operation_id"),Requests.RestrictionCreate.class)));}
    @PostMapping("/admin/restrictions/{id}/release")Object release(HttpServletRequest req,@PathVariable long id,@RequestBody Map<String,Object> body){return Api.ok(workflow.release(WebSecurity.uid(req),id,convert(body,Set.of("version","reason","verification_note","facts_confirmed","operation_id"),Requests.RestrictionRelease.class)));}
    private <T>T convert(Map<String,Object> body,Set<String> allowed,Class<T> type){Api.require(allowed.containsAll(body.keySet()),400,"请求含不允许的起点、身份或其他字段");for(String key:List.of("ends_at","reason","verification_note","operation_id"))Api.require(!body.containsKey(key)||body.get(key)==null||body.get(key)instanceof String,400,key+"须为文本");Api.require(!body.containsKey("facts_confirmed")||body.get("facts_confirmed")instanceof Boolean,400,"facts_confirmed须为布尔值");try{return json.convertValue(body,type);}catch(IllegalArgumentException e){throw Api.fail(400,"限制参数格式错误");}}
}
