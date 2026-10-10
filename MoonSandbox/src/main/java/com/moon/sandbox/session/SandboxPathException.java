package com.moon.sandbox.session;

public class SandboxPathException extends RuntimeException {

    public SandboxPathException(String message) {
        super(message);
    }

    public SandboxPathException(String message, Throwable cause) {
        super(message, cause);
    }
}
