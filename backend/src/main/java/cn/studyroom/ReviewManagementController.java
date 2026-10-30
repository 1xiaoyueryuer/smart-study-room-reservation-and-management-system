package cn.studyroom;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ReviewManagementController {
    private final ReviewService reviews;
    public ReviewManagementController(ReviewService reviews){this.reviews=reviews;}
    @GetMapping("/admin/reviews") Object list(HttpServletRequest req,@RequestParam(defaultValue="1") int page,@RequestParam(name="page_size",defaultValue="20") int size,@RequestParam(name="room_id",required=false) Long room,@RequestParam(required=false) String hidden){Api.require(hidden==null||hidden.equals("true")||hidden.equals("false"),400,"隐藏筛选须为true或false");return Api.ok(reviews.adminList(WebSecurity.uid(req),page,size,room,hidden==null?null:Boolean.valueOf(hidden)));}
    @GetMapping("/admin/reviews/{id}") Object detail(HttpServletRequest req,@PathVariable long id){return Api.ok(reviews.adminDetail(WebSecurity.uid(req),id));}
    @GetMapping("/rooms/{id}/reviews") Object publicList(HttpServletRequest req,@PathVariable long id,@RequestParam(defaultValue="1") int page,@RequestParam(name="page_size",defaultValue="20") int size){return Api.ok(reviews.roomReviews(WebSecurity.uid(req),id,page,size));}
    @PostMapping("/admin/reviews/{id}/reply") Object reply(HttpServletRequest req,@PathVariable long id,@RequestBody JsonNode body){return act(req,id,body,"reply","reply");}
    @PostMapping("/admin/reviews/{id}/hide") Object hide(HttpServletRequest req,@PathVariable long id,@RequestBody JsonNode body){return act(req,id,body,"hide","reason");}
    private Object act(HttpServletRequest req,long id,JsonNode body,String action,String field){Set<String> keys=new HashSet<>();if(body.isObject())body.fieldNames().forEachRemaining(keys::add);Api.require(keys.equals(Set.of("operation_id","version",field)),400,"管理评价只接受操作标识、版本及对应回复或理由，不允许修改评分");var version=body.path("version");Api.require(version.isIntegralNumber()&&version.canConvertToLong()&&version.longValue()>0&&body.path("operation_id").isTextual()&&body.path(field).isTextual(),400,"版本须为正整数，操作标识和内容须为文本");return Api.ok(reviews.moderate(WebSecurity.uid(req),id,action,body.get("operation_id").textValue(),version.longValue(),body.get(field).textValue()));}
}
