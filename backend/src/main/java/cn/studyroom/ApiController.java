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
    private final AuthService auth;
    private final ResourceService resources;
    private final BookingService bookings;
    private final StatsService stats;
    private final Store db;
    private final UserService users;
    private final StringRedisTemplate redis;
    public ApiController(AuthService auth,ResourceService resources,BookingService bookings,StatsService stats,Store db,StringRedisTemplate redis,UserService users) {
        this.auth=auth;this.resources=resources;this.bookings=bookings;this.stats=stats;this.db=db;this.redis=redis;this.users=users;
    }
    @GetMapping("/health") Object health() {
        db.count("SELECT count(*) FROM booking_mutex");redis.hasKey("studyroom-java:health");
        return ok(Map.of("status","healthy","backend","java","database","PostgreSQL"));
    }
    @PostMapping("/auth/login") Object login(@Valid @RequestBody Requests.Login req) { return ok(auth.login(req)); }
    @PostMapping("/auth/register") Object register(@Valid @RequestBody Requests.Register req) { return ok(auth.register(req)); }
    @PostMapping("/auth/logout") Object logout(HttpServletRequest req) { auth.logout((String)req.getAttribute("token"));return ok(Map.of("logged_out",true)); }
    @GetMapping("/auth/profile") Object profile(HttpServletRequest req) { return ok(auth.profile(uid(req))); }
    @PatchMapping("/auth/profile") Object updateProfile(HttpServletRequest req,@RequestBody Map<String,Object> body) { return ok(users.updateContacts(uid(req),body)); }
    @GetMapping("/rooms") Object rooms() { return ok(resources.rooms()); }
    @GetMapping("/rooms/{id}/seats") Object seats(@PathVariable long id,@RequestParam String date,@RequestParam String start,@RequestParam String end) { return ok(resources.seatMap(id,date,start,end)); }
    @PostMapping("/reservations") Object create(HttpServletRequest req,@Valid @RequestBody Requests.Reservation body) { return ok(bookings.create(uid(req),body)); }
    @PostMapping("/reservations/auto") Object allocate(HttpServletRequest req,@Valid @RequestBody Requests.Allocation body) { return ok(bookings.allocate(uid(req),body)); }
    @GetMapping("/reservations/mine") Object mine(HttpServletRequest req) { return ok(bookings.mine(uid(req))); }
    @PostMapping("/reservations/{id}/{action}") Object action(HttpServletRequest req,@PathVariable long id,@PathVariable String action) {
        bookings.action(uid(req),id,action);
        return ok(action.equals("cancel")?Map.of("cancelled",id):Map.of("id",id,"action",switch(action){case "checkin","return"->"checked_in";case "leave"->"temp_leave";case "checkout"->"completed";default->action;}));
    }
    @GetMapping("/credit") Object credit(HttpServletRequest req) {
        var user=auth.profile(uid(req));var result=new java.util.LinkedHashMap<String,Object>();result.put("score",user.get("credit_score"));result.put("banned_until",user.get("credit_banned_until"));
        result.put("logs",db.list("SELECT * FROM credit_logs WHERE user_id=? ORDER BY id DESC LIMIT 50",uid(req)));return ok(result);
    }
    @GetMapping("/notifications") Object notifications(HttpServletRequest req) { return ok(db.list("SELECT * FROM notifications WHERE user_id=? ORDER BY id DESC LIMIT 200",uid(req))); }
    @GetMapping("/notifications/unread_count") Object unread(HttpServletRequest req) { return ok(Map.of("count",db.count("SELECT count(*) FROM notifications WHERE user_id=? AND is_read=FALSE",uid(req)))); }
    @PostMapping("/notifications/{id}/read") Object read(HttpServletRequest req,@PathVariable long id) {
        var row=db.one("SELECT user_id FROM notifications WHERE id=?",id);Api.require(Store.id(row,"user_id")==uid(req),403,"无权访问他人消息");
        db.update("UPDATE notifications SET is_read=TRUE WHERE id=? AND user_id=?",id,uid(req));return ok(Map.of("read",id));
    }
    @PostMapping("/notifications/read_all") Object readAll(HttpServletRequest req) { db.update("UPDATE notifications SET is_read=TRUE WHERE user_id=?",uid(req));return ok(Map.of("read_all",true)); }
    @PostMapping("/waitlist") Object join(HttpServletRequest req,@Valid @RequestBody Requests.Wait body) { return ok(bookings.join(uid(req),body)); }
    @GetMapping("/waitlist/mine") Object waitMine(HttpServletRequest req) { return ok(bookings.waitMine(uid(req))); }
    @PostMapping("/waitlist/{id}/cancel") Object cancelWait(HttpServletRequest req,@PathVariable long id) { bookings.cancelWait(uid(req),id);return ok(Map.of("cancelled",id)); }
    @GetMapping("/stats/overview") Object overview() { return ok(stats.overview()); }
    @GetMapping("/stats/heatmap") Object heatmap(@RequestParam("room_id") long roomId,@RequestParam(required=false) String date) { return ok(stats.heatmap(roomId,date)); }
    @GetMapping("/stats/trend") Object trend(@RequestParam(defaultValue="14") int days) { return ok(stats.trend(days)); }
    @GetMapping("/stats/peak") Object peak(@RequestParam(defaultValue="14") int days) { return ok(stats.peak(days)); }
    @GetMapping("/stats/top-seats") Object top(@RequestParam(defaultValue="14") int days,@RequestParam(defaultValue="10") int limit) { return ok(stats.top(days,limit)); }
    @GetMapping("/admin/users") Object users(HttpServletRequest req) { return ok(users.list(uid(req))); }
    @PostMapping("/admin/users") Object createUser(HttpServletRequest req,@Valid @RequestBody Requests.UserChange body) { return ok(users.save(uid(req),null,body)); }
    @PutMapping("/admin/users/{id}") Object updateUser(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody Requests.UserChange body) { return ok(users.save(uid(req),id,body)); }
    @PutMapping("/admin/users/{id}/status") Object status(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody Requests.Status body) { return ok(users.setStatus(uid(req),id,body)); }
}
