package com.storyboard.asset.client;

/** asset-service 调用失败；调用方只处理稳定错误码，不解析下游原始响应。 */
public class AssetServiceException extends RuntimeException {

    private final int code;
    private final int httpStatus;
    private final String correlationId;

    public AssetServiceException(int code, int httpStatus, String message, String correlationId) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.correlationId = correlationId;
    }

    public int code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String correlationId() {
        return correlationId;
    }
}
