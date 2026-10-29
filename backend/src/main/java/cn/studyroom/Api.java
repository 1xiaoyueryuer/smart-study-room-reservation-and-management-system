package cn.studyroom;

import java.util.Map;
import javax.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

public final class Api {
    private Api() {}
    public static Map<String,Object> ok(Object value) { return Map.of("code",0,"message","ok","data",value); }
    public static Failure fail(int status,String message) { return new Failure(status,message); }
    public static void require(boolean condition,int status,String message) { if(!condition) throw fail(status,message); }
    public static class Failure extends RuntimeException {
        public final int status;
        Failure(int status,String message) { super(message); this.status=status; }
    }
}

@RestControllerAdvice
class ApiErrors {
    private static final Logger log=LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler(Api.Failure.class)
    ResponseEntity<?> business(Api.Failure e) { return error(e.status,e.getMessage()); }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,ConstraintViolationException.class})
    ResponseEntity<?> bad(Exception e) { return error(400,"参数格式错误"); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        return error(400,e.getBindingResult().getFieldErrors().stream().map(x->x.getField()+": "+x.getDefaultMessage()).findFirst().orElse("参数错误"));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict(DataIntegrityViolationException e) { return error(409,"数据重复或存在关联记录，请刷新后重试"); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> internal(Exception e) { log.error("请求失败",e); return error(500,"服务处理失败，请稍后重试"); }
    static ResponseEntity<?> error(int status,String message) { return ResponseEntity.status(status).body(Map.of("code",status,"message",message)); }
}
