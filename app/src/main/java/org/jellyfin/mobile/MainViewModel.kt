package org.jellyfin.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.app.Endpoint
import org.jellyfin.mobile.app.EndpointResolver
import org.jellyfin.mobile.app.buildWebSessionCredentials
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.data.entity.UserEntity
import java.util.UUID
import timber.log.Timber

class MainViewModel(
    app: Application,
    private val apiClientController: ApiClientController,
    private val endpointResolver: EndpointResolver,
) : AndroidViewModel(app) {
    private val _serverState: MutableStateFlow<ServerState> = MutableStateFlow(ServerState.Pending)
    val serverState: StateFlow<ServerState> get() = _serverState

    private val _userState: MutableStateFlow<UserState> = MutableStateFlow(UserState.Pending)
    val userState: StateFlow<UserState> get() = _userState
    private val _connectionState = MutableStateFlow(ConnectionState.CONNECTING)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    private val switchMutex = Mutex()
    private var activeEndpoint: Endpoint? = null
    private var lastAutomaticSwitchAt = 0L

    init {
        viewModelScope.launch {
            switchMutex.withLock { autoConnect() }
        }
    }

    private suspend fun autoConnect() {
        Timber.i("Startup endpoint selection started")
        _connectionState.value = ConnectionState.CONNECTING
        if (endpointResolver.configuredEndpoints().isEmpty()) {
            refreshServer()
            refreshUser()
            _connectionState.value = if (_serverState.value is ServerState.Available) {
                ConnectionState.REMOTE
            } else {
                ConnectionState.DISCONNECTED
            }
            return
        }
        val endpoint = endpointResolver.select()
        if (endpoint != null) {
            connect(endpoint)
        } else {
            Timber.w("Startup endpoint selection finished: OFFLINE")
            _serverState.value = ServerState.Unset
            _userState.value = UserState.Unset
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    suspend fun switchServer(hostname: String) {
        apiClientController.setupServer(hostname)
        activeEndpoint = endpointResolver.configuredEndpoints().firstOrNull { it.url == hostname.trimEnd('/') }
        _connectionState.value = activeEndpoint?.let { if (it.isLocal) ConnectionState.LOCAL else ConnectionState.REMOTE }
            ?: ConnectionState.REMOTE
        refreshUser()
        refreshServer()
    }

    private suspend fun connect(endpoint: Endpoint) {
        Timber.i("Applying selected endpoint %s url=%s", if (endpoint.isLocal) "LOCAL" else "REMOTE", endpoint.url)
        apiClientController.setupServer(endpoint.url)
        activeEndpoint = endpoint
        Timber.i("Active Jellyfin endpoint changed to %s", if (endpoint.isLocal) "LOCAL" else "REMOTE")
        _connectionState.value = if (endpoint.isLocal) ConnectionState.LOCAL else ConnectionState.REMOTE
        refreshUser()
        // Publish the server only after persisted credentials are ready for WebView restoration.
        refreshServer()
    }

    suspend fun retryConnection() = switchMutex.withLock { autoConnect() }

    suspend fun switchToAlternateEndpoint(): Boolean = switchMutex.withLock {
        Timber.i("Endpoint fallback requested; active=%s", activeEndpoint?.url ?: "none")
        if (System.currentTimeMillis() - lastAutomaticSwitchAt < 5_000L) return false
        val current = activeEndpoint ?: return false
        val alternate = endpointResolver.alternate(current.url) ?: return false
        lastAutomaticSwitchAt = System.currentTimeMillis()
        connect(alternate)
        Timber.i("Endpoint fallback completed: %s", if (alternate.isLocal) "LOCAL" else "REMOTE")
        true
    }

    suspend fun setupUser(serverId: Long, userId: UUID, accessToken: String) {
        apiClientController.setupUser(serverId, userId, accessToken)
        refreshUser()
    }

    /** Credentials are restored only into Jellyfin WebView local storage for the active origin. */
    fun savedWebCredentialsJson(server: ServerEntity): String? {
        val user = (userState.value as? UserState.Available)?.user ?: return null
        return buildWebSessionCredentials(server, user)
    }

    private suspend fun refreshServer() {
        val serverEntity = apiClientController.loadSavedServer()
        _serverState.value = serverEntity?.let { entity -> ServerState.Available(entity) } ?: ServerState.Unset
    }

    private suspend fun refreshUser() {
        val userEntity = apiClientController.loadSavedUser()
        _userState.value = userEntity?.let { entity -> UserState.Available(entity) } ?: UserState.Unset
    }

    /**
     * Temporarily unset the selected server to be able to connect to a different one
     */
    fun resetServer() {
        _serverState.value = ServerState.Unset
        _userState.value = UserState.Unset
    }
}

sealed class ServerState {
    open val server: ServerEntity? = null

    object Pending : ServerState()
    object Unset : ServerState()
    class Available(override val server: ServerEntity) : ServerState()
}

sealed class UserState {
    open val user: UserEntity? = null

    object Pending : UserState()
    object Unset : UserState()
    class Available(override val user: UserEntity) : UserState()
}
