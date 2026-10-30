package cn.studyroom;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reservations/{id}/review")
public class ReviewController {
    private final ReviewService reviews;
    public ReviewController(ReviewService reviews){this.reviews=reviews;}
    @GetMapping Object detail(HttpServletRequest req,@PathVariable long id){return Api.ok(reviews.detail(WebSecurity.uid(req),id));}
    @PostMapping Object create(HttpServletRequest req,@PathVariable long id,@RequestBody JsonNode body){return save(req,id,body,false);}
    @PutMapping Object update(HttpServletRequest req,@PathVariable long id,@RequestBody JsonNode body){return save(req,id,body,true);}
    private Object save(HttpServletRequest req,long id,JsonNode body,boolean update){
        Set<String> keys=new HashSet<>();if(body.isObject())body.fieldNames().forEachRemaining(keys::add);Api.require(keys.equals(update?Set.of("operation_id","rating","content","version"):Set.of("operation_id","rating","content")),400,"评价参数字段不完整或含额外字段");Api.require(body.path("operation_id").isTextual()&&body.path("content").isTextual(),400,"评价文字和操作标识须为文本");var rating=body.path("rating");Api.require(rating.isIntegralNumber()&&rating.canConvertToInt(),400,"评分须为真实JSON整数");Long version=null;if(update){var node=body.path("version");Api.require(node.isIntegralNumber()&&node.canConvertToLong()&&node.longValue()>0,400,"版本须为正整数");version=node.longValue();}var result=reviews.saveForClient(WebSecurity.uid(req),id,update?"update":"create",body.get("operation_id").textValue(),rating.intValue(),body.get("content").textValue(),version);if(result.status()!=200)throw Api.fail(result.status(),result.message(),result.details());return Api.ok(result.data());
    }
}
