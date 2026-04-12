package sh.haven.app

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import javax.inject.Inject

@HiltAndroidApp
class HavenApp : Application() {

    @Inject lateinit var mcpServer: sh.haven.app.agent.McpServer
    @Inject lateinit var preferencesRepository: sh.haven.core.data.preferences.UserPreferencesRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        // Register Shizuku binder listeners early so the async callback
        // has time to fire before any UI checks isShizukuAvailable().
        sh.haven.core.local.WaylandSocketHelper.initShizukuListeners()

        // MCP agent endpoint is OFF by default — it exposes state that
        // local processes (or an AI agent you've pointed at it) can
        // query, so it must be an explicit opt-in. When the user toggles
        // it in Settings we react by starting or stopping the server.
        preferencesRepository.mcpAgentEndpointEnabled
            .distinctUntilChanged()
            .onEach { enabled ->
                if (enabled) mcpServer.start() else mcpServer.stop()
            }
            .launchIn(appScope)
    }
}
