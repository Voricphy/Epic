package com.gridcharge.config;

import com.gridcharge.exception.BusinessException;
import com.gridcharge.dto.Result;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> business(BusinessException ex) {
        return ResponseEntity.status(ex.getStatus()).body(Result.error(ex.getMessage()));
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        ConstraintViolationException.class,
        MethodArgumentTypeMismatchException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class
    })
    public ResponseEntity<Result<Void>> invalid(Exception ex) {
        return ResponseEntity.badRequest().body(Result.error("请求参数不合法"));
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Result<Void>> conflict(Exception ex) {
        log.warn("数据库约束冲突: {}", ex.getClass().getSimpleName());
        return ResponseEntity.status(409).body(Result.error("记录重复或存在关联业务，请刷新后重试"));
    }

    @ExceptionHandler({
        org.springframework.dao.DataAccessResourceFailureException.class,
        org.redisson.client.RedisException.class
    })
    public ResponseEntity<Result<Void>> unavailable(Exception ex) {
        log.error("依赖服务不可用", ex);
        return ResponseEntity.status(503).body(Result.error("数据库或 Redis 暂不可用，请使用原 requestId 重试"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> unknown(Exception ex) {
        log.error("服务异常", ex);
        return ResponseEntity.internalServerError().body(Result.error("服务异常，请稍后重试"));
    }
}
