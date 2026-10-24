package hnist.communication.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import hnist.communication.entity.GroupMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface GroupMemberMapper extends BaseMapper<GroupMember> {

    /** 包含已逻辑删除记录的唯一键查询，重新加群时必须恢复而不是插入重复行。 */
    @Select("SELECT * FROM group_members WHERE group_id = #{groupId} AND user_id = #{userId} LIMIT 1")
    GroupMember selectIncludingDeleted(@Param("groupId") Long groupId, @Param("userId") Long userId);

    @Update("UPDATE group_members SET is_deleted = 0, role = #{role}, nickname = #{nickname}, "
            + "joined_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int restoreDeleted(GroupMember member);
}
