package sh.haven.core.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LocalSessionManager"

/**
 * Manages active local terminal sessions.
 * Follows the same lifecycle pattern as MoshSessionManager.
 */
@Singleton
class LocalSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class SessionState(
        val sessionId: String,
        val profileId: String,
        val label: String,
        val status: Status,
        val localSession: LocalSession? = null,
    ) {
        enum class Status { CONNECTING, CONNECTED, DISCONNECTED, ERROR }
    }

    private val _sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionState>> = _sessions.asStateFlow()

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "local-session-io").apply { isDaemon = true }
    }

    val activeSessions: List<SessionState>
        get() = _sessions.value.values.filter {
            it.status == SessionState.Status.CONNECTED ||
                it.status == SessionState.Status.CONNECTING
        }

    fun registerSession(profileId: String, label: String): String {
        val sessionId = UUID.randomUUID().toString()
        _sessions.update { map ->
            map + (sessionId to SessionState(
                sessionId = sessionId,
                profileId = profileId,
                label = label,
                status = SessionState.Status.CONNECTING,
            ))
        }
        return sessionId
    }

    /**
     * Mark a session as connected. The actual process starts when
     * [createTerminalSession] is called.
     */
    fun connectSession(sessionId: String) {
        _sessions.value[sessionId]
            ?: throw IllegalStateException("Session $sessionId not found")
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(status = SessionState.Status.CONNECTED))
        }
    }

    /**
     * Build the shell command for a local session.
     * Uses proot if a rootfs is installed, otherwise falls back to /system/bin/sh.
     */
    fun buildCommand(): Triple<String, Array<String>, Array<String>> {
        val rootfsDir = context.filesDir.resolve("proot/rootfs/alpine")
        val prootBinary = findProotBinary()

        return if (prootBinary != null && rootfsDir.isDirectory) {
            // PRoot with Alpine rootfs
            val cmd = prootBinary
            val args = arrayOf(
                prootBinary,
                "-0",                    // fake root
                "-r", rootfsDir.absolutePath,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "/storage",
                "-w", "/root",
                "/bin/sh", "-l",
            )
            val env = arrayOf(
                "HOME=/root",
                "USER=root",
                "TERM=xterm-256color",
                "LANG=en_US.UTF-8",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "SHELL=/bin/sh",
            )
            Triple(cmd, args, env)
        } else {
            // Fallback: plain Android shell
            val cmd = "/system/bin/sh"
            val args = arrayOf(cmd, "-l")
            val env = arrayOf(
                "HOME=${context.filesDir.absolutePath}",
                "TERM=xterm-256color",
                "LANG=en_US.UTF-8",
                "PATH=/system/bin:/vendor/bin",
                "SHELL=/system/bin/sh",
                "TMPDIR=${context.cacheDir.absolutePath}",
            )
            Triple(cmd, args, env)
        }
    }

    private fun findProotBinary(): String? {
        // Check nativeLibraryDir (bundled as libproot.so)
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val prootInNative = java.io.File(nativeDir, "libproot.so")
        if (prootInNative.canExecute()) return prootInNative.absolutePath

        // Check app files dir (downloaded)
        val prootInFiles = context.filesDir.resolve("proot/proot")
        if (prootInFiles.canExecute()) return prootInFiles.absolutePath

        return null
    }

    /**
     * Create a [LocalSession] for a connected session.
     * The PTY process starts immediately and output flows via [onDataReceived].
     */
    fun createTerminalSession(
        sessionId: String,
        onDataReceived: (ByteArray, Int, Int) -> Unit,
        rows: Int = 24,
        cols: Int = 80,
    ): LocalSession? {
        val session = _sessions.value[sessionId] ?: return null
        if (session.status != SessionState.Status.CONNECTED) return null
        if (session.localSession != null) return null

        val (cmd, args, env) = buildCommand()

        val localSession = LocalSession(
            sessionId = sessionId,
            profileId = session.profileId,
            label = session.label,
            command = cmd,
            args = args,
            env = env,
            onDataReceived = onDataReceived,
            onExited = { exitCode ->
                Log.d(TAG, "Session $sessionId process exited: $exitCode")
                updateStatus(sessionId, SessionState.Status.DISCONNECTED)
            },
        )

        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(localSession = localSession))
        }

        return localSession
    }

    fun isReadyForTerminal(sessionId: String): Boolean {
        val session = _sessions.value[sessionId] ?: return false
        return session.status == SessionState.Status.CONNECTED &&
            session.localSession == null
    }

    fun detachTerminalSession(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        session.localSession?.close()
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(localSession = null))
        }
    }

    fun updateStatus(sessionId: String, status: SessionState.Status) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(status = status))
        }
    }

    fun removeSession(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        _sessions.update { it - sessionId }
        ioExecutor.execute {
            try {
                session.localSession?.close()
            } catch (e: Exception) {
                Log.e(TAG, "tearDown failed for $sessionId", e)
            }
        }
    }

    fun removeAllSessionsForProfile(profileId: String) {
        val toRemove = _sessions.value.values.filter { it.profileId == profileId }
        _sessions.update { map -> map.filterValues { it.profileId != profileId } }
        ioExecutor.execute {
            toRemove.forEach { session ->
                try {
                    session.localSession?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "tearDown failed for ${session.sessionId}", e)
                }
            }
        }
    }

    fun disconnectAll() {
        val snapshot = _sessions.value.values.toList()
        _sessions.update { emptyMap() }
        ioExecutor.execute {
            snapshot.forEach { session ->
                try {
                    session.localSession?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "tearDown failed for ${session.sessionId}", e)
                }
            }
        }
    }

    fun getSessionsForProfile(profileId: String): List<SessionState> =
        _sessions.value.values.filter { it.profileId == profileId }
}
