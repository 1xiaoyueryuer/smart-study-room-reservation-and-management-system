package cn.studyroom;

import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/availability")
public class AvailabilityController {
    private final AvailabilityService availability;
    public AvailabilityController(AvailabilityService availability) { this.availability=availability; }
    @GetMapping Object find(HttpServletRequest req,@RequestParam("room_id") long roomId,@RequestParam String date,@RequestParam(required=false) String strategy) { return Api.ok(availability.find(WebSecurity.uid(req),roomId,date,strategy==null?"earliest":strategy)); }
}
