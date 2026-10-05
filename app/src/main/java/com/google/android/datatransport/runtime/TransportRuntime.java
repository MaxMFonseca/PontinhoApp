package com.google.android.datatransport.runtime;

import android.content.Context;

import com.google.android.datatransport.Encoding;
import com.google.android.datatransport.Transport;
import com.google.android.datatransport.TransportFactory;
import com.google.android.datatransport.Transformer;

/**
 * Pinned MediaPipe 1.1.0 compatibility shim. The upstream logger has no opt-out hook, so this
 * implements only the three linked runtime methods and deterministically drops every event.
 */
public final class TransportRuntime {
    private static final TransportRuntime INSTANCE = new TransportRuntime();
    private static final TransportFactory OFFLINE_FACTORY = new TransportFactory() {
        @Override
        public <T> Transport<T> getTransport(
                String name,
                Class<T> payloadType,
                Encoding encoding,
                Transformer<T, byte[]> transformer) {
            return event -> { };
        }
    };

    private TransportRuntime() { }

    public static void initialize(Context context) {
        // Intentionally no-op: no scheduler, backend, storage, or network transport is installed.
    }

    public static TransportRuntime getInstance() {
        return INSTANCE;
    }

    public TransportFactory newFactory(Destination destination) {
        return OFFLINE_FACTORY;
    }
}
