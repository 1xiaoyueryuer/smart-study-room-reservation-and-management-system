package cn.studyroom;

import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;
import static cn.studyroom.Api.ok;
import static cn.studyroom.WebSecurity.uid;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final NotificationService messages;
    private final AuthService auth;
    private final ResourceService resources;
    private final BookingService bookings;
    private final StatsService stats;
    private final Store db;
    private final UserService users;
    private final BookingPolicy policy;
    private final StringRedisTemplate redis;
    public ApiController(AuthService auth,ResourceService resources,BookingService bookings,StatsService stats,Store db,StringRedisTemplate redis,UserService users,BookingPolicy policy,NotificationService messages) {this.messages=messages;
        this.auth=auth;this.resources=resources;this.bookings=bookings;this.stats=stats;this.db=db;this.redis=redis;this.users=users;this.policy=policy;
    }
    @GetMapping("/health") Object health() {
        db.count("SELECT count(*) FROM booking_mutex");redis.hasKey("studyroom-java:health");
        return ok(Map.of("status","healthy","backend","java","database","PostgreSQL","database_name",db.one("SELECT current_database() AS name").get("name")));
    }
    @PostMapping("/auth/login") Object login(@Valid @RequestBody Requests.Login req) { return ok(auth.login(req)); }
    @PostMapping("/auth/register") Object register(@Valid @RequestBody Requests.Register req) { return ok(auth.register(req)); }
    @PostMapping("/auth/logout") Object logout(HttpServletRequest req) { auth.logout((String)req.getAttribute("token"));return ok(Map.of("logged_out",true)); }
    @GetMapping("/auth/profile") Object profile(HttpServletRequest req) { return ok(auth.profile(uid(req))); }
    @PatchMapping("/auth/profile") Object updateProfile(HttpServletRequest req,@RequestBody Map<String,Object> body) { return ok(users.updateContacts(uid(req),body)); }
    @PatchMapping("/auth/password") Object changePassword(HttpServletRequest req,@Valid @RequestBody Requests.ChangePassword body) { return ok(users.changePassword(uid(req),body)); }
    @GetMapping("/booking-policy") Object bookingPolicy() { return ok(policy.view()); }
    @GetMapping("/rooms") Object rooms() { return ok(resources.rooms()); }
    @GetMapping("/rooms/{id}/seats") Object seats(@PathVariable long id,@RequestParam String date,@RequestParam String start,@RequestParam String end) { return ok(resources.seatMap(id,date,start,end)); }
    @PostMapping("/reservations") Object create(HttpServletRequest req,@Valid @RequestBody Requests.Reservation body) { return actionResponse(bookings.createForClient(uid(req),body)); }
    @PostMapping("/reservations/auto") Object allocate(HttpServletRequest req,@Valid @RequestBody Requests.Allocation body) { return ok(bookings.allocate(uid(req),body)); }
    @GetMapping("/reservations/mine") Object mine(HttpServletRequest req,@RequestParam Map<String,String> parameters) { return ok(bookings.records(uid(req),parameters,false)); }
    @GetMapping("/reservations/{id}") Object detail(HttpServletRequest req,@PathVariable long id) { return ok(bookings.detail(uid(req),id)); }
    @PostMapping("/reservations/{id}/checkin") Object checkin(HttpServletRequest req,@PathVariable long id,@RequestBody Map<String,Object> body) {
        Api.require(body.keySet().equals(java.util.Set.of("seat_code"))&&body.get("seat_code") instanceof String,400,"签到仅接受座位码 seat_code");
        return actionResponse(bookings.checkin(uid(req),id,(String)body.get("seat_code")));
    }
    @PostMapping("/reservations/{id}/{action}") Object action(HttpServletRequest req,@PathVariable long id,@PathVariable String action) {
        return actionResponse(bookings.action(uid(req),id,action));
    }
    private Object actionResponse(BookingService.ActionResult result) {
        // Service代理已成功提交事务，才把已落实deadline的冲突映为HTTP错误。
        if(result.status()!=200) throw Api.fail(result.status(),result.message(),result.details());
        return ok(result.data());
    }
    @GetMapping("/violations/mine") Object violations(HttpServletRequest req,@RequestParam Map<String,String> parameters) { return ok(bookings.violationRecords(uid(req),parameters,false)); }
    @GetMapping("/violations/{id}") Object violationDetail(HttpServletRequest req,@PathVariable long id){return ok(bookings.violationDetail(uid(req),id));}
    @GetMapping("/admin/violations") Object adminViolations(HttpServletRequest req,@RequestParam Map<String,String> parameters){return ok(bookings.violationRecords(uid(req),parameters,true));}
    @GetMapping("/admin/reservations") Object adminReservations(HttpServletRequest req,@RequestParam Map<String,String> parameters){return ok(bookings.records(uid(req),parameters,true));}
    @GetMapping("/admin/reservations/{id}") Object adminReservation(HttpServletRequest req,@PathVariable long id){return ok(bookings.correctionDetail(uid(req),id));}
    @GetMapping("/credit") Object credit(HttpServletRequest req) { return ok(bookings.legacyCredit(uid(req))); }
    @GetMapping("/notifications") Object notifications(HttpServletRequest req,@RequestParam Map<String,String> parameters){return ok(messages.list(uid(req),parameters));}
    @GetMapping("/notifications/unread_count") Object unread(HttpServletRequest req){return ok(messages.unread(uid(req)));}
    @PostMapping("/notifications/{id}/read") Object read(HttpServletRequest req,@PathVariable long id){return ok(messages.read(uid(req),id));}
    @PostMapping("/notifications/read_all") Object readAll(HttpServletRequest req){return ok(messages.readAll(uid(req)));}
    @PostMapping("/waitlist") Object join(HttpServletRequest req,@Valid @RequestBody Requests.Wait body) { return ok(bookings.join(uid(req),body)); }
    @GetMapping("/waitlist/mine") Object waitMine(HttpServletRequest req) { return ok(bookings.waitMine(uid(req))); }
    @PostMapping("/waitlist/{id}/cancel") Object cancelWait(HttpServletRequest req,@PathVariable long id) { bookings.cancelWait(uid(req),id);return ok(Map.of("cancelled",id)); }
    @GetMapping("/stats/overview") Object overview(HttpServletRequest req,@RequestParam Map<String,String> parameters) { return ok(stats.overview(uid(req),parameters)); }
    @GetMapping("/stats/heatmap") Object heatmap(HttpServletRequest req,@RequestParam Map<String,String> parameters) { return ok(stats.heatmap(uid(req),parameters)); }
    @GetMapping("/admin/users") Object users(HttpServletRequest req) { return ok(users.list(uid(req))); }
    @PostMapping("/admin/users") Object createUser(HttpServletRequest req,@Valid @RequestBody Requests.UserChange body) { return ok(users.save(uid(req),null,body)); }
    @PutMapping("/admin/users/{id}") Object updateUser(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody Requests.UserChange body) { return ok(users.save(uid(req),id,body)); }
    @PutMapping("/admin/users/{id}/status") Object status(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody Requests.Status body) { return ok(users.setStatus(uid(req),id,body)); }
}
