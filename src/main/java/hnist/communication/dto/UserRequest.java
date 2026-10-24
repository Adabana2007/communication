package hnist.communication.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 新建用户请求（注册或后台创建）。 */
@Data
public class UserRequest {
    @NotBlank(message = "用户名不能为空")
    private String username;

    /** 新建时必填 */
    @NotBlank(message = "密码不能为空")
    private String password;

    private String nickname;
    private String phone;
    /** 系统角色: 0=普通用户 1=唯一超级管理员（固定账号 admin） */
    private Integer role;
}
