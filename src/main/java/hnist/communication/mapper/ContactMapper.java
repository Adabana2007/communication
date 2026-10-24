package hnist.communication.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import hnist.communication.entity.Contact;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ContactMapper extends BaseMapper<Contact> {

    @Select("SELECT * FROM contacts WHERE user_id = #{userId} AND contact_user_id = #{contactUserId} LIMIT 1")
    Contact selectIncludingDeleted(@Param("userId") Long userId, @Param("contactUserId") Long contactUserId);

    @Update("UPDATE contacts SET is_deleted = 0, remark = #{remark}, friend_group_id = #{friendGroupId} "
            + "WHERE id = #{id}")
    int restoreDeleted(Contact contact);
}
