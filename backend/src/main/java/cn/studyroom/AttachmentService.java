package cn.studyroom;

import java.awt.image.BufferedImage;
import java.io.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.imageio.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 材料只保存原字节于数据库；临时/绑定读取均鉴权，不暴露文件系统路径。 */
@Service
public class AttachmentService {
    static final int MAX_BYTES=5*1024*1024;
    private static final String METADATA="id,owner_id,purpose,complaint_id,appeal_id,position,display_name,content_type,size_bytes,width,height,sha256,created_at,expires_at";
    public enum Purpose {
        COMPLAINT("complaint","complaints","complaint_id","投诉"),APPEAL("appeal","appeals","appeal_id","申诉");
        final String value,table,column,label;
        Purpose(String value,String table,String column,String label){this.value=value;this.table=table;this.column=column;this.label=label;}
    }
    private final Store db;private final AuthService auth;private final AuditService audit;private final Clock clock;
    public AttachmentService(Store db,AuthService auth,AuditService audit,Clock clock) { this.db=db;this.auth=auth;this.audit=audit;this.clock=clock; }
    private record Image(String contentType,String extension,int width,int height) {}
    public record Content(byte[] bytes,String contentType,String displayName) {}
    @Transactional
    public Map<String,Object> upload(long actor,MultipartFile file) {return upload(actor,file,Purpose.COMPLAINT);}
    @Transactional
    public Map<String,Object> upload(long actor,MultipartFile file,Purpose purpose) {
        requireOrdinary(actor);Api.require(file!=null&&!file.isEmpty(),400,"请选择一张PNG或JPEG材料");Api.require(file.getSize()<=MAX_BYTES,400,"单张材料不能超过5MiB");
        byte[] bytes;try(var stream=file.getInputStream()) { bytes=stream.readNBytes(MAX_BYTES+1); } catch(IOException e) { throw Api.fail(400,"材料读取失败，请重新上传"); }
        Api.require(bytes.length>0&&bytes.length<=MAX_BYTES,400,"单张材料不能超过5MiB");var image=inspect(bytes);String hash=sha256(bytes);
        db.lockBookings();requireOrdinary(actor);var now=now();String id=UUID.randomUUID().toString(),name="material-"+id+"."+image.extension();
        db.update("INSERT INTO evidence_attachments(id,owner_id,purpose,display_name,content_type,size_bytes,width,height,sha256,file_bytes,created_at,expires_at) VALUES(CAST(? AS UUID),?,?,?,?,?,?,?,?,?,?,?)",id,actor,purpose.value,name,image.contentType(),bytes.length,image.width(),image.height(),hash,bytes,java.sql.Timestamp.valueOf(now),java.sql.Timestamp.valueOf(now.plusHours(24)));
        var result=metadata(db.one("SELECT "+METADATA+" FROM evidence_attachments WHERE id=CAST(? AS UUID)",id));
        audit.record(actor,purpose.value+"_attachment.upload",purpose.value+"_attachment",id,"用户上传临时"+purpose.label+"材料，内容尚未核实",Map.of(),result);return result;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Content content(long actor,String rawId) {return content(actor,rawId,Purpose.COMPLAINT);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Content content(long actor,String rawId,Purpose purpose) {
        var user=requireActive(actor);String id=uuid(rawId,"材料编号");var row=db.one("SELECT "+METADATA+" FROM evidence_attachments WHERE id=CAST(? AS UUID) AND purpose=?",id,purpose.value);boolean owner=Store.id(row,"owner_id")==actor;
        Api.require(owner||"admin".equals(user.get("role"))&&bound(row),403,"无权读取该材料");
        if(!bound(row)) Api.require(now().isBefore(LocalDateTime.parse(row.get("expires_at").toString())),410,"临时材料已过期，请重新上传");
        byte[] bytes=(byte[])db.one("SELECT file_bytes FROM evidence_attachments WHERE id=CAST(? AS UUID)",id).get("file_bytes");return new Content(bytes,Store.text(row,"content_type"),Store.text(row,"display_name"));
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void bind(long actor,long complaint,List<String> ids,LocalDateTime snapshot) {bind(actor,Purpose.COMPLAINT,complaint,ids,snapshot);}
    @Transactional(propagation=Propagation.MANDATORY)
    public void bind(long actor,Purpose purpose,long domain,List<String> ids,LocalDateTime snapshot) {
        db.lockBookings();requireOrdinary(actor);Api.require(Store.id(db.one("SELECT user_id FROM "+purpose.table+" WHERE id=?",domain),"user_id")==actor,403,"不能绑定他人的"+purpose.label);Api.require(ids.size()<=3&&new HashSet<>(ids).size()==ids.size(),400,"每条记录最多3个不重复材料");
        for(int position=0;position<ids.size();position++) {
            String id=uuid(ids.get(position),"材料编号");var row=db.one("SELECT "+METADATA+" FROM evidence_attachments WHERE id=CAST(? AS UUID)",id);Api.require(Store.id(row,"owner_id")==actor,403,"不能使用他人的材料");Api.require(purpose.value.equals(row.get("purpose")),400,"材料用途不匹配，请为本次"+purpose.label+"重新上传");Api.require(!bound(row),409,"材料已绑定其他记录，请重新上传");Api.require(snapshot.isBefore(LocalDateTime.parse(row.get("expires_at").toString())),409,"材料已过期，请重新上传");
            int changed=db.update("UPDATE evidence_attachments SET "+purpose.column+"=?,position=?,bound_at=? WHERE id=CAST(? AS UUID) AND owner_id=? AND purpose=? AND complaint_id IS NULL AND appeal_id IS NULL AND expires_at>?",domain,position,java.sql.Timestamp.valueOf(snapshot),id,actor,purpose.value,java.sql.Timestamp.valueOf(snapshot));Api.require(changed==1,409,"材料状态已变化，请刷新后重试");
        }
    }
    List<Map<String,Object>> boundMetadata(long complaint) {return boundMetadata(Purpose.COMPLAINT,complaint);}
    List<Map<String,Object>> boundMetadata(Purpose purpose,long domain) { return db.list("SELECT "+METADATA+" FROM evidence_attachments WHERE "+purpose.column+"=? AND purpose=? ORDER BY position,id",domain,purpose.value).stream().map(this::metadata).toList(); }
    @Transactional
    public int cleanupExpired() {
        db.lockBookings();var now=now();var removed=db.list("DELETE FROM evidence_attachments WHERE complaint_id IS NULL AND appeal_id IS NULL AND expires_at<=? RETURNING id",java.sql.Timestamp.valueOf(now));
        if(!removed.isEmpty()) audit.record(null,"evidence_attachment.cleanup","temporary_material_batch",now.toString(),"系统清理满24小时且未绑定的投诉或申诉材料",Map.of(),Map.of("deleted_count",removed.size()));return removed.size();
    }
    private Map<String,Object> metadata(Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>();for(String key:List.of("id","display_name","content_type","size_bytes","width","height","sha256")) result.put(key,row.get(key));result.put("id",row.get("id").toString());result.put("created_at",offset(row.get("created_at")));result.put("expires_at",!bound(row)?offset(row.get("expires_at")):null);return result;
    }
    private boolean bound(Map<String,Object> row){return row.get("complaint_id")!=null||row.get("appeal_id")!=null;}
    private Image inspect(byte[] bytes) {
        boolean png=bytes.length>=8&&Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10});boolean jpeg=bytes.length>=3&&(bytes[0]&255)==255&&(bytes[1]&255)==216&&(bytes[2]&255)==255;Api.require(png||jpeg,400,"材料须为真实PNG或JPEG图片");
        if(png) completePng(bytes);else completeJpeg(bytes);
        ImageReader reader=null;BufferedImage decoded=null;var warnings=new ArrayList<String>();
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input);Api.require(readers.hasNext(),400,"材料无法解码，请选择完整PNG或JPEG");reader=readers.next();String format=reader.getFormatName();Api.require(png&&format.equalsIgnoreCase("png")||jpeg&&format.equalsIgnoreCase("jpeg"),400,"材料签名和图片格式不一致");reader.addIIOReadWarningListener((source,warning)->warnings.add(warning));reader.setInput(input,false,true);int width=reader.getWidth(0),height=reader.getHeight(0);Api.require(width>0&&height>0&&width<=10000&&height<=10000&&(long)width*height<=20_000_000,400,"材料单边不能超过10000像素且总像素不能超过2000万");
            decoded=reader.read(0);Api.require(decoded!=null&&decoded.getWidth()==width&&decoded.getHeight()==height&&warnings.isEmpty(),400,"材料无法完整解码");return new Image(png?"image/png":"image/jpeg",png?"png":"jpg",width,height);
        } catch(Api.Failure e) { throw e; } catch(IOException|RuntimeException e) { throw Api.fail(400,"材料图片损坏或无法解码，请重新选择"); }
        finally { if(decoded!=null) decoded.flush();if(reader!=null) reader.dispose(); }
    }
    // 只检验容器完整性；像素/颜色/可选元数据仍由ImageIO解码，原字节不重写。
    private void completePng(byte[] bytes) {
        int position=8;boolean first=true;
        while(position+12<=bytes.length) {
            long length=Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(bytes,position,4).getInt());long end=position+12L+length;Api.require(end<=bytes.length,400,"PNG材料容器被截断");int size=(int)length,type=java.nio.ByteBuffer.wrap(bytes,position+4,4).getInt();
            Api.require(!first||type==0x49484452&&size==13,400,"PNG材料缺少完整头部");first=false;var crc=new java.util.zip.CRC32();crc.update(bytes,position+4,size+4);long stored=Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(bytes,position+8+size,4).getInt());Api.require(crc.getValue()==stored,400,"PNG材料校验失败，请重新选择完整图片");
            if(type==0x49454e44) { Api.require(size==0&&end==bytes.length,400,"PNG材料结束块无效");return; }position=(int)end;
        }
        throw Api.fail(400,"PNG材料缺少完整结束块");
    }
    private void completeJpeg(byte[] bytes) {
        int position=2;
        while(position<bytes.length) {
            Api.require((bytes[position]&255)==255,400,"JPEG材料容器损坏");while(position<bytes.length&&(bytes[position]&255)==255) position++;Api.require(position<bytes.length,400,"JPEG材料被截断");int marker=bytes[position++]&255;
            if(marker==0xd9) return;Api.require(marker!=0&&marker!=0xd8,400,"JPEG材料标记无效");if(marker==1||marker>=0xd0&&marker<=0xd7) continue;
            Api.require(position+2<=bytes.length,400,"JPEG材料被截断");int length=(bytes[position]&255)*256+(bytes[position+1]&255);Api.require(length>=2&&position+(long)length<=bytes.length,400,"JPEG材料片段被截断");position+=length;
            if(marker==0xda) {
                // 熵数据中的FF00是转义字节，FFD0..D7为重启；APP/EXIF内的EOI不当成主图结束。
                while(position<bytes.length) {
                    if((bytes[position]&255)!=255) { position++;continue; }
                    int start=position;while(position<bytes.length&&(bytes[position]&255)==255) position++;Api.require(position<bytes.length,400,"JPEG材料缺少主图结束标记");int next=bytes[position]&255;
                    if(next==0||next>=0xd0&&next<=0xd7) { position++;continue; }position=start;break;
                }
            }
        }
        throw Api.fail(400,"JPEG材料缺少主图结束标记");
    }
    Map<String,Object> requireActive(long actor) { var user=auth.profile(actor);Api.require("active".equals(user.get("status"))&&(AuthService.isOrdinary(user.get("role"))||"admin".equals(user.get("role"))),403,"账号当前不可操作");return user; }
    void requireOrdinary(long actor) { Api.require(AuthService.isOrdinary(requireActive(actor).get("role")),403,"仅启用的学生和教职工可操作本人记录或材料"); }
    static String uuid(String raw,String label) { Api.require(raw!=null&&raw.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"),400,label+"须为UUID");return UUID.fromString(raw).toString(); }
    static String offset(Object local) { return local==null?null:LocalDateTime.parse(local.toString()).atZone(BookingPolicy.CAMPUS_ZONE).toOffsetDateTime().toString(); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(),BookingPolicy.CAMPUS_ZONE).truncatedTo(ChronoUnit.MICROS); }
    private String sha256(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
}
