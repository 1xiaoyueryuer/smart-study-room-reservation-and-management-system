package cn.studyroom;

import java.util.*;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ComplaintPriorityController {
    private final ComplaintPriorityService priorities;private final com.fasterxml.jackson.databind.ObjectMapper json;
    public ComplaintPriorityController(ComplaintPriorityService priorities,com.fasterxml.jackson.databind.ObjectMapper json){this.priorities=priorities;this.json=json;}
    @GetMapping("/complaint-priority-mappings") Object options(HttpServletRequest req){return Api.ok(priorities.current(WebSecurity.uid(req),false));}
    @GetMapping("/admin/complaint-priority-mappings") Object adminMappings(HttpServletRequest req){return Api.ok(priorities.current(WebSecurity.uid(req),true));}
    @PutMapping("/admin/complaint-priority-mappings") Object replace(HttpServletRequest req,@RequestBody Map<String,Object> body){
        Api.require(Set.of("version","operation_id","reason","rules").containsAll(body.keySet())&&body.keySet().containsAll(Set.of("version","operation_id","reason","rules")),400,"映射仅接受版本、操作标识、理由和规则集");
        Api.require(body.get("version") instanceof Number&&body.get("operation_id") instanceof String&&body.get("reason") instanceof String&&body.get("rules") instanceof List<?>,400,"映射请求字段格式错误");Number version=(Number)body.get("version");Api.require(version.doubleValue()==Math.rint(version.doubleValue()),400,"映射版本须为整数");
        for(Object item:(List<?>)body.get("rules")){Api.require(item instanceof Map<?,?>,400,"映射规则须为对象");var rule=(Map<?,?>)item;Api.require(Set.of("fault_type","impact_scope","priority","enabled").containsAll(rule.keySet())&&rule.keySet().containsAll(Set.of("fault_type","impact_scope","priority","enabled")),400,"映射规则字段不完整或包含未知字段");Api.require(rule.get("fault_type") instanceof String&&rule.get("impact_scope") instanceof String&&rule.get("priority") instanceof Number&&rule.get("enabled") instanceof Boolean,400,"映射规则字段格式错误");Number priority=(Number)rule.get("priority");Api.require(priority.doubleValue()==Math.rint(priority.doubleValue()),400,"映射等级须为整数");}
        try{return Api.ok(priorities.replace(WebSecurity.uid(req),json.convertValue(body,Requests.ComplaintPriorityMappings.class)));}catch(IllegalArgumentException e){throw Api.fail(400,"映射请求格式错误");}
    }
}
