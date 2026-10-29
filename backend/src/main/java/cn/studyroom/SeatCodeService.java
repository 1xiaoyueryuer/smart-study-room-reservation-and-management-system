package cn.studyroom;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.QRCodeWriter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 每座当前操作码的唯一写源；资源与预约核心均不反向依赖。 */
@Service
@Transactional
public class SeatCodeService {
    static final String PREFIX="studyroom-seat:v1:";
    private final Store db;
    private final AuthService auth;
    private final AuditService audit;
    private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    public SeatCodeService(Store db,AuthService auth,AuditService audit,Clock clock) { this.db=db;this.auth=auth;this.audit=audit;this.clock=clock; }
    @Transactional(readOnly=true)
    public Map<String,Object> metadata(long actor,long seat) { requireAdmin(actor);requireSeat(seat);return view(seat,current(seat)); }
    public Map<String,Object> generate(long actor,long seat,Requests.SeatCodeChange body) {
        db.lockBookings();requireAdmin(actor);requireSeat(seat);String reason=reason(body==null?null:body.reason());
        Api.require(body!=null&&body.version()!=null&&body.version()==0,409,"初次生成的码版本必须为0，请刷新");
        var old=current(seat);Api.require(old.isEmpty(),409,"座位码已经生成，请使用轮换");
        String code=newCode();Timestamp now=Timestamp.from(clock.instant());
        db.update("INSERT INTO seat_codes(seat_id,code_value,enabled,version,created_at,updated_at) VALUES(?,?,TRUE,1,?,?)",seat,code,now,now);
        var after=view(seat,current(seat));audit.record(actor,"seat_code.generate","seat",Long.toString(seat),redact(reason),view(seat,old),after);return after;
    }
    public Map<String,Object> rotate(long actor,long seat,Requests.SeatCodeChange body) {
        db.lockBookings();requireAdmin(actor);requireSeat(seat);String reason=reason(body==null?null:body.reason());var old=required(seat);version(old,body==null?null:body.version());
        db.update("UPDATE seat_codes SET code_value=?,version=version+1,updated_at=? WHERE seat_id=?",newCode(),Timestamp.from(clock.instant()),seat);
        var after=view(seat,current(seat));audit.record(actor,"seat_code.rotate","seat",Long.toString(seat),redact(reason),view(seat,old),after);return after;
    }
    public Map<String,Object> setEnabled(long actor,long seat,Requests.SeatCodeStatus body) {
        db.lockBookings();requireAdmin(actor);requireSeat(seat);String reason=reason(body==null?null:body.reason());var old=required(seat);version(old,body==null?null:body.version());
        Api.require(body.enabled()!=null,400,"必须指定座位码启停状态");Api.require(!body.enabled().equals(old.get("enabled")),409,"座位码已处于该状态");
        db.update("UPDATE seat_codes SET enabled=?,version=version+1,updated_at=? WHERE seat_id=?",body.enabled(),Timestamp.from(clock.instant()),seat);
        var after=view(seat,current(seat));audit.record(actor,"seat_code.status","seat",Long.toString(seat),redact(reason),view(seat,old),after);return after;
    }
    @Transactional(readOnly=true)
    public byte[] png(long actor,long seat) { return png(actor,seat,null); }
    @Transactional(readOnly=true)
    public byte[] png(long actor,long seat,Long expectedVersion) {
        requireAdmin(actor);requireSeat(seat);var row=required(seat);if(expectedVersion!=null) version(row,expectedVersion);
        String code=Store.text(row,"code_value");
        try {
            var matrix=new QRCodeWriter().encode(code,BarcodeFormat.QR_CODE,384,384,Map.of(EncodeHintType.MARGIN,4,EncodeHintType.CHARACTER_SET,"UTF-8"));
            var image=new BufferedImage(matrix.getWidth(),matrix.getHeight(),BufferedImage.TYPE_INT_RGB);
            for(int y=0;y<matrix.getHeight();y++) for(int x=0;x<matrix.getWidth();x++) image.setRGB(x,y,matrix.get(x,y)?0x000000:0xffffff);
            var output=new ByteArrayOutputStream();if(!ImageIO.write(image,"PNG",output)) throw new IllegalStateException("PNG编码器不可用");return output.toByteArray();
        } catch(WriterException|IOException e) { throw new IllegalStateException("座位码图片生成失败",e); }
    }
    /** 调用方先锁 booking_mutex，再验证；轮换和签到不能交错。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void requireValid(long seat,String payload) {
        Api.require(payload!=null&&payload.matches("studyroom-seat:v1:[A-Za-z0-9_-]{43}"),400,"座位码格式错误，请选择真实座位码图片");
        var row=current(seat);String actual=Store.text(row,"code_value");
        Api.require(!row.isEmpty()&&Boolean.TRUE.equals(row.get("enabled"))&&MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8),payload.getBytes(StandardCharsets.UTF_8)),409,"码已失效或不属于本预约座位");
    }
    private Map<String,Object> current(long seat) { var rows=db.list("SELECT * FROM seat_codes WHERE seat_id=?",seat);return rows.isEmpty()?Map.of():rows.get(0); }
    private Map<String,Object> required(long seat) { var row=current(seat);Api.require(!row.isEmpty(),409,"该座位尚未生成座位码");return row; }
    private void requireSeat(long seat) { db.one("SELECT id FROM seats WHERE id=?",seat); }
    private void requireAdmin(long actor) { var user=auth.profile(actor);Api.require("admin".equals(user.get("role"))&&"active".equals(user.get("status")),403,"需要管理员权限"); }
    private void version(Map<String,Object> row,Long expected) { Api.require(expected!=null&&expected>0,400,"必须提供座位码版本");Api.require(Store.id(row,"version")==expected,409,"座位码已变更，请刷新后重试"); }
    private String reason(String reason) { Api.require(reason!=null&&!reason.isBlank()&&reason.trim().length()<=1000,400,"请填写1000字以内的操作理由");return reason.trim(); }
    private String redact(String reason) { return reason.replaceAll("studyroom-seat:v1:[A-Za-z0-9_-]+","[座位码已脱敏]").replaceAll("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{43}(?![A-Za-z0-9_-])","[座位码已脱敏]"); }
    private String newCode() { byte[] bytes=new byte[32];random.nextBytes(bytes);return PREFIX+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private Map<String,Object> view(long seat,Map<String,Object> row) {
        var view=new LinkedHashMap<String,Object>();view.put("seat_id",seat);view.put("configured",!row.isEmpty());view.put("enabled",Boolean.TRUE.equals(row.get("enabled")));view.put("version",row.isEmpty()?0L:row.get("version"));view.put("created_at",row.get("created_at"));view.put("updated_at",row.get("updated_at"));return view;
    }
}
