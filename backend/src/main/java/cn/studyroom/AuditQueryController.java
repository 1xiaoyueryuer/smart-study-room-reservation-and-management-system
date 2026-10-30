package cn.studyroom;

import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/audit-events")
public class AuditQueryController {
    private final AuditQueryService queries;
    public AuditQueryController(AuditQueryService queries){this.queries=queries;}
    @GetMapping Object list(HttpServletRequest req,@RequestParam Map<String,String> parameters){return Api.ok(queries.list(WebSecurity.uid(req),parameters));}
    @GetMapping("/{id}") Object detail(HttpServletRequest req,@PathVariable long id){return Api.ok(queries.detail(WebSecurity.uid(req),id));}
}
