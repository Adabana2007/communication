package hnist.communication.vo;

import hnist.communication.entity.GroupMember;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupMemberVO {
    private Long id;
    private Long groupId;
    private Long userId;
    /** 用户名（非DB字段，查询时填充） */
    private String username;
    /** 角色: owner / admin / member */
    private String role;
    /** 群内昵称 */
    private String nickname;
    private LocalDateTime joinedAt;

    public static GroupMemberVO from(GroupMember member) {
        return GroupMemberVO.builder()
                .id(member.getId()).groupId(member.getGroupId())
                .userId(member.getUserId()).role(member.getRole())
                .nickname(member.getNickname()).joinedAt(member.getJoinedAt())
                .build();
    }
}
