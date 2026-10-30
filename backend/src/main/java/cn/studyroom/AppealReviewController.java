package cn.studyroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/appeals")
public class AppealReviewController {
    private final AppealReviewService reviews;private final ObjectMapper json;
    public AppealReviewController(AppealReviewService reviews,ObjectMapper json){this.reviews=reviews;this.json=json;}
    @PostMapping("/{id}/review/preview") Object preview(HttpServletRequest req,@PathVariable long id,@RequestBody Map<String,Object> body){validate(body,false);return Api.ok(reviews.preview(WebSecurity.uid(req),id,convert(body,Requests.AppealReview.class)));}
    @PostMapping("/{id}/review/confirm") Object confirm(HttpServletRequest req,@PathVariable long id,@RequestBody Map<String,Object> body){validate(body,true);return Api.ok(reviews.confirm(WebSecurity.uid(req),id,convert(body,Requests.AppealReviewConfirm.class)));}
    private void validate(Map<String,Object> body,boolean confirm){var fields=confirm?Set.of("version","decision","reason","verification_note","facts_confirmed","impact_hash","operation_id"):Set.of("version","decision","reason","verification_note","facts_confirmed");Api.require(body.keySet().equals(fields),400,"审核仅接受本次规定的版本、决定、依据与确认字段");Object version=body.get("version");Api.require(version instanceof Integer||version instanceof Long||version instanceof java.math.BigInteger,400,"申诉版本须为整数");for(String key:fields)if(!Set.of("version","facts_confirmed").contains(key))Api.require(body.get(key) instanceof String,400,key+"须为文本");Api.require(body.get("facts_confirmed") instanceof Boolean&&Boolean.TRUE.equals(body.get("facts_confirmed")),400,"必须明确自报已核实事实与依据");}
    private <T>T convert(Map<String,Object> body,Class<T> type){try{return json.convertValue(body,type);}catch(IllegalArgumentException e){throw Api.fail(400,"审核参数格式错误");}}
}
