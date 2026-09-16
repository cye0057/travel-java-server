package org.example.traveljavaserver.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class Result<T> {
    private Boolean success;
    private Integer code;
    private String messages;
    private T data;
    private String error;
    private String rawResponse;

    public static <T> Result<T> ok(){
        Result<T> result = new Result<>();
        result.setSuccess(true);
        result.setCode(200);
        result.setMessages("成功");
        return result;
    }

    public static <T> Result<T> ok(T data){
        Result<T> result = ok();
        result.setData(data);
        return result;
    }

    public static <T> Result<T> fail(){
        Result<T> result = new Result<>();
        result.setSuccess(false);
        result.setCode(500);
        result.setMessages("失败");
        return result;
    }

    public static <T> Result<T> fail(Integer code ,String messages){
        Result<T> result = fail();
        result.setCode(code);   // 之前漏了回显 code，导致 400/401 都被写成 500，前端没法区分"登录过期"
        result.setMessages(messages);
        return result;
    }

    public static <T> Result<T> error(String error,String rawResponse){
        Result<T> result = new Result<>();
        result.setSuccess(false);
        result.setError(error);
        result.setRawResponse(rawResponse);
        return result;
    }
}
