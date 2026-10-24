package hnist.communication.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import hnist.communication.entity.DictType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DictTypeMapper extends BaseMapper<DictType> {

    @Select("SELECT * FROM dict_type WHERE dict_type = #{dictType} LIMIT 1")
    DictType selectByTypeIncludingDeleted(@Param("dictType") String dictType);

    @Update("UPDATE dict_type SET is_deleted = 0, dict_name = #{dictName}, status = #{status} WHERE id = #{id}")
    int restoreDeleted(DictType type);
}
