package org.jellyfin.mobile

enum class ConnectionState {
    CONNECTING,
    LOCAL,
    REMOTE,
    DISCONNECTED,
    AUTHENTICATION_REQUIRED,
}
