package cn.studyroom;

import java.util.*;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/reservations")
public class CheckinCorrectionController {
    private final BookingService bookings;
    public CheckinCorrectionController(BookingService bookings){this.bookings=bookings;}
    @GetMapping("/{id}/checkin-correction") Object detail(HttpServletRequest req,@PathVariable long id){return Api.ok(bookings.correctionDetail(WebSecurity.uid(req),id));}
    @PostMapping("/{id}/correct-checkin") Object correct(HttpServletRequest req,@PathVariable long id,@RequestBody Map<String,Object> body){
        Api.require(body.keySet().equals(Set.of("operation_id","checkin_at","reason","verification_note","facts_confirmed"))&&Boolean.TRUE.equals(body.get("facts_confirmed")),400,"补签须提供完整核实内容且不得包含额外字段");
        for(String key:List.of("operation_id","checkin_at","reason","verification_note"))Api.require(body.get(key) instanceof String,400,"补签时刻、标识和依据须为文本");
        var result=bookings.correctCheckin(WebSecurity.uid(req),id,(String)body.get("operation_id"),(String)body.get("checkin_at"),(String)body.get("reason"),(String)body.get("verification_note"));
        if(result.status()!=200)throw Api.fail(result.status(),result.message(),result.details());return Api.ok(result.data());
    }
}
