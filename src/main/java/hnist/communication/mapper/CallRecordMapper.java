package hnist.communication.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import hnist.communication.entity.CallRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CallRecordMapper extends BaseMapper<CallRecord> {}
