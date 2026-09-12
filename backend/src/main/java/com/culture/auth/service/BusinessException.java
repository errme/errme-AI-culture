package com.culture.auth.service;

/**
 * 业务异常：message 会直接作为接口错误信息返回前端。
 * 由 AuthController 的 @ExceptionHandler 统一捕获并转为 ApiResponse。
 */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) {
        super(message);
    }
}
