package com.song.common.web;

import com.song.common.BizException;
import com.song.common.Result;
import com.song.common.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：所有业务异常、参数校验异常、兜底异常统一转 {@link Result}。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail(e);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidException(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError == null ? ResultCode.BAD_REQUEST.getMessage() : fieldError.getDefaultMessage();
        return Result.fail(ResultCode.BAD_REQUEST.getCode(), message);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public Result<Void> handleMissingHeader(MissingRequestHeaderException e) {
        return Result.fail(ResultCode.UNAUTHORIZED.getCode(), "缺少身份信息，请通过网关访问");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("未捕获异常", e);
        return Result.fail(ResultCode.SERVER_ERROR);
    }
}
