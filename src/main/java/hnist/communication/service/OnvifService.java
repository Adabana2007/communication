package hnist.communication.service;

/** ONVIF 协议客户端：通过 SOAP 获取摄像头 RTSP 地址。 */
public interface OnvifService {

    /** 获取 RTSP 地址；认证失败、超时等任何探测异常都返回 null。 */
    String getRtspUrl(String ip, int port, String username, String password);
}
