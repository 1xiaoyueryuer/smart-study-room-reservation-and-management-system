package cn.studyroom;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import org.apache.commons.csv.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class UserImportService {
    static final int MAX_BYTES=1024*1024, MAX_ROWS=500;
    static final List<String> FIELDS=List.of("campus_id","username","real_name","role","phone","email");
    static final Set<String> REQUIRED=Set.of("campus_id","username","real_name","role");
    private final Store db;
    private final AuthService auth;
    private final UserService users;
    private final AuditService audit;
    public UserImportService(Store db,AuthService auth,UserService users,AuditService audit) { this.db=db;this.auth=auth;this.users=users;this.audit=audit; }

    public record Row(long rowNumber,String campusId,String username,String realName,String role,String phone,String email,List<String> errors) {
        Requests.UserChange request(String password,String reason) { return new Requests.UserChange(username,realName,campusId,role,"active",phone,email,password,reason); }
        Row withError(String error) { var all=new ArrayList<>(errors);all.add(error);return new Row(rowNumber,campusId,username,realName,role,phone,email,List.copyOf(all)); }
    }
    public record Preview(String fileSha256,int total,long validCount,long invalidCount,List<Row> rows) {}
    public record ImportedUser(long id,String campusId,String username,String role) {}
    public record Result(int importedCount,List<ImportedUser> users) {}

    public Preview preview(long actor,MultipartFile file) {
        requireAdmin(actor);
        return inspect(read(file));
    }
    @Transactional
    public Result confirm(long actor,MultipartFile file,String expectedHash,String password,String reason) {
        db.lockBookings();requireAdmin(actor);
        byte[] bytes=read(file);String hash=sha256(bytes);
        if(!hash.equals(expectedHash)) throw Api.fail(409,"文件已变化，请重新预检",Map.of("reason","file_changed"));
        users.validateInitialPassword(password);
        Api.require(reason!=null&&!reason.isBlank()&&reason.length()<=1000,400,"导入理由必填且不能超过1000字符");
        Preview current=inspect(bytes);
        if(current.invalidCount()>0) throw Api.fail(409,"导入数据已失效或存在错误，请修正后重新预检",conflicts(current));
        List<ImportedUser> imported=new ArrayList<>();
        for(Row row:current.rows()) {
            try {
                var created=users.save(actor,null,row.request(password,reason));
                imported.add(new ImportedUser(Store.id(created,"id"),row.campusId(),row.username(),row.role()));
            } catch(DataIntegrityViolationException e) {
                if(!ApiErrors.isConflict(e)) throw e;
                throw Api.fail(409,"确认时发生账号冲突，整批未导入，请重新预检",Map.of("rows",List.of(row.withError("校园编号或用户名已存在，请重新预检")),"total",current.total(),"valid_count",current.total()-1,"invalid_count",1));
            }
        }
        audit.record(actor,"user.import","user_batch",hash,reason.trim(),Map.of(),Map.of("file_sha256",hash,"imported_count",imported.size()));
        return new Result(imported.size(),List.copyOf(imported));
    }
    private static Map<String,Object> conflicts(Preview data) {
        return Map.of("rows",data.rows().stream().filter(r->!r.errors().isEmpty()).toList(),"total",data.total(),"valid_count",data.validCount(),"invalid_count",data.invalidCount());
    }
    private void requireAdmin(long actor) { var user=auth.profile(actor);Api.require("admin".equals(user.get("role"))&&"active".equals(user.get("status")),403,"需要管理员权限"); }
    private static byte[] read(MultipartFile file) {
        Api.require(file!=null&&!file.isEmpty(),400,"请选择CSV文件");
        Api.require(file.getSize()<=MAX_BYTES,400,"CSV文件不能超过1MiB");
        try(var stream=file.getInputStream()) {
            byte[] bytes=stream.readNBytes(MAX_BYTES+1);
            Api.require(bytes.length<=MAX_BYTES,400,"CSV文件不能超过1MiB");return bytes;
        } catch(IOException e) { throw Api.fail(400,"CSV文件读取失败，请重新选择"); }
    }
    private Preview inspect(byte[] bytes) {
        String text;
        try { text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch(CharacterCodingException e) { throw Api.fail(400,"CSV必须使用有效UTF-8编码"); }
        if(text.startsWith("\uFEFF")) text=text.substring(1);
        List<Row> rows=new ArrayList<>();
        try(CSVParser parser=CSVFormat.RFC4180.parse(new StringReader(text))) {
            var records=parser.iterator();
            Api.require(records.hasNext(),400,"CSV缺少表头");
            CSVRecord header=records.next();List<String> columns=header.toList();
            Api.require(new HashSet<>(columns).size()==columns.size(),400,"CSV表头不能重复");
            Api.require(FIELDS.containsAll(columns)&&columns.containsAll(REQUIRED),400,"CSV表头仅允许campus_id,username,real_name,role,phone,email，且前四列必需；不得包含凭证");
            while(records.hasNext()) {
                CSVRecord data=records.next();
                Api.require(rows.size()<MAX_ROWS,400,"CSV数据不能超过500条");
                Api.require(data.size()==columns.size(),400,"CSV第"+data.getRecordNumber()+"条记录的列数与表头不符");
                var fields=new HashMap<String,String>();
                for(int i=0;i<columns.size();i++) fields.put(columns.get(i),AuthService.blankNull(data.get(i)));
                Row row=new Row(data.getRecordNumber(),fields.get("campus_id"),fields.get("username"),fields.get("real_name"),fields.get("role"),fields.get("phone"),fields.get("email"),List.of());
                List<String> errors=users.creationErrors(row.request(null,"CSV导入预检"));
                if(fields.values().stream().anyMatch(value->value!=null&&value.indexOf('\0')>=0)) errors.add("字段不能包含NUL控制字符");
                rows.add(new Row(row.rowNumber(),row.campusId(),row.username(),row.realName(),row.role(),row.phone(),row.email(),errors));
            }
        } catch(IOException|UncheckedIOException|IllegalArgumentException e) { throw Api.fail(400,"CSV格式无效，请检查引号、分隔符和换行"); }
        Api.require(!rows.isEmpty(),400,"CSV没有数据行");
        Map<String,Integer> campusCounts=new HashMap<>(),nameCounts=new HashMap<>();
        for(Row row:rows) { if(row.campusId()!=null) campusCounts.merge(row.campusId(),1,Integer::sum);if(row.username()!=null) nameCounts.merge(row.username(),1,Integer::sum); }
        Set<String> knownCampus=new HashSet<>(),knownNames=new HashSet<>();
        for(var known:db.list("SELECT campus_id,username FROM users")) { if(known.get("campus_id")!=null) knownCampus.add((String)known.get("campus_id"));knownNames.add((String)known.get("username")); }
        List<Row> checked=new ArrayList<>();
        for(Row row:rows) {
            if(campusCounts.getOrDefault(row.campusId(),0)>1) row=row.withError("campus_id: 同批重复校园编号");
            if(nameCounts.getOrDefault(row.username(),0)>1) row=row.withError("username: 同批重复用户名");
            if(knownCampus.contains(row.campusId())) row=row.withError("campus_id: 校园编号已存在");
            if(knownNames.contains(row.username())) row=row.withError("username: 用户名已存在");
            checked.add(row);
        }
        long invalid=checked.stream().filter(r->!r.errors().isEmpty()).count();
        return new Preview(sha256(bytes),checked.size(),checked.size()-invalid,invalid,List.copyOf(checked));
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException("运行环境缺少SHA-256",e); }
    }
}
