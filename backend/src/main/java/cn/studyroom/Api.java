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
    public static Failure fail(int status,String message,Object details) { return new Failure(status,message,details); }
    public static void require(boolean condition,int status,String message) { if(!condition) throw fail(status,message); }
    public static class Failure extends RuntimeException {
        public final int status;
        public final Object details;
        Failure(int status,String message) { this(status,message,null); }
        Failure(int status,String message,Object details) { super(message); this.status=status; this.details=details; }
    }
}

@RestControllerAdvice
class ApiErrors {
    private static final Logger log=LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler(Api.Failure.class)
    ResponseEntity<?> business(Api.Failure e) {
        if(e.details==null) return error(e.status,e.getMessage());
        return ResponseEntity.status(e.status).body(Map.of("code",e.status,"message",e.getMessage(),"details",e.details));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,ConstraintViolationException.class,org.springframework.web.multipart.MultipartException.class,org.springframework.web.multipart.support.MissingServletRequestPartException.class,org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<?> bad(Exception e) { return error(400,"参数格式错误"); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        return error(400,e.getBindingResult().getFieldErrors().stream().map(x->x.getField()+": "+x.getDefaultMessage()).findFirst().orElse("参数错误"));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict(DataIntegrityViolationException e) {
        if(isConflict(e)) return error(409,"数据重复或存在关联记录，请刷新后重试");
        log.error("数据库写入失败，操作未完成，SQLState={}",sqlState(e));
        return error(500,"服务处理失败，操作未完成，请稍后重试");
    }
    static boolean isConflict(Throwable e) { return java.util.Set.of("23505","23503").contains(sqlState(e)); }
    private static String sqlState(Throwable e) {
        for(Throwable cause=e;cause!=null;cause=cause.getCause()) if(cause instanceof java.sql.SQLException sql) return java.util.Objects.toString(sql.getSQLState(),"");
        return "";
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> internal(Exception e) { log.error("请求失败",e); return error(500,"服务处理失败，请稍后重试"); }
    static ResponseEntity<?> error(int status,String message) { return ResponseEntity.status(status).body(Map.of("code",status,"message",message)); }
}
