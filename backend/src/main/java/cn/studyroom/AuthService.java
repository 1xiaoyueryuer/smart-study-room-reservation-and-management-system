package cn.studyroom;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final Store db;
    private final StringRedisTemplate redis;
    private final Algorithm algorithm;
    private final Clock clock;
    private final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder();
    public AuthService(Store db,StringRedisTemplate redis,@Value("${studyroom.jwt-secret}") String secret,Clock clock) {
        Api.require(secret.length()>=32,500,"STUDYROOM_JWT_SECRET 至少需要32个字符");
        this.db=db;this.redis=redis;this.algorithm=Algorithm.HMAC256(secret);this.clock=clock;
    }
    public Map<String,Object> register(Requests.Register req) {
        throw Api.fail(403,"校园账号由管理员维护，不开放公共注册");
    }
    public Map<String,Object> login(Requests.Login req) {
        var users=db.list("SELECT * FROM users WHERE username=?",req.username());
        Api.require(!users.isEmpty() && passwords.matches(req.password(),Store.text(users.get(0),"password_hash")),401,"用户名或口令错误");
        var user=users.get(0); Api.require(isOrdinary(user.get("role"))||"admin".equals(user.get("role")),403,"账号角色无效"); Api.require("active".equals(user.get("status")),403,"账号已被禁用");
        Instant expires=clock.instant().plus(Duration.ofDays(7));
        String token=JWT.create().withIssuer("studyroom-java").withSubject(""+Store.id(user,"id"))
                .withJWTId(UUID.randomUUID().toString()).withIssuedAt(Date.from(clock.instant())).withExpiresAt(Date.from(expires)).sign(algorithm);
        return Map.of("token",token,"expires_at",expires.toString(),"user",profile(Store.id(user,"id")));
    }
    public Map<String,Object> authenticate(String token) {
        try {
            var jwt=JWT.require(algorithm).withIssuer("studyroom-java").build().verify(token);
            Api.require(!Boolean.TRUE.equals(redis.hasKey(revokedKey(token))),401,"登录已注销");
            var user=profile(Long.parseLong(jwt.getSubject()));
            Api.require("active".equals(user.get("status")),401,"账号已被禁用");
            return user;
        } catch(Api.Failure e) { throw e; }
        catch(com.auth0.jwt.exceptions.JWTVerificationException|NumberFormatException e) { throw Api.fail(401,"登录已过期或令牌无效"); }
    }
    public void logout(String token) {
        var jwt=JWT.require(algorithm).withIssuer("studyroom-java").build().verify(token);
        Duration ttl=Duration.between(clock.instant(),jwt.getExpiresAt().toInstant());
        if(!ttl.isNegative()&&!ttl.isZero()) redis.opsForValue().set(revokedKey(token),"1",ttl);
    }
    public Map<String,Object> profile(long id) {
        return db.one("SELECT id,username,real_name,student_no,role,credit_score,credit_banned_until,status,created_at FROM users WHERE id=?",id);
    }
    public static boolean isOrdinary(Object role) { return "student".equals(role)||"staff".equals(role); }
    static String blankNull(String s) { return s==null||s.isBlank()?null:s.trim(); }
    private String revokedKey(String token) {
        try { return "studyroom-java:revoked:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
