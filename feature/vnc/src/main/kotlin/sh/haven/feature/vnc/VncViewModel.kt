package sh.haven.feature.vnc

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import sh.haven.core.ssh.SshSessionManager
import sh.haven.core.vnc.ColorDepth
import sh.haven.core.vnc.VncClient
import sh.haven.core.vnc.VncConfig
import javax.inject.Inject

private const val TAG = "VncViewModel"

/** An active SSH session that can be used for tunneling. */
data class SshTunnelOption(
    val sessionId: String,
    val label: String,
    val profileId: String,
)

@HiltViewModel
class VncViewModel @Inject constructor(
    private val sshSessionManager: SshSessionManager,
) : ViewModel() {

    private val _frame = MutableStateFlow<Bitmap?>(null)
    val frame: StateFlow<Bitmap?> = _frame.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _serverName = MutableStateFlow<String?>(null)
    val serverName: StateFlow<String?> = _serverName.asStateFlow()

    private var client: VncClient? = null
    private var tunnelPort: Int? = null
    private var tunnelSessionId: String? = null

    fun setActive(active: Boolean) {
        client?.paused = !active
    }

    /** List active SSH sessions available for tunneling. */
    fun getActiveSshSessions(): List<SshTunnelOption> {
        return sshSessionManager.activeSessions.map { session ->
            SshTunnelOption(
                sessionId = session.sessionId,
                label = session.label,
                profileId = session.profileId,
            )
        }
    }

    /**
     * Connect VNC through an SSH tunnel.
     * Creates a local port forward and connects VNC to localhost.
     */
    fun connectViaSsh(sessionId: String, remoteHost: String, remotePort: Int, password: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _error.value = null
                val session = sshSessionManager.getSession(sessionId)
                    ?: throw IllegalStateException("SSH session not found")
                val localPort = session.client.setPortForwardingL(
                    "127.0.0.1", 0, remoteHost, remotePort,
                )
                tunnelPort = localPort
                tunnelSessionId = sessionId
                Log.d(TAG, "SSH tunnel: localhost:$localPort -> $remoteHost:$remotePort")
                doConnect("127.0.0.1", localPort, password)
            } catch (e: Exception) {
                Log.e(TAG, "SSH tunnel setup failed", e)
                _error.value = e.message ?: "SSH tunnel failed"
            }
        }
    }

    fun connect(host: String, port: Int, password: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _error.value = null
                doConnect(host, port, password)
            } catch (e: Exception) {
                Log.e(TAG, "VNC connect failed", e)
                _error.value = e.message ?: "Connection failed"
            }
        }
    }

    private fun doConnect(host: String, port: Int, password: String?) {
        val config = VncConfig().apply {
            colorDepth = ColorDepth.BPP_24_TRUE
            targetFps = 10
            shared = true
            if (!password.isNullOrEmpty()) {
                passwordSupplier = { password }
            }
            onScreenUpdate = { bitmap ->
                _frame.value = bitmap
            }
            onError = { e ->
                Log.e(TAG, "VNC error", e)
                _error.value = e.message ?: "VNC error"
                _connected.value = false
            }
            onRemoteClipboard = { text ->
                Log.d(TAG, "Remote clipboard: ${text.take(100)}")
            }
        }
        val c = VncClient(config)
        client = c
        c.start(host, port)
        _serverName.value = c.toString()
        _connected.value = true
    }

    fun disconnect() {
        viewModelScope.launch(Dispatchers.IO) {
            client?.stop()
            client = null
            // Tear down SSH tunnel if one was created
            val tp = tunnelPort
            val tsId = tunnelSessionId
            if (tp != null && tsId != null) {
                try {
                    sshSessionManager.getSession(tsId)?.client
                        ?.delPortForwardingL("127.0.0.1", tp)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to remove SSH tunnel", e)
                }
                tunnelPort = null
                tunnelSessionId = null
            }
            _connected.value = false
            _frame.value = null
        }
    }

    fun sendPointer(x: Int, y: Int) {
        viewModelScope.launch(Dispatchers.IO) { client?.moveMouse(x, y) }
    }

    fun pressButton(button: Int = 1) {
        viewModelScope.launch(Dispatchers.IO) { client?.updateMouseButton(button, true) }
    }

    fun releaseButton(button: Int = 1) {
        viewModelScope.launch(Dispatchers.IO) { client?.updateMouseButton(button, false) }
    }

    fun sendClick(x: Int, y: Int, button: Int = 1) {
        viewModelScope.launch(Dispatchers.IO) {
            client?.moveMouse(x, y)
            client?.click(button)
        }
    }

    fun sendKey(keySym: Int, pressed: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { client?.updateKey(keySym, pressed) }
    }

    fun typeKey(keySym: Int) {
        viewModelScope.launch(Dispatchers.IO) { client?.type(keySym) }
    }

    fun scrollUp() {
        viewModelScope.launch(Dispatchers.IO) { client?.click(4) }
    }

    fun scrollDown() {
        viewModelScope.launch(Dispatchers.IO) { client?.click(5) }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
