package hnist.communication.dto;

import lombok.Data;

/** 通话媒体心跳请求参数。 */
@Data
public class CallRecordingPulseRequest {
    private String roomId;
    private long peerId;
    private boolean cameraOff;
    private boolean muted;
}
