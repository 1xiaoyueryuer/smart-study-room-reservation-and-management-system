package cn.studyroom;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.*;
import static cn.studyroom.Api.ok;
import static cn.studyroom.WebSecurity.uid;

@RestController
public class OpeningScheduleController {
    private final OpeningScheduleService opening;
    public OpeningScheduleController(OpeningScheduleService opening) { this.opening=opening; }
    @GetMapping("/api/rooms/{id}/opening-windows") Object day(@PathVariable long id,@RequestParam String date,@RequestParam(name="seat_id",required=false) Long seatId) { return ok(opening.publicDay(id,date,seatId)); }
    @GetMapping("/api/admin/rooms/{id}/opening-windows") Object management(@PathVariable long id) { return ok(opening.management(id)); }
    @PutMapping("/api/admin/rooms/{id}/opening-windows") Object save(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody Requests.OpeningChange body) { return ok(opening.save(uid(req),id,body)); }
    @DeleteMapping("/api/admin/rooms/{id}/opening-windows") Object delete(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody Requests.OpeningDelete body) { return ok(opening.deleteException(uid(req),id,body)); }
    @GetMapping("/api/admin/rooms/{id}/unavailability") Object blocks(@PathVariable long id) { return ok(opening.blocks(id)); }
    @PostMapping("/api/admin/rooms/{id}/unavailability") Object createBlock(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody Requests.Unavailability body) { return ok(opening.saveBlock(uid(req),id,null,body)); }
    @PutMapping("/api/admin/rooms/{id}/unavailability/{blockId}") Object editBlock(HttpServletRequest req,@PathVariable long id,@PathVariable long blockId,@Valid @RequestBody Requests.Unavailability body) { return ok(opening.saveBlock(uid(req),id,blockId,body)); }
    @DeleteMapping("/api/admin/rooms/{id}/unavailability/{blockId}") Object cancelBlock(HttpServletRequest req,@PathVariable long id,@PathVariable long blockId,@Valid @RequestBody Requests.ResourceDelete body) { return ok(opening.cancelBlock(uid(req),id,blockId,body)); }
}
