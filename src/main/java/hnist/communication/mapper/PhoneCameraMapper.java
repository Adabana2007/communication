package hnist.communication.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import hnist.communication.entity.PhoneCamera;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PhoneCameraMapper extends BaseMapper<PhoneCamera> {

    @Select("SELECT * FROM phone_cameras WHERE device_id = #{deviceId} LIMIT 1")
    PhoneCamera selectByDeviceIdIncludingDeleted(@Param("deviceId") String deviceId);

    @Update("UPDATE phone_cameras SET is_deleted = 0, user_id = #{userId}, device_name = #{deviceName}, "
            + "status = #{status}, hls_url = #{hlsUrl}, mediamtx_path = #{mediamtxPath}, "
            + "last_heartbeat = #{lastHeartbeat}, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int restoreDeleted(PhoneCamera camera);
}
