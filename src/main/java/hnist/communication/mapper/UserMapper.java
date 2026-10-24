package hnist.communication.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import hnist.communication.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /** 含逻辑删除记录的用户名查询，仅供重建同名账号时恢复原记录。 */
    @Select("SELECT * FROM users WHERE username = #{username} LIMIT 1")
    User selectByUsernameIncludingDeleted(@Param("username") String username);

    /** 恢复逻辑删除的用户并写入本次创建时的最新资料。 */
    @Update("UPDATE users SET is_deleted = 0, password = #{password}, nickname = #{nickname}, "
            + "phone = #{phone}, email = NULL, sex = NULL, avatar = NULL, role = #{role}, "
            + "default_group_id = #{groupId}, updated_at = CURRENT_TIMESTAMP "
            + "WHERE user_id = #{userId}")
    int restoreDeleted(User user);
}
