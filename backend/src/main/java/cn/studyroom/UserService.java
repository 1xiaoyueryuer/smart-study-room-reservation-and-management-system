package cn.studyroom;

import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.validation.Validator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 校园账号维护的唯一写源；student_no仅作旧数据溯源。 */
@Service
@Transactional
public class UserService {
    private final Store db;
    private final AuthService auth;
    private final AuditService audit;
    private final Validator validator;
    private final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder();
    private static final Set<String> CONTACT_FIELDS=Set.of("phone","email");
    private static final List<String> AUDIT_FIELDS=List.of("username","real_name","campus_id","role","status","phone","email");
    public UserService(Store db,AuthService auth,AuditService audit,Validator validator) {
        this.db=db;this.auth=auth;this.audit=audit;this.validator=validator;
    }
    public List<Map<String,Object>> list(long actor) {
        requireAdmin(actor);
        return db.list("SELECT id,username,real_name,campus_id,phone,email,role,credit_score,credit_banned_until,status,created_at FROM users ORDER BY id");
    }
    public Map<String,Object> save(long actor,Long id,Requests.UserChange body) {
        db.lockBookings();requireAdmin(actor);validate(body);
        var before=id==null?Map.<String,Object>of():auth.profile(id);
        String campus=AuthService.blankNull(body.campusId());
        Api.require(id!=null||campus!=null,400,"新账号必须提供校园编号");
        Api.require(id==null||before.get("campus_id")==null||campus!=null,400,"已知校园编号不能清空");
        Api.require(id==null||!Objects.equals(actor,id)||("admin".equals(body.role())&&"active".equals(body.status())),400,"不能禁用或降级当前登录管理员");
        String password=body.password();
        boolean reset=password!=null&&!password.isEmpty();
        Api.require(id!=null||reset,400,"新账号必须提供初始口令");
        if(reset) Api.require(!password.isBlank()&&password.length()>=6&&password.length()<=64&&password.getBytes(StandardCharsets.UTF_8).length<=72,400,"口令须为6至64个字符且UTF-8长度不超过72字节");
        String phone=AuthService.blankNull(body.phone()),email=AuthService.blankNull(body.email());
        long target;
        if(id==null) {
            target=db.insert("INSERT INTO users(username,password_hash,real_name,campus_id,role,status,phone,email) VALUES(?,?,?,?,?,?,?,?)",
                body.username().trim(),passwords.encode(password),body.realName().trim(),campus,body.role(),body.status(),phone,email);
        } else {
            target=id;
            db.update("UPDATE users SET username=?,real_name=?,campus_id=?,role=?,status=?,phone=?,email=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                body.username().trim(),body.realName().trim(),campus,body.role(),body.status(),phone,email,target);
            if(reset) db.update("UPDATE users SET password_hash=? WHERE id=?",passwords.encode(password),target);
        }
        var after=auth.profile(target);
        var changes=snapshot(after);
        if(reset) changes.put("credentials_reset",true); // 只记录操作事实，不记录口令或hash。
        audit.record(actor,id==null?"user.create":"user.update","user",Long.toString(target),body.reason().trim(),snapshot(before),changes);
        return after;
    }
    public Map<String,Object> setStatus(long actor,long id,Requests.Status body) {
        db.lockBookings();requireAdmin(actor);validate(body);
        var user=auth.profile(id);
        return save(actor,id,new Requests.UserChange(Store.text(user,"username"),Store.text(user,"real_name"),
            (String)user.get("campus_id"),Store.text(user,"role"),body.status(),(String)user.get("phone"),(String)user.get("email"),null,body.reason()));
    }
    public Map<String,Object> updateContacts(long uid,Map<String,Object> patch) {
        Api.require(!patch.isEmpty()&&CONTACT_FIELDS.containsAll(patch.keySet()),400,"本人资料仅允许phone和email字段");
        for(Object value:patch.values()) Api.require(value==null||value instanceof String,400,"联系方式须为文本或null");
        db.lockBookings();
        var before=auth.profile(uid);
        String phone=patch.containsKey("phone")?AuthService.blankNull((String)patch.get("phone")):(String)before.get("phone");
        String email=patch.containsKey("email")?AuthService.blankNull((String)patch.get("email")):(String)before.get("email");
        validate(new Requests.Contacts(phone,email));
        db.update("UPDATE users SET phone=?,email=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",phone,email,uid);
        var after=auth.profile(uid);
        // 本人维护无管理理由输入；记录真实操作类型，与管理提供的理由分开。
        audit.record(uid,"user.contacts.update","user",Long.toString(uid),"本人更新联系方式",snapshot(before),snapshot(after));
        return after;
    }
    private void requireAdmin(long actor) { Api.require("admin".equals(auth.profile(actor).get("role")),403,"需要管理员权限"); }
    private void validate(Object value) {
        var errors=validator.validate(value);
        Api.require(errors.isEmpty(),400,errors.stream().map(x->x.getPropertyPath()+": "+x.getMessage()).sorted().findFirst().orElse("参数错误"));
    }
    private static Map<String,Object> snapshot(Map<String,Object> user) {
        var values=new LinkedHashMap<String,Object>();
        for(String field:AUDIT_FIELDS) if(user.containsKey(field)) values.put(field,user.get(field));
        return values;
    }
}
