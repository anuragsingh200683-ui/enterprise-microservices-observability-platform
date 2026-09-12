package com.enterprise.projectservice.exception;

import java.time.Instant;
import java.util.List;

public class ApiError {

    private Instant timestamp;
    private int status;
    private String error;
    private String path;
    private List<String> messages;

    public ApiError(int status, String error, String path, List<String> messages) {
        this.timestamp = Instant.now();
        this.status = status;
        this.error = error;
        this.path = path;
        this.messages = messages;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public int getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public String getPath() {
        return path;
    }

    public List<String> getMessages() {
        return messages;
    }
}
