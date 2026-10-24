package hnist.communication.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MapDownloadUpdateRequest {
    /** downloading / completed / failed */
    @NotBlank(message = "状态不能为空")
    private String status;
    /** 进度 0-100 */
    private Integer progress;
    /** 实际下载字节数 */
    private Long fileSize;
    private String errorMsg;
}
