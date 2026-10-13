package com.example.line.service;

/** Redis implementations must acquire atomically and retain in-flight reservations. */
public interface EventDeduplicator {
    boolean tryAcquire(String eventId);
    void complete(String eventId);
    void release(String eventId);
}
