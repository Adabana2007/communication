package hnist.communication.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("group_members")
public class GroupMember {
    /** 群主 */
    public static final String ROLE_OWNER = "owner";
    /** 管理员 */
    public static final String ROLE_ADMIN = "admin";
    /** 普通成员 */
    public static final String ROLE_MEMBER = "member";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long groupId;
    private Long userId;
    /** 角色: owner / admin / member */
    private String role;
    /** 群内昵称 */
    private String nickname;
    private LocalDateTime joinedAt;

    /** 逻辑删除标记，不参与 JSON 契约。 */
    @com.baomidou.mybatisplus.annotation.TableLogic(value = "0", delval = "1")
    @com.baomidou.mybatisplus.annotation.TableField("is_deleted")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Boolean deleted;
}
