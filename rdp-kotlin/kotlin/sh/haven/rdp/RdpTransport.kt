package sh.haven.rdp

/**
 * Stub Kotlin API for rdp-transport.
 *
 * In production, UniFFI generates these bindings from the Rust crate.
 * This file provides compilable types so that the Gradle build
 * succeeds before the native library is built.
 *
 * The actual runtime behavior requires the native .so to be loaded.
 */

/** RDP connection configuration. */
data class RdpConfig(
    val username: String,
    val password: String,
    val domain: String = "",
    val width: UShort = 1920u,
    val height: UShort = 1080u,
    val colorDepth: UByte = 32u,
)

/** ARGB_8888 pixel data for one frame. */
data class FrameData(
    val width: UShort,
    val height: UShort,
    val pixels: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrameData) return false
        return width == other.width && height == other.height && pixels.contentEquals(other.pixels)
    }

    override fun hashCode(): Int {
        var result = width.hashCode()
        result = 31 * result + height.hashCode()
        result = 31 * result + pixels.contentHashCode()
        return result
    }
}

/** Dirty rectangle from a graphics update. */
data class RdpRect(
    val x: UShort,
    val y: UShort,
    val width: UShort,
    val height: UShort,
)

/** Mouse button identifiers. */
enum class MouseButton {
    LEFT,
    RIGHT,
    MIDDLE,
}

/** Callback for frame updates from the server. */
interface FrameCallback {
    fun onFrameUpdate(x: UShort, y: UShort, w: UShort, h: UShort)
    fun onResize(width: UShort, height: UShort)
}

/** Callback for clipboard data from the server. */
interface ClipboardCallback {
    fun onRemoteClipboard(text: String)
}

/** RDP error types. */
sealed class RdpException(message: String) : Exception(message) {
    class ConnectionFailed : RdpException("Connection failed")
    class AuthenticationFailed : RdpException("Authentication failed")
    class ProtocolError : RdpException("Protocol error")
    class TlsError : RdpException("TLS error")
    class Disconnected : RdpException("Disconnected")
    class IoError : RdpException("I/O error")
}

/**
 * RDP client wrapping the native IronRDP library via UniFFI.
 *
 * Usage:
 * ```
 * val client = RdpClient(config)
 * client.connect("hostname", 3389)
 * // ... poll framebuffer or use callbacks ...
 * client.disconnect()
 * ```
 */
class RdpClient(private val config: RdpConfig) {

    @Volatile
    private var nativePtr: Long = 0

    private var connected = false

    init {
        try {
            System.loadLibrary("rdp_transport")
            nativePtr = nativeNew(
                config.username, config.password, config.domain,
                config.width.toInt(), config.height.toInt(), config.colorDepth.toInt(),
            )
        } catch (e: UnsatisfiedLinkError) {
            // Native library not available — stub mode for compilation
            nativePtr = 0
        }
    }

    @Throws(RdpException::class)
    fun connect(host: String, port: UShort) {
        if (nativePtr != 0L) {
            val result = nativeConnect(nativePtr, host, port.toInt())
            if (result != 0) {
                throw when (result) {
                    1 -> RdpException.AuthenticationFailed()
                    2 -> RdpException.TlsError()
                    else -> RdpException.ConnectionFailed()
                }
            }
        }
        connected = true
    }

    fun disconnect() {
        connected = false
        if (nativePtr != 0L) {
            nativeDisconnect(nativePtr)
        }
    }

    fun isConnected(): Boolean = connected && (nativePtr == 0L || nativeIsConnected(nativePtr))

    fun getFramebuffer(): FrameData? {
        if (nativePtr == 0L) return null
        return nativeGetFramebuffer(nativePtr)
    }

    fun getDirtyRects(): List<RdpRect> {
        if (nativePtr == 0L) return emptyList()
        return nativeGetDirtyRects(nativePtr)
    }

    fun setFrameCallback(cb: FrameCallback) {
        if (nativePtr != 0L) {
            nativeSetFrameCallback(nativePtr, cb)
        }
    }

    fun sendKey(scancode: UShort, pressed: Boolean) {
        if (nativePtr != 0L) nativeSendKey(nativePtr, scancode.toInt(), pressed)
    }

    fun sendUnicodeKey(ch: UInt, pressed: Boolean) {
        if (nativePtr != 0L) nativeSendUnicodeKey(nativePtr, ch.toInt(), pressed)
    }

    fun sendMouseMove(x: UShort, y: UShort) {
        if (nativePtr != 0L) nativeSendMouseMove(nativePtr, x.toInt(), y.toInt())
    }

    fun sendMouseButton(button: MouseButton, pressed: Boolean) {
        if (nativePtr != 0L) nativeSendMouseButton(nativePtr, button.ordinal, pressed)
    }

    fun sendMouseWheel(vertical: Boolean, delta: Short) {
        if (nativePtr != 0L) nativeSendMouseWheel(nativePtr, vertical, delta.toInt())
    }

    fun sendClipboardText(text: String) {
        if (nativePtr != 0L) nativeSendClipboardText(nativePtr, text)
    }

    fun setClipboardCallback(cb: ClipboardCallback) {
        if (nativePtr != 0L) nativeSetClipboardCallback(nativePtr, cb)
    }

    protected fun finalize() {
        if (nativePtr != 0L) {
            nativeFree(nativePtr)
            nativePtr = 0
        }
    }

    // Native method stubs — implemented by UniFFI-generated JNI
    private external fun nativeNew(
        username: String, password: String, domain: String,
        width: Int, height: Int, colorDepth: Int,
    ): Long

    private external fun nativeConnect(ptr: Long, host: String, port: Int): Int
    private external fun nativeDisconnect(ptr: Long)
    private external fun nativeIsConnected(ptr: Long): Boolean
    private external fun nativeGetFramebuffer(ptr: Long): FrameData?
    private external fun nativeGetDirtyRects(ptr: Long): List<RdpRect>
    private external fun nativeSetFrameCallback(ptr: Long, cb: FrameCallback)
    private external fun nativeSendKey(ptr: Long, scancode: Int, pressed: Boolean)
    private external fun nativeSendUnicodeKey(ptr: Long, ch: Int, pressed: Boolean)
    private external fun nativeSendMouseMove(ptr: Long, x: Int, y: Int)
    private external fun nativeSendMouseButton(ptr: Long, button: Int, pressed: Boolean)
    private external fun nativeSendMouseWheel(ptr: Long, vertical: Boolean, delta: Int)
    private external fun nativeSendClipboardText(ptr: Long, text: String)
    private external fun nativeSetClipboardCallback(ptr: Long, cb: ClipboardCallback)
    private external fun nativeFree(ptr: Long)
}
