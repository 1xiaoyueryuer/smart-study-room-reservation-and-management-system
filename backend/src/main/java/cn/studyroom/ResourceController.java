package cn.studyroom;

import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.*;
import static cn.studyroom.Api.ok;
import static cn.studyroom.WebSecurity.uid;

@RestController
@RequestMapping("/api/admin")
public class ResourceController {
    private final ResourceService resources;
    public ResourceController(ResourceService resources) { this.resources=resources; }
    @PostMapping("/rooms") Object createRoom(HttpServletRequest req,@Valid @RequestBody Requests.Room body) { return ok(resources.save(uid(req),null,body)); }
    @PutMapping("/rooms/{id}") Object updateRoom(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody Requests.Room body) { return ok(resources.save(uid(req),id,body)); }
    @DeleteMapping("/rooms/{id}") Object deleteRoom(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody Requests.ResourceDelete body) { resources.deleteRoom(uid(req),id,body);return ok(Map.of("deleted",id)); }
    @GetMapping("/rooms/{id}/seats") Object seats(@PathVariable long id) { return ok(resources.managementSeats(id)); }
    @PostMapping("/rooms/{id}/seats/batch") Object batch(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody Requests.Batch body) { int created=resources.batch(uid(req),id,body);return ok(Map.of("room_id",id,"created",created,"room",resources.room(id))); }
    @PostMapping("/rooms/{id}/seats") Object createSeat(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody Requests.Seat body) { return ok(resources.createSeat(uid(req),id,body)); }
    @PutMapping("/seats/{id}") Object updateSeat(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody Requests.Seat body) { return ok(resources.updateSeat(uid(req),id,body)); }
    @DeleteMapping("/seats/{id}") Object deleteSeat(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody Requests.ResourceDelete body) { resources.deleteSeat(uid(req),id,body);return ok(Map.of("deleted",id)); }
}
