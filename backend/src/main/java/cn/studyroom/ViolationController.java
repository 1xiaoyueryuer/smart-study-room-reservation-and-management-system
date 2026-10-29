package cn.studyroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/complaint-events")
public class ViolationController {
    private final ViolationService violations;private final ObjectMapper json;
    public ViolationController(ViolationService violations,ObjectMapper json){this.violations=violations;this.json=json;}
    @PostMapping("/{id}/violations") Object register(HttpServletRequest req,@PathVariable long id,@RequestBody Map<String,Object> body){Api.require(Set.of("operation_id","reason").containsAll(body.keySet()),400,"登记只接受操作标识和独立公开事实说明，主体及事实来自核实事件");for(String key:List.of("operation_id","reason"))Api.require(!body.containsKey(key)||body.get(key)==null||body.get(key) instanceof String,400,key+"须为文本");Requests.ManualViolation request;try{request=json.convertValue(body,Requests.ManualViolation.class);}catch(IllegalArgumentException e){throw Api.fail(400,"违规登记参数格式错误");}return Api.ok(violations.registerManual(WebSecurity.uid(req),id,request));}
}
