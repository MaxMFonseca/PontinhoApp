package com.google.android.datatransport;

/** Exact factory method used by MediaPipe Tasks 1.1.0's RemoteLoggingClient. */
@FunctionalInterface
public interface TransportFactory {
    <T> Transport<T> getTransport(
            String name,
            Class<T> payloadType,
            Encoding encoding,
            Transformer<T, byte[]> transformer);
}
