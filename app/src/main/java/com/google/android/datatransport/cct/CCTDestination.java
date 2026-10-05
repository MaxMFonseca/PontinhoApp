package com.google.android.datatransport.cct;

import com.google.android.datatransport.runtime.Destination;

/** Non-networking destination token required by MediaPipe Tasks 1.1.0 linkage. */
public final class CCTDestination implements Destination {
    public static final CCTDestination INSTANCE = new CCTDestination();

    private CCTDestination() { }

    @Override
    public String getName() {
        return "offline";
    }

    @Override
    public byte[] getExtras() {
        return null;
    }
}
