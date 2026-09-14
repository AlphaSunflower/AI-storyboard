package com.storyboard.asset.storage;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.Closeable;
import java.io.IOException;

/** MinIO 对象流及其响应元数据；调用方必须关闭。 */
public record StoredObject(
        ResponseInputStream<GetObjectResponse> stream,
        long contentLength,
        String contentType,
        String contentRange
) implements Closeable {

    @Override
    public void close() throws IOException {
        stream.close();
    }
}
