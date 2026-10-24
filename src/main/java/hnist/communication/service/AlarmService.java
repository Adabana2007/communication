package hnist.communication.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.entity.Alarm;
import hnist.communication.entity.AlarmMedia;
import hnist.communication.vo.AlarmVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 告警记录查询与处理状态变更。 */
public interface AlarmService {

    Alarm create(Alarm alarm);

    /** 创建报警并在同一事务内绑定本人上传且尚未使用的媒体。 */
    Alarm createWithMedia(Alarm alarm, List<Long> imageMediaIds, Long voiceMediaId);

    /** 校验并保存报警图片/语音；服务端按文件头判断真实类型。 */
    AlarmMedia storeMedia(MultipartFile file, Long uploaderId);

    /** 读取报警媒体。 */
    AlarmMedia getMedia(Long id);

    /** 后台告警分页：按用户、关键词、状态和时间范围过滤。 */
    Page<AlarmVO> list(int page, int size, Long userId, String keyword,
                       String status, String start, String end);

    /** 状态仅允许 pending/processing/done，同时记录处理人与备注。 */
    void updateStatus(Long id, String status, String remark, Long operatorId);
}
