package com.example.line.service;

/** Sanitized failure; never retain sensitive SDK exception messages or bodies. */
public class LineSendException extends RuntimeException {
    public enum Reason { CLIENT_ERROR, TRANSIENT_FAILURE, UNEXPECTED_FAILURE, INTERRUPTED }
    private final Reason reason;
    private final Integer upstreamStatus;
    public LineSendException(Reason reason, Integer upstreamStatus) {
        super("LINE message request failed: " + reason);
        this.reason = reason;
        this.upstreamStatus = upstreamStatus;
    }
    public Reason getReason() { return reason; }
    public Integer getUpstreamStatus() { return upstreamStatus; }
}
