package cn.studyroom;

import java.nio.charset.StandardCharsets;
import javax.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/users/import")
public class UserImportController {
    private final UserImportService imports;
    public UserImportController(UserImportService imports) { this.imports=imports; }
    @GetMapping("/template") ResponseEntity<byte[]> template() {
        return ResponseEntity.ok().header("Content-Type","text/csv;charset=UTF-8")
            .header("Content-Disposition","attachment; filename=campus-users-template.csv")
            .body("\uFEFFcampus_id,username,real_name,role,phone,email\r\n".getBytes(StandardCharsets.UTF_8));
    }
    @PostMapping(value="/preview",consumes="multipart/form-data") Object preview(HttpServletRequest req,@RequestParam("file") MultipartFile file) { return Api.ok(imports.preview(WebSecurity.uid(req),file)); }
    @PostMapping(value="/confirm",consumes="multipart/form-data") Object confirm(HttpServletRequest req,@RequestParam("file") MultipartFile file,@RequestParam("file_sha256") String hash,@RequestParam("initial_password") String password,@RequestParam("reason") String reason) {
        return Api.ok(imports.confirm(WebSecurity.uid(req),file,hash,password,reason));
    }
}
