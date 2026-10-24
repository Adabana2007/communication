package hnist.communication.service;

import hnist.communication.model.ChatMediaFile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/** 聊天图片、视频与语音的上传、定位和读取。 */
public interface ChatMediaService {

    /** 校验上传文件并落盘，返回客户端所需的媒体标识与访问地址。 */
    Map<String, Object> upload(MultipartFile file, String mediaTypeHint);

    /** 按 mediaId 查找原始媒体文件。 */
    Optional<ChatMediaFile> find(String mediaId);

    /** 查找视频封面；缺失时按需生成。 */
    Optional<ChatMediaFile> thumbnail(String mediaId);

    /** 从指定偏移读取媒体内容，流由 Spring MVC 在响应完成后关闭。 */
    InputStreamResource open(ChatMediaFile file, long offset, long length) throws IOException;
}
