package com.convenientclipboard.common;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<?> api(ApiException ex) { return error(ex.status(), ex.getMessage()); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> validation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst().map(e -> e.getField() + ": " + e.getDefaultMessage()).orElse("参数无效");
        return error(HttpStatus.BAD_REQUEST, message);
    }
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
            MissingServletRequestPartException.class, IllegalArgumentException.class})
    public ResponseEntity<?> malformed(Exception ex) { return error(HttpStatus.BAD_REQUEST, "请求参数无效"); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> tooLarge(Exception ex) { return error(HttpStatus.PAYLOAD_TOO_LARGE, "文件超过上传大小限制"); }
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> notFound(Exception ex) { return error(HttpStatus.NOT_FOUND, "资源不存在"); }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> method(Exception ex) { return error(HttpStatus.METHOD_NOT_ALLOWED, "不支持此请求方法"); }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> forbidden(Exception ex) { return error(HttpStatus.FORBIDDEN, "没有操作权限"); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> conflict(Exception ex) { return error(HttpStatus.CONFLICT, "数据冲突，请刷新后重试"); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(Exception ex) {
        log.error("Request failed", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "服务暂时不可用，请稍后重试");
    }
    private ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message));
    }
}
