package com.culture.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.HashMap;
import java.util.Map;

/**
 * 上传异常统一处理。
 *
 * <p>multipart 体积超限是在进入 Controller <b>之前</b>由 DispatcherServlet 抛出的，
 * Controller 内部的 {@code @ExceptionHandler} 捕获不到，必须由全局 {@code @RestControllerAdvice} 处理；
 * 否则前端只能拿到 500 HTML 错误页，表现为「上传失败」却查不到原因。</p>
 */
@RestControllerAdvice
public class UploadExceptionHandler {

    /**
     * 文件超过 multipart 上限（默认 1MB/10MB，已在 application.yml 调整为 200MB/220MB）。
     * 返回 413 + JSON，前端可提示具体原因。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public Map<String, Object> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        Map<String, Object> result = new HashMap<>();
        result.put("errno", 1);
        result.put("message", "文件过大：图片请不超过 10MB，视频请不超过 200MB");
        return result;
    }
}
