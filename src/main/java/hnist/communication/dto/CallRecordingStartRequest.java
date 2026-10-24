package hnist.communication.dto;

import lombok.Data;

/** 开始通话录制的请求参数。 */
@Data
public class CallRecordingStartRequest {
    private String roomId;
    private long peerId;
    private String requestId;
    private boolean swapped;
    private boolean mirror;
    private boolean cameraOff;
}
