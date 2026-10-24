package hnist.communication.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import hnist.communication.entity.CallRecord;
import hnist.communication.vo.CallRecordVO;
import hnist.communication.vo.CallStatsVO;

/** 通话记录查询、增删改与统计。 */
public interface CallRecordService {

    /** 按媒体类型、通话类型、状态、关键字和时间范围筛选；viewerId/admin 用于区分本人与管理员可见范围。 */
    Page<CallRecordVO> list(int page, int size, Integer mediaType, Integer callType,
                            String status, String keyword, String startTime, String endTime,
                            Long viewerId, boolean admin);

    CallRecordVO get(Long id);

    CallRecordVO create(CallRecord record);

    CallRecordVO update(Long id, CallRecord record);

    void delete(Long id);

    void batchDelete(Long[] ids);

    CallStatsVO stats();
}
