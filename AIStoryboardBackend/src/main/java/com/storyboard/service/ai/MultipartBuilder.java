package com.storyboard.service.ai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 手动构建 multipart/form-data 请求体的工具类。
 * JDK HttpClient 不内置 multipart 支持，此工具提供轻量级替代。
 */
final class MultipartBuilder {

    private final String boundary;
    private final List<Part> parts = new ArrayList<>();

    record Part(String name, String filename, String contentType, byte[] data) {}

    MultipartBuilder() {
        this.boundary = "----Boundary" + java.util.UUID.randomUUID();
    }

    String boundary() { return boundary; }

    MultipartBuilder field(String name, String value) {
        parts.add(new Part(name, null, null, value.getBytes(StandardCharsets.UTF_8)));
        return this;
    }

    MultipartBuilder file(String name, String filename, String contentType, byte[] data) {
        parts.add(new Part(name, filename, contentType, data));
        return this;
    }

    byte[] build() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] crlf = "\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] dd = "--".getBytes(StandardCharsets.UTF_8);
        byte[] bnd = boundary.getBytes(StandardCharsets.UTF_8);

        for (Part p : parts) {
            out.write(dd); out.write(bnd); out.write(crlf);
            String disp = "Content-Disposition: form-data; name=\"" + p.name + "\"";
            if (p.filename != null) disp += "; filename=\"" + p.filename + "\"";
            out.write(disp.getBytes(StandardCharsets.UTF_8)); out.write(crlf);
            if (p.contentType != null) {
                out.write(("Content-Type: " + p.contentType).getBytes(StandardCharsets.UTF_8));
                out.write(crlf);
            }
            out.write(crlf);
            out.write(p.data);
            out.write(crlf);
        }
        out.write(dd); out.write(bnd); out.write(dd); out.write(crlf);
        return out.toByteArray();
    }
}
