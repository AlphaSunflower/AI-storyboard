package com.moon.moonagent.ai.agent.document;

public class DocumentParseException extends RuntimeException {

    private final String code;

    public DocumentParseException(String code, String message) {
        super(message);
        this.code = code;
    }

    public DocumentParseException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
