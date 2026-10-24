package hnist.communication.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import hnist.communication.entity.Group;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface GroupMapper extends BaseMapper<Group> {

    /** 含逻辑删除记录的群名查询，用于重建同名群时恢复原记录。 */
    @Select("SELECT * FROM group_info WHERE name = #{name} LIMIT 1")
    Group selectByNameIncludingDeleted(@Param("name") String name);

    @Update("UPDATE group_info SET is_deleted = 0, name = #{name}, owner_id = #{ownerId}, "
            + "avatar = #{avatar}, updated_at = CURRENT_TIMESTAMP WHERE group_id = #{groupId}")
    int restoreDeleted(Group group);
}
