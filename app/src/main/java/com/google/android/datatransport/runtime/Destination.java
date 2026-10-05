package com.google.android.datatransport.runtime;

/** Binary signature consumed by TransportRuntime.newFactory. */
public interface Destination {
    String getName();

    byte[] getExtras();
}
