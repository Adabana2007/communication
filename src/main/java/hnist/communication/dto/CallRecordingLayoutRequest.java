package hnist.communication.dto;

import lombok.Data;

/** 调整通话录制画面布局的请求参数。 */
@Data
public class CallRecordingLayoutRequest {
    private boolean swapped;
    private boolean mirror;
    private boolean cameraOff;
}
