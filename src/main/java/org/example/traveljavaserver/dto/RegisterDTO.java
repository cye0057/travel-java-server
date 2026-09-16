package org.example.traveljavaserver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 注册请求：用户名 2-32、密码 6-64，长度校验放在入口层，service 不再重复防御 */
@Data
public class RegisterDTO {
    @NotBlank(message = "用户名不能为空")
    @Size(min = 2, max = 32, message = "用户名长度需在2-32之间")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度需在6-64之间")
    private String password;

    @Size(max = 32, message = "昵称最长32字")
    private String nickname;
}
