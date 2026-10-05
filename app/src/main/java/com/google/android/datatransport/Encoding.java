package com.google.android.datatransport;

import java.util.Objects;

/** Binary-compatible value used only by MediaPipe's disabled telemetry client. */
public final class Encoding {
    private final String name;

    private Encoding(String name) {
        this.name = Objects.requireNonNull(name);
    }

    public static Encoding of(String name) {
        return new Encoding(name);
    }

    public String getName() {
        return name;
    }
}
