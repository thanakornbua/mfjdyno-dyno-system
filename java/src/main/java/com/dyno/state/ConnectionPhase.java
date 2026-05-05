package com.dyno.state;

public enum ConnectionPhase {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    RECONNECT_WAIT
}
