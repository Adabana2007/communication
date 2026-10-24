package hnist.communication.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import hnist.communication.entity.VoiceMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface VoiceMessageMapper extends BaseMapper<VoiceMessage> {}
