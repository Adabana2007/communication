package hnist.communication.dto;

import lombok.Data;

/**
 * 报警处理状态变更请求
 */
@Data
public class AlarmStatusRequest {
    /** pending=未处理 processing=处理中 done=已处理 */
    private String status;
    private String remark;
}
