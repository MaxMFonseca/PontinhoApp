package com.google.android.datatransport;

/** Offline transport surface: events are accepted and discarded locally. */
@FunctionalInterface
public interface Transport<T> {
    void send(Event<T> event);
}
