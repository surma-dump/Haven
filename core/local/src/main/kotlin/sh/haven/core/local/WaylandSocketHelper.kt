package sh.haven.core.local

import android.util.Log

/**
 * Creates a symlink to the Wayland socket in /data/local/tmp/ via Shizuku
 * so external clients (Termux, chroot) can connect.
 *
 * Requires Shizuku to be installed and permission granted.
 * Gracefully no-ops if Shizuku is unavailable.
 */
object WaylandSocketHelper {
    private const val TAG = "WaylandSocket"
    private const val LINK_DIR = "/data/local/tmp/haven-wayland"

    /**
     * Try to create a symlink at /data/local/tmp/haven-wayland/wayland-0
     * pointing to the app's Wayland socket. Returns true if successful.
     */
    fun tryCreateSymlink(socketPath: String): Boolean {
        if (!isShizukuAvailable()) {
            Log.d(TAG, "Shizuku not available — external socket access disabled")
            return false
        }
        if (!hasShizukuPermission()) {
            Log.d(TAG, "Shizuku permission not granted")
            return false
        }
        return try {
            val cmd = "mkdir -p $LINK_DIR && chmod 0755 $LINK_DIR && " +
                "rm -f $LINK_DIR/wayland-0 && " +
                "ln -s $socketPath $LINK_DIR/wayland-0"
            val result = runShizukuCommand(cmd)
            if (result == 0) {
                Log.i(TAG, "Symlink created: $LINK_DIR/wayland-0 → $socketPath")
                true
            } else {
                Log.w(TAG, "Symlink command failed with exit code $result")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku symlink failed: ${e.message}")
            false
        }
    }

    /** Clean up the symlink when the compositor stops. */
    fun tryRemoveSymlink() {
        if (!isShizukuAvailable() || !hasShizukuPermission()) return
        try {
            runShizukuCommand("rm -f $LINK_DIR/wayland-0")
        } catch (_: Exception) {}
    }

    fun isShizukuAvailable(): Boolean {
        return try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getMethod("pingBinder")
            method.invoke(null) as Boolean
        } catch (_: Exception) {
            false
        }
    }

    fun hasShizukuPermission(): Boolean {
        return try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getMethod("checkSelfPermission")
            (method.invoke(null) as Int) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    fun requestPermission() {
        try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getMethod("requestPermission", Int::class.java)
            method.invoke(null, 42)
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku permission request failed: ${e.message}")
        }
    }

    private fun runShizukuCommand(cmd: String): Int {
        // Use Shizuku's remote process to execute shell commands
        val clazz = Class.forName("rikka.shizuku.Shizuku")
        val method = clazz.getMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        val process = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        return process.waitFor()
    }
}
