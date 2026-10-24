package hnist.communication.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 添加/移除成员请求
 */
@Data
public class MemberAddRequest {
    private Long operatorId;

    @NotEmpty(message = "用户列表不能为空")
    private List<Long> userIds;
}
