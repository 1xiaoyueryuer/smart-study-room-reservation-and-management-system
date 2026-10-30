package cn.studyroom;

import java.util.Set;
import javax.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.*;

/** Domain routes choose a fixed purpose; the client cannot change ownership or evidence purpose. */
@RestController
@RequestMapping("/api")
public class EvidenceController {
    private final AttachmentService attachments;
    public EvidenceController(AttachmentService attachments){this.attachments=attachments;}
    @PostMapping("/complaint-attachments") Object complaintUpload(HttpServletRequest req,@RequestPart("file") MultipartFile file){return upload(req,file,AttachmentService.Purpose.COMPLAINT);}
    @PostMapping("/appeal-attachments") Object appealUpload(HttpServletRequest req,@RequestPart("file") MultipartFile file){return upload(req,file,AttachmentService.Purpose.APPEAL);}
    @GetMapping("/complaint-attachments/{id}/content") ResponseEntity<byte[]> complaintContent(HttpServletRequest req,@PathVariable String id){return content(req,id,AttachmentService.Purpose.COMPLAINT);}
    @GetMapping("/appeal-attachments/{id}") ResponseEntity<byte[]> appealContent(HttpServletRequest req,@PathVariable String id){return content(req,id,AttachmentService.Purpose.APPEAL);}
    private Object upload(HttpServletRequest req,MultipartFile file,AttachmentService.Purpose purpose){
        Api.require(req instanceof MultipartHttpServletRequest,400,"材料必须以单文件表单上传");var multipart=(MultipartHttpServletRequest)req;Api.require(multipart.getMultiFileMap().keySet().equals(Set.of("file"))&&multipart.getMultiFileMap().get("file").size()==1&&req.getParameterMap().isEmpty(),400,"每次只接受一个file，不接受路径或其他字段");return Api.ok(attachments.upload(WebSecurity.uid(req),file,purpose));
    }
    private ResponseEntity<byte[]> content(HttpServletRequest req,String id,AttachmentService.Purpose purpose){var file=attachments.content(WebSecurity.uid(req),id,purpose);return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.contentType())).contentLength(file.bytes().length).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").header("Content-Disposition",ContentDisposition.inline().filename(file.displayName()).build().toString()).body(file.bytes());}
}
