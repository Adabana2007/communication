package hnist.communication.config;

import hnist.communication.websocket.LocationWebSocketHandler;
import hnist.communication.websocket.PhoneCamAuthInterceptor;
import hnist.communication.websocket.PhoneCamWebSocketHandler;
import hnist.communication.websocket.WebSocketAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/** 注册位置上报与手机摄像头两条 WebSocket 通道，并绑定各自的握手鉴权拦截器。 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final LocationWebSocketHandler locationHandler;
    private final PhoneCamWebSocketHandler phoneCamHandler;
    private final WebSocketAuthInterceptor authInterceptor;
    private final PhoneCamAuthInterceptor phoneCamAuthInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(locationHandler, "/ws/location")
                .addInterceptors(authInterceptor)
                .setAllowedOrigins("*");
        registry.addHandler(phoneCamHandler, "/ws/phone-camera")
                .addInterceptors(phoneCamAuthInterceptor)
                .setAllowedOrigins("*");
    }

    /**
     * 调大 WebSocket 消息缓冲区（默认 8KB）：PTT 语音以 base64 编码的 mp3 通过 WebSocket 广播，
     * 60 秒录音 base64 后约 160KB，默认缓冲会直接拒收。
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainerFactory() {
        ServletServerContainerFactoryBean factory = new ServletServerContainerFactoryBean();
        factory.setMaxTextMessageBufferSize(512 * 1024);   // 512KB
        factory.setMaxBinaryMessageBufferSize(512 * 1024); // 512KB
        return factory;
    }
}
