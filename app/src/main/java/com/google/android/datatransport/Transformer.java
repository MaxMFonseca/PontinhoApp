package com.google.android.datatransport;

/** Functional signature referenced by MediaPipe's generated lambda. */
@FunctionalInterface
public interface Transformer<T, U> {
    U apply(T value);
}
