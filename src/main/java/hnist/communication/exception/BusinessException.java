package hnist.communication.exception;

/**
 * 业务异常：message 面向用户，由 GlobalExceptionHandler 原样放进 Result.message。
 */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}
