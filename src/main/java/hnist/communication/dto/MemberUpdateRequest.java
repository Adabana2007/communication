package hnist.communication.dto;

import lombok.Data;

@Data
public class MemberUpdateRequest {
    private Long operatorId;

    /** 新角色（设/撤 admin 仅群主或超级管理员） */
    private String role;
    /** 群内昵称 */
    private String nickname;
}
