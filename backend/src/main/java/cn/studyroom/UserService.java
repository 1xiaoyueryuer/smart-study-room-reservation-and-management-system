package cn.studyroom;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
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
    private final Clock clock;
    private final BCryptPasswordEncoder passwords=new BCryptPasswordEncoder();
    private static final Set<String> CONTACT_FIELDS=Set.of("phone","email");
    private static final List<String> AUDIT_FIELDS=List.of("username","real_name","campus_id","role","status","phone","email");
    public UserService(Store db,AuthService auth,AuditService audit,Validator validator,Clock clock) {
        this.db=db;this.auth=auth;this.audit=audit;this.validator=validator;this.clock=clock;
    }
    public List<Map<String,Object>> list(long actor) {
        requireAdmin(actor);
        return db.list("SELECT id,username,real_name,campus_id,phone,email,role,credit_score,credit_banned_until,status,created_at FROM users ORDER BY id");
    }
    public Map<String,Object> save(long actor,Long id,Requests.UserChange body) {
        db.lockBookings();requireAdmin(actor);validate(body);
        var before=id==null?Map.<String,Object>of():auth.profile(id);
        String campus=AuthService.blankNull(body.campusId());
        String login=body.username().trim();
        boolean unchangedLegacyIdentity=id!=null&&Objects.equals(campus,before.get("campus_id"))
            &&Objects.equals(login,before.get("username"))&&Objects.equals(body.role(),before.get("role"));
        if("student".equals(body.role())||"staff".equals(body.role())) {
            if(!unchangedLegacyIdentity) {
                String identityError=CampusIdentity.validationError(campus,body.role(),clock.instant().atZone(BookingPolicy.CAMPUS_ZONE).getYear());
                Api.require(identityError==null,400,identityError);
                Api.require(login.equals(campus),400,"登录名必须与9位校园编号一致");
            }
        } else {
            Api.require(campus==null||campus.length()<=20,400,"管理员校园编号不能超过20个字符");
            Api.require(login.length()>=3&&login.length()<=50,400,"管理员登录名须为3—50个字符");
        }
        Api.require(id==null||!Objects.equals(actor,id)||("admin".equals(body.role())&&"active".equals(body.status())),400,"不能禁用或降级当前登录管理员");
        String password=body.password();
        boolean reset=password!=null&&!password.isEmpty();
        Api.require(id!=null||reset,400,"新账号必须提供初始口令");
        if(reset) validateInitialPassword(password);
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
    public Map<String,Object> changePassword(long uid,Requests.ChangePassword body) {
        db.lockBookings();
        var current=db.one("SELECT password_hash,status FROM users WHERE id=?",uid);
        Api.require("active".equals(current.get("status")),403,"账号当前不可修改口令");
        Api.require(passwords.matches(body.currentPassword(),Store.text(current,"password_hash")),400,"当前口令不正确");
        validateInitialPassword(body.newPassword());
        Api.require(!body.currentPassword().equals(body.newPassword()),400,"新口令不能与当前口令相同");
        db.update("UPDATE users SET password_hash=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",passwords.encode(body.newPassword()),uid);
        var after=auth.profile(uid);
        audit.record(uid,"user.password.change","user",Long.toString(uid),"本人修改登录口令",Map.of(),Map.of("changed",true));
        return after;
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.SUPPORTS)
    public void validateInitialPassword(String password) {
        Api.require(password!=null&&!password.isBlank()&&password.length()>=6&&password.length()<=64&&password.getBytes(StandardCharsets.UTF_8).length<=72,400,"口令须为6至64个字符且UTF-8长度不超过72字节");
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.SUPPORTS)
    public List<String> creationErrors(Requests.UserChange value) {
        var errors=new ArrayList<String>();
        validator.validate(value).stream().map(x->x.getPropertyPath()+": "+x.getMessage()).sorted().forEach(errors::add);
        String campus=AuthService.blankNull(value.campusId());
        if("student".equals(value.role())||"staff".equals(value.role())) {
            String identityError=CampusIdentity.validationError(campus,value.role(),clock.instant().atZone(BookingPolicy.CAMPUS_ZONE).getYear());
            if(identityError!=null) errors.add("campus_id: "+identityError);
            if(value.username()!=null&&!value.username().trim().equals(campus)) errors.add("username: 登录名必须与9位校园编号一致");
        }
        return errors;
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
