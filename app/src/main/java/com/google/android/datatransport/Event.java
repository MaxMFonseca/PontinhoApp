package com.google.android.datatransport;

/** Minimal event envelope needed by MediaPipe Tasks 1.1.0. */
public final class Event<T> {
    private final T payload;

    private Event(T payload) {
        this.payload = payload;
    }

    public static <T> Event<T> ofData(T payload) {
        return new Event<>(payload);
    }

    public T getPayload() {
        return payload;
    }
}
