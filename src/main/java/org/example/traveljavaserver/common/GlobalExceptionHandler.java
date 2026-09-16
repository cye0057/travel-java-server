package org.example.traveljavaserver.common;

import org.example.traveljavaserver.vo.Result;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handlerException(MethodArgumentNotValidException e){
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return Result.fail(400,message);
    }

    /** 鉴权失败：返回体 code=401，前端据此清本地token跳登录（沿用本项目"HTTP 200 + 业务码"风格） */
    @ExceptionHandler(UnauthorizedException.class)
    public Result<Void> handleUnauthorized(UnauthorizedException e){
        return Result.fail(401, e.getMessage());
    }

    /** 用户名已存在、密码错误等业务校验失败 */
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e){
        return Result.fail(400, e.getMessage());
    }
//    /**
//     * 通用全局异常，JDK17完全兼容，完整写在类内部，不是裸代码片段
//     */
//    @ExceptionHandler(Exception.class)
//    public Result<Void> handleAllException(Exception e){
//        //打印堆栈，方便后台看日志
//        e.printStackTrace();
//        return Result.fail(500,"服务器内部异常");
//    }
}
