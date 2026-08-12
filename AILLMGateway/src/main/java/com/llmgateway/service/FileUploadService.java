package com.llmgateway.service;

/**
 * 上游文件上传代理：将文件上传到 MiniMax 平台并返回 mm_file://{file_id}，
 * 供视频多模态参考（reference_image / reference_video / reference_audio）引用。
 */
public interface FileUploadService {

    /**
     * 上传文件到上游并返回 mm_file://{file_id}。
     *
     * @param contentType 原始 Content-Type（含 multipart boundary）
     * @param bodyBytes   multipart 请求体字节流
     * @return mm_file://{file_id}
     */
    String upload(String contentType, byte[] bodyBytes);
}
