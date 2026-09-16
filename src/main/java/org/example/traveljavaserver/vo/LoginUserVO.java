package org.example.traveljavaserver.vo;

/** 登录/注册成功返回：token 给前端存 localStorage，userCode 是对外唯一标识 */
public record LoginUserVO(String token, String userCode, String username, String nickname) {}
