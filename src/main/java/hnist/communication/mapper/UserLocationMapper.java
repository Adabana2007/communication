package hnist.communication.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import hnist.communication.entity.UserLocation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserLocationMapper extends BaseMapper<UserLocation> {

    @Select("SELECT * FROM user_locations WHERE user_id = #{userId} LIMIT 1")
    UserLocation selectByUserIdIncludingDeleted(@Param("userId") Long userId);

    @Update("UPDATE user_locations SET is_deleted = 0, latitude = #{latitude}, longitude = #{longitude}, "
            + "address = #{address}, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int restoreDeleted(UserLocation location);
}
