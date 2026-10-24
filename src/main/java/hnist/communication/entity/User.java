package hnist.communication.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("users")
public class User {
    /** 普通用户角色 */
    public static final int ROLE_USER = 0;
    /** 唯一超级管理员（固定账号 admin） */
    public static final int ROLE_SUPER_ADMIN = 1;
    public static final String SUPER_ADMIN_USERNAME = "admin";

    public static boolean isSuperAdmin(Integer role) {
        return role != null && role == ROLE_SUPER_ADMIN;
    }

    @TableId(type = IdType.AUTO)
    private Long userId;
    private String username;
    private String password;
    private String nickname;
    private String avatar;
    private String phone;
    private String email;
    private String sex;
    private Integer role;
    /**
     * 默认展示群 ID（group_info.group_id，1 = 未分类组）。数据库列为 default_group_id，
     * 属性名沿用 groupId 以保持客户端 JSON 契约，由 @TableField 完成列映射。
     */
    @TableField("default_group_id")
    private Long groupId;

    /** 逻辑删除标记，不参与 JSON 契约。 */
    @com.baomidou.mybatisplus.annotation.TableLogic(value = "0", delval = "1")
    @com.baomidou.mybatisplus.annotation.TableField("is_deleted")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Boolean deleted;
}
