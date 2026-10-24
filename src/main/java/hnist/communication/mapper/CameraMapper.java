package hnist.communication.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import hnist.communication.entity.Camera;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CameraMapper extends BaseMapper<Camera> {

    @Select("SELECT * FROM cameras WHERE ip = #{ip} AND port = #{port} LIMIT 1")
    Camera selectByEndpointIncludingDeleted(@Param("ip") String ip, @Param("port") Integer port);

    @Update("UPDATE cameras SET is_deleted = 0, name = #{name}, device_code = #{deviceCode}, "
            + "device_model = #{deviceModel}, username = #{username}, password = #{password}, brand = #{brand}, "
            + "rtsp_url = #{rtspUrl}, source_protocol = #{sourceProtocol}, snapshot_url = #{snapshotUrl}, "
            + "playback_path = #{playbackPath}, playback_mode = #{playbackMode}, status = 0, "
            + "mediamtx_path = NULL, webrtc_url = NULL, hls_url = NULL, last_error = NULL, "
            + "updated_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int restoreDeleted(Camera camera);
}
