package hnist.communication.manager.camera;

import hnist.communication.exception.BusinessException;
import hnist.communication.entity.Camera;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** 摄像头源地址与播放路径的纯计算工具。 */
@Slf4j
public final class CameraMediaUtils {

    private CameraMediaUtils() {
    }

    public static String playbackPath(Camera camera) {
        if ("compatible".equals(camera.getPlaybackMode())) {
            return conformancePath(camera);
        }
        return !"existing".equals(camera.getPlaybackMode())
                || camera.getPlaybackPath() == null
                || camera.getPlaybackPath().isBlank()
                ? camera.getMediamtxPath()
                : camera.getPlaybackPath();
    }

    public static String sourceUrl(Camera camera) {
        try {
            URI uri = URI.create(camera.getRtspUrl().replace("&amp;", "&"));
            if (uri.getUserInfo() != null || camera.getUsername() == null || camera.getPassword() == null) {
                return uri.toASCIIString();
            }
            return new URI(uri.getScheme(), camera.getUsername() + ":" + camera.getPassword(),
                    uri.getHost(), uri.getPort(), uri.getPath(), uri.getQuery(), null).toASCIIString();
        } catch (Exception e) {
            log.warn("摄像头RTSP地址解析失败: cameraId={}", camera.getId());
            throw new BusinessException("摄像头 RTSP 地址无效");
        }
    }

    public static String sourceFingerprint(Camera camera) {
        try {
            String value = sourceUrl(camera) + "\n" + Objects.toString(camera.getSourceProtocol(), "automatic");
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String conformancePath(Camera camera) {
        return "cam_" + camera.getId() + "_compatible";
    }
}
