package hnist.communication.dto;

import lombok.Data;

/** 更新群组请求，所有字段可选 */
@Data
public class GroupUpdateRequest {
    private String ownerUsername;
    private String name;
    private String avatar;
}
