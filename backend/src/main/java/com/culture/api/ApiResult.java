package com.culture.api;

/**
 * 前后端分离统一响应：{ code, message, data }
 * code: 200=成功 400=业务/参数错误 401=未登录 403=无权限 500=服务器错误
 */
public class ApiResult<T> {

    private int code;
    private String message;
    private T data;

    public static <T> ApiResult<T> ok(T data) {
        return ok("操作成功", data);
    }

    public static <T> ApiResult<T> ok(String message, T data) {
        ApiResult<T> r = new ApiResult<>();
        r.code = 200;
        r.message = message;
        r.data = data;
        return r;
    }

    public static <T> ApiResult<T> error(String message) {
        return error(400, message);
    }

    public static <T> ApiResult<T> error(int code, String message) {
        ApiResult<T> r = new ApiResult<>();
        r.code = code;
        r.message = message;
        return r;
    }

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
}
