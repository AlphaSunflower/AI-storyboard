package com.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Converts upstream OpenAI-compatible SSE (or a single JSON response) into
 * newline-delimited stream events consumed by MoonAgent.
 */
public final class ChatStreamEventNormalizer {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Consumer<String> sink;
    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder data = new StringBuilder();
    private final StringBuilder standalone = new StringBuilder();

    private int sequence;
    private boolean completed;
    private boolean sawSseFrame;

    public ChatStreamEventNormalizer(Consumer<String> sink) {
        this.sink = sink;
    }

    public synchronized void accept(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        pending.append(new String(bytes, StandardCharsets.UTF_8));
        drainLines(false);
    }

    public synchronized void complete() {
        drainLines(true);
        if (!data.isEmpty()) {
            emitData(data.toString());
            data.setLength(0);
        } else if (!sawSseFrame && !standalone.isEmpty()) {
            emitJson(standalone.toString(), true);
        }
        pending.setLength(0);
        standalone.setLength(0);
        if (!completed) emit("done", "", "stop");
    }

    public synchronized void error(String message) {
        if (completed) return;
        emitError(message == null || message.isBlank() ? "upstream stream failed" : message);
    }

    private void drainLines(boolean flushRemainder) {
        int newline;
        while ((newline = pending.indexOf("\n")) >= 0) {
            String line = pending.substring(0, newline);
            pending.delete(0, newline + 1);
            processLine(stripCarriageReturn(line));
        }
        if (flushRemainder && !pending.isEmpty()) {
            processLine(stripCarriageReturn(pending.toString()));
            pending.setLength(0);
        }
    }

    private void processLine(String line) {
        if (line == null) return;
        if (line.isEmpty()) {
            if (!data.isEmpty()) {
                emitData(data.toString());
                data.setLength(0);
            }
            return;
        }
        if (line.startsWith("data:")) {
            sawSseFrame = true;
            if (!data.isEmpty()) data.append('\n');
            String value = line.substring(5);
            if (value.startsWith(" ")) value = value.substring(1);
            data.append(value);
        } else if (!sawSseFrame) {
            standalone.append(line).append('\n');
        }
    }

    private void emitData(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return;
        if ("[DONE]".equals(value)) {
            if (!completed) emit("done", "", "stop");
            return;
        }
        emitJson(value, false);
    }

    private void emitJson(String raw, boolean standalone) {
        if (raw == null || raw.isBlank()) return;
        try {
            JsonNode root = objectMapper.readTree(raw.trim());
            JsonNode error = root.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                emitError(error.path("message").asText(error.asText("upstream error")));
                return;
            }
            JsonNode choice = root.path("choices").path(0);
            if (choice.isMissingNode() || choice.isNull()) {
                if (standalone) emitError("upstream stream returned no choices");
                return;
            }
            JsonNode payload = choice.has("delta") ? choice.path("delta") : choice.path("message");
            String reasoning = firstText(payload, "reasoning_content", "reasoningContent", "reasoning");
            String content = firstText(payload, "content");
            if (!reasoning.isEmpty()) emit("reasoning_delta", reasoning, "");
            if (!content.isEmpty()) emit("content_delta", content, "");
            String finishReason = choice.path("finish_reason").asText("");
            if (!finishReason.isBlank() || standalone) {
                if (!completed) emit("done", "", finishReason.isBlank() ? "stop" : finishReason);
            }
        } catch (Exception exception) {
            emitError("invalid upstream stream frame: " + exception.getMessage());
        }
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual()) {
                String text = value.asText("");
                if (!text.isEmpty()) return text;
            } else if (value.isArray()) {
                StringBuilder text = new StringBuilder();
                for (JsonNode item : value) {
                    if (item.isTextual()) text.append(item.asText(""));
                    else if (item.has("text")) text.append(item.path("text").asText(""));
                }
                if (!text.isEmpty()) return text.toString();
            }
        }
        return "";
    }

    private void emitError(String message) {
        if (completed) return;
        emit("error", message, "");
        completed = true;
    }

    private void emit(String type, String delta, String finishReason) {
        if (completed && !"error".equals(type)) return;
        try {
            ObjectNode event = objectMapper.createObjectNode();
            event.put("seq", ++sequence);
            event.put("type", type);
            if ("reasoning_delta".equals(type) || "content_delta".equals(type)) {
                event.put("delta", delta == null ? "" : delta);
            }
            if ("done".equals(type)) {
                event.put("finishReason", finishReason == null || finishReason.isBlank()
                        ? "stop" : finishReason);
            }
            if ("error".equals(type)) {
                event.put("message", delta == null ? "" : delta);
            }
            sink.accept(objectMapper.writeValueAsString(event));
            if ("done".equals(type) || "error".equals(type)) completed = true;
        } catch (Exception exception) {
            completed = true;
            throw new IllegalStateException("failed to encode normalized stream event", exception);
        }
    }

    private static String stripCarriageReturn(String value) {
        return value != null && value.endsWith("\r")
                ? value.substring(0, value.length() - 1) : value;
    }
}
