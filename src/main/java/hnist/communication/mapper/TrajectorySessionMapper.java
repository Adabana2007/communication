package hnist.communication.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import hnist.communication.entity.TrajectorySession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TrajectorySessionMapper extends BaseMapper<TrajectorySession> {

    @Select("SELECT * FROM trajectory_sessions WHERE session_id = #{sessionId} LIMIT 1")
    TrajectorySession selectBySessionIdIncludingDeleted(@Param("sessionId") String sessionId);

    @Update("UPDATE trajectory_sessions SET is_deleted = 0, user_id = #{userId}, name = #{name}, "
            + "start_time = #{startTime}, end_time = NULL, distance_km = 0, duration_sec = 0, point_count = 0, "
            + "status = 0, created_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int restoreDeleted(TrajectorySession session);
}
