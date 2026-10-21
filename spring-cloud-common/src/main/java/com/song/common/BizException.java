package com.song.common;

/**
 * 业务异常，由全局异常处理器转成 {@link Result}。
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public BizException(String message) {
        this(ResultCode.BAD_REQUEST.getCode(), message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(ResultCode resultCode) {
        this(resultCode.getCode(), resultCode.getMessage());
    }

    public int getCode() {
        return code;
    }
}
