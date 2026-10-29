package cn.studyroom;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Vue history 路由刷新时返回前端入口，不接管 API 或静态资源错误。 */
@Controller
public class SpaController {
    @GetMapping({"/","/login","/booking","/mine","/waitlist","/analytics","/notifications","/profile","/admin"})
    String page() { return "forward:/index.html"; }
}
