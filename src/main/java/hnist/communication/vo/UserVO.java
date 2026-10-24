package hnist.communication.vo;

import hnist.communication.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserVO {
    private Long userId;
    private String username;
    private String nickname;
    private String avatar;
    private String phone;
    private String email;
    private String sex;
    private Integer role;
    private Long groupId;
    /** 所属群组名称（列表批量填充；groupId=1 为未分类组） */
    private String groupName;
    /**
     * 登录代际号（仅登录接口填充）：同一账号每次登录 +1，写入 JWT claim {@code gen}。
     * 其它接口不返回该字段（null）。
     */
    private Long generation;

    public static UserVO from(User user) {
        return UserVO.builder()
                .userId(user.getUserId()).username(user.getUsername())
                .nickname(user.getNickname()).avatar(user.getAvatar())
                .phone(user.getPhone()).email(user.getEmail())
                .sex(user.getSex()).role(user.getRole())
                .groupId(user.getGroupId()).build();
    }
}
