package sh.haven.core.vnc.protocol

import sh.haven.core.vnc.VncSession

/**
 * Performs VNC initialization after handshake: sends ClientInit, receives
 * ServerInit, configures pixel format and encodings.
 */
object Initializer {

    fun initialise(session: VncSession) {
        ClientInit(session.config.shared).encode(session.outputStream)

        val serverInit = ServerInit.decode(session.inputStream)
        session.serverInit = serverInit
        session.framebufferWidth = serverInit.framebufferWidth
        session.framebufferHeight = serverInit.framebufferHeight

        val depth = session.config.colorDepth
        val pixelFormat = PixelFormat(
            bitsPerPixel = depth.bitsPerPixel,
            depth = depth.depth,
            bigEndian = false,
            trueColor = depth.trueColor,
            redMax = depth.redMax,
            greenMax = depth.greenMax,
            blueMax = depth.blueMax,
            redShift = depth.redShift,
            greenShift = depth.greenShift,
            blueShift = depth.blueShift,
        )
        session.pixelFormat = pixelFormat
        SetPixelFormat(pixelFormat).encode(session.outputStream)

        val encodings = mutableListOf<Encoding>()
        encodings += Encoding.HEXTILE
        encodings += Encoding.RRE
        encodings += Encoding.COPYRECT
        encodings += Encoding.RAW
        encodings += Encoding.DESKTOP_SIZE
        SetEncodings(encodings).encode(session.outputStream)
        session.outputStream.flush()
    }
}
