package hnist.communication.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import hnist.communication.entity.MapDownload;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface MapDownloadMapper extends BaseMapper<MapDownload> {

    @Select("SELECT * FROM map_downloads WHERE user_id = #{userId} AND region_id = #{regionId} LIMIT 1")
    MapDownload selectByUserAndRegionIncludingDeleted(@Param("userId") Long userId,
                                                      @Param("regionId") Long regionId);

    @Update("UPDATE map_downloads SET is_deleted = 0, status = #{status}, progress = #{progress}, "
            + "file_size = #{fileSize}, error_msg = #{errorMsg}, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int restoreDeleted(MapDownload download);
}
