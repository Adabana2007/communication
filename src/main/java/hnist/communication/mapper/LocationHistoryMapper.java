package hnist.communication.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import hnist.communication.entity.LocationHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LocationHistoryMapper extends BaseMapper<LocationHistory> {

    @Select("SELECT DISTINCT DATE_FORMAT(recorded_at, '%Y-%m-%d') FROM location_history " +
            "WHERE user_id = #{userId} AND is_deleted = 0 AND recorded_at >= DATE_SUB(NOW(), INTERVAL #{days} DAY) " +
            "ORDER BY 1 DESC")
    List<String> selectAvailableDates(@Param("userId") Long userId, @Param("days") int days);
}
