package cn.studyroom;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/seats/{id}/code")
public class SeatCodeController {
    private final SeatCodeService codes;
    public SeatCodeController(SeatCodeService codes) { this.codes=codes; }
    @GetMapping Object metadata(HttpServletRequest req,@PathVariable long id) { return Api.ok(codes.metadata(WebSecurity.uid(req),id)); }
    @PostMapping Object generate(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody Requests.SeatCodeChange body) { return Api.ok(codes.generate(WebSecurity.uid(req),id,body)); }
    @PostMapping("/rotate") Object rotate(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody Requests.SeatCodeChange body) { return Api.ok(codes.rotate(WebSecurity.uid(req),id,body)); }
    @PatchMapping Object status(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody Requests.SeatCodeStatus body) { return Api.ok(codes.setEnabled(WebSecurity.uid(req),id,body)); }
    @GetMapping("/png") ResponseEntity<byte[]> png(HttpServletRequest req,@PathVariable long id,@RequestParam(name="expected_version",required=false) Long expectedVersion) { return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore()).body(codes.png(WebSecurity.uid(req),id,expectedVersion)); }
}
