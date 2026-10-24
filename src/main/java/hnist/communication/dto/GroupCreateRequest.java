package hnist.communication.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GroupCreateRequest {
    @NotBlank(message = "群主用户名不能为空")
    private String ownerUsername;

    @NotBlank(message = "群组名称不能为空")
    private String name;

    private String avatar;
}
