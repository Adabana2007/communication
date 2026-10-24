package hnist.communication.dto;

import lombok.Data;

/** 停止通话录制的请求参数。 */
@Data
public class CallRecordingStopRequest {
    private String reason;
}
