package hnist.communication.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VideoRenameRequest {
    @NotBlank(message = "新文件名不能为空")
    private String newFileName;
}
