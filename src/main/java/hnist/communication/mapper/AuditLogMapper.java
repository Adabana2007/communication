package hnist.communication.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import hnist.communication.entity.AuditLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

public interface AuditLogMapper extends BaseMapper<AuditLog> {
    /** 审计日志保留期到期后物理清理，不能只打软删标记导致表无限增长。 */
    @Delete("DELETE FROM audit_logs WHERE created_at < #{cutoff}")
    int hardDeleteBefore(@Param("cutoff") LocalDateTime cutoff);
}
