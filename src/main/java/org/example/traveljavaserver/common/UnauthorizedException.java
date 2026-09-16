package org.example.traveljavaserver.common;

/** 鉴权失败专用异常：被 AuthInterceptor 或 service 抛出，GlobalExceptionHandler 统一转 401 */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
