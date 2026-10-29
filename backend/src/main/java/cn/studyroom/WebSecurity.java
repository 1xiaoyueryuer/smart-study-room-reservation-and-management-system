package cn.studyroom;

import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebSecurity implements WebMvcConfigurer {
    private final AuthService auth;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    public WebSecurity(AuthService auth,StringRedisTemplate redis,ObjectMapper json) { this.auth=auth;this.redis=redis;this.json=json; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest req,HttpServletResponse resp,Object handler) throws Exception {
                try {
                    String path=req.getRequestURI();
                    if("OPTIONS".equals(req.getMethod())||path.equals("/api/health")) return true;
                    if(path.equals("/api/auth/login")||path.equals("/api/auth/register")) {
                        rate("login:"+req.getRemoteAddr(),10,60000); return true;
                    }
                    String header=req.getHeader("Authorization");
                    Api.require(header!=null&&header.startsWith("Bearer "),401,"请先登录");
                    String token=header.substring(7);
                    Map<String,Object> user=auth.authenticate(token); req.setAttribute("user",user);req.setAttribute("token",token);
                    if(path.startsWith("/api/admin/")) Api.require("admin".equals(user.get("role")),403,"需要管理员权限");
                    if("POST".equals(req.getMethod())&&(path.equals("/api/reservations")||path.equals("/api/reservations/auto")))
                        rate("reserve:"+user.get("id"),2,1000);
                    return true;
                } catch(Api.Failure e) { write(resp,e.status,e.getMessage());return false; }
                catch(org.springframework.data.redis.RedisConnectionFailureException e) { write(resp,503,"Redis 暂时不可用，请检查服务");return false; }
            }
        }).addPathPatterns("/api/**");
    }
    private void rate(String key,int max,long millis) {
        String lua="local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('PEXPIRE',KEYS[1],ARGV[1]) end; return n";
        Long n=redis.execute(new DefaultRedisScript<>(lua,Long.class),List.of("studyroom-java:rate:"+key),""+millis);
        Api.require(n!=null&&n<=max,429,"操作过于频繁，请稍后重试");
    }
    private void write(HttpServletResponse resp,int code,String message) throws Exception {
        resp.setStatus(code);resp.setContentType("application/json;charset=UTF-8");
        json.writeValue(resp.getWriter(),Map.of("code",code,"message",message));
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> user(HttpServletRequest req) { return (Map<String,Object>)req.getAttribute("user"); }
    public static long uid(HttpServletRequest req) { return Store.id(user(req),"id"); }
}
