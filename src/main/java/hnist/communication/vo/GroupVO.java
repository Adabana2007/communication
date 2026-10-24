package hnist.communication.vo;

import hnist.communication.entity.Group;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupVO {
    private Long groupId;
    private String name;
    private Long ownerId;
    /** 群主用户名（非DB字段，查询时填充） */
    private String ownerUsername;
    private String avatar;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static GroupVO from(Group group, String ownerUsername) {
        return GroupVO.builder()
                .groupId(group.getGroupId()).name(group.getName())
                .ownerId(group.getOwnerId()).ownerUsername(ownerUsername)
                .avatar(group.getAvatar()).createdAt(group.getCreatedAt())
                .updatedAt(group.getUpdatedAt()).build();
    }
}
