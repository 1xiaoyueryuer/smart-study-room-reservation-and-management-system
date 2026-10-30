package cn.studyroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AppealController {
    private final AppealService appeals;private final ObjectMapper json;
    public AppealController(AppealService appeals,ObjectMapper json){this.appeals=appeals;this.json=json;}
    @GetMapping("/violations/{id}/appeal-eligibility") Object eligibility(HttpServletRequest req,@PathVariable long id){return Api.ok(appeals.eligibility(WebSecurity.uid(req),id));}
    @PostMapping("/violations/{id}/appeals") Object create(HttpServletRequest req,@PathVariable long id,@RequestBody Map<String,Object> body){
        Api.require(Set.of("operation_id","description","attachment_ids").containsAll(body.keySet())&&body.keySet().containsAll(Set.of("operation_id","description")),400,"申诉仅接受操作标识、本人说明和申诉材料编号");Api.require(body.get("operation_id") instanceof String&&body.get("description") instanceof String,400,"操作标识和申诉说明须为文本");Object ids=body.get("attachment_ids");Api.require(ids==null||ids instanceof List<?> list&&list.stream().allMatch(idValue->idValue instanceof String),400,"材料编号须为UUID文本数组");Requests.Appeal request;try{request=json.convertValue(body,Requests.Appeal.class);}catch(IllegalArgumentException e){throw Api.fail(400,"申诉参数格式错误");}return Api.ok(appeals.create(WebSecurity.uid(req),id,request));
    }
    @GetMapping("/appeals/mine") Object mine(HttpServletRequest req){return Api.ok(appeals.mine(WebSecurity.uid(req)));}
    @GetMapping("/appeals/{id}") Object detail(HttpServletRequest req,@PathVariable long id){return Api.ok(appeals.detail(WebSecurity.uid(req),id));}
    @GetMapping("/admin/appeals") Object adminList(HttpServletRequest req,@RequestParam(required=false) String status){return Api.ok(appeals.listAdmin(WebSecurity.uid(req),status));}
    @GetMapping("/admin/appeals/{id}") Object adminDetail(HttpServletRequest req,@PathVariable long id){return Api.ok(appeals.detailAdmin(WebSecurity.uid(req),id));}
}
