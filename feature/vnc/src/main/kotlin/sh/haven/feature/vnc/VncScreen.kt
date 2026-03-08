package sh.haven.feature.vnc

import android.graphics.Bitmap
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlin.math.abs

@Composable
fun VncScreen(
    isActive: Boolean = true,
    pendingHost: String? = null,
    pendingPort: Int? = null,
    pendingPassword: String? = null,
    pendingSshForward: Boolean = false,
    pendingSshSessionId: String? = null,
    onPendingConsumed: () -> Unit = {},
    onConnectedChanged: (Boolean) -> Unit = {},
    viewModel: VncViewModel = hiltViewModel(),
) {
    LaunchedEffect(isActive) { viewModel.setActive(isActive) }

    val connected by viewModel.connected.collectAsState()

    // Report connected state to parent for pager swipe control
    LaunchedEffect(connected) { onConnectedChanged(connected) }

    // Auto-connect when navigated from terminal
    LaunchedEffect(pendingHost) {
        if (pendingHost != null) {
            if (pendingSshForward && pendingSshSessionId != null) {
                viewModel.connectViaSsh(
                    pendingSshSessionId, "localhost", pendingPort ?: 5900, pendingPassword,
                )
            } else {
                viewModel.connect(pendingHost, pendingPort ?: 5900, pendingPassword)
            }
            onPendingConsumed()
        }
    }

    val frame by viewModel.frame.collectAsState()
    val error by viewModel.error.collectAsState()

    if (connected && frame != null) {
        VncViewer(
            frame = frame!!,
            onTap = { x, y -> viewModel.sendClick(x, y) },
            onDragStart = { x, y ->
                viewModel.sendPointer(x, y)
                viewModel.pressButton(1)
            },
            onDrag = { x, y -> viewModel.sendPointer(x, y) },
            onDragEnd = { viewModel.releaseButton(1) },
            onScrollUp = { viewModel.scrollUp() },
            onScrollDown = { viewModel.scrollDown() },
            onTypeChar = { ch -> viewModel.typeKey(charToKeySym(ch)) },
            onKeyDown = { keySym -> viewModel.sendKey(keySym, true) },
            onKeyUp = { keySym -> viewModel.sendKey(keySym, false) },
            onDisconnect = { viewModel.disconnect() },
        )
    } else {
        val sshSessions = remember { viewModel.getActiveSshSessions() }
        VncConnectForm(
            error = error,
            sshSessions = sshSessions,
            onConnect = { host, port, password ->
                viewModel.connect(host, port, password)
            },
            onConnectViaSsh = { sessionId, host, port, password ->
                viewModel.connectViaSsh(sessionId, host, port, password)
            },
        )
    }
}

@Composable
private fun VncConnectForm(
    error: String?,
    sshSessions: List<SshTunnelOption> = emptyList(),
    onConnect: (String, Int, String?) -> Unit,
    onConnectViaSsh: (sessionId: String, host: String, port: Int, password: String?) -> Unit = { _, _, _, _ -> },
) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("5900") }
    var password by rememberSaveable { mutableStateOf("") }
    var sshForward by rememberSaveable { mutableStateOf(false) }
    var selectedSshIndex by rememberSaveable { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text("VNC Connection", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text(if (sshForward) "Remote host" else "Host") },
            placeholder = { Text(if (sshForward) "localhost" else "192.168.1.100") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("Port") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        if (sshSessions.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                androidx.compose.material3.Checkbox(
                    checked = sshForward,
                    onCheckedChange = {
                        sshForward = it
                        if (it && host.isBlank()) host = "localhost"
                    },
                )
                Text("Tunnel through SSH")
            }
            if (sshForward && sshSessions.size > 1) {
                Spacer(Modifier.height(4.dp))
                sshSessions.forEachIndexed { index, session ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp),
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = selectedSshIndex == index,
                            onClick = { selectedSshIndex = index },
                        )
                        Text(session.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                val p = port.toIntOrNull() ?: 5900
                val pw = password.ifEmpty { null }
                if (sshForward && sshSessions.isNotEmpty()) {
                    val session = sshSessions[selectedSshIndex.coerceIn(sshSessions.indices)]
                    onConnectViaSsh(session.sessionId, host.ifBlank { "localhost" }, p, pw)
                } else {
                    onConnect(host, p, pw)
                }
            },
            enabled = if (sshForward) sshSessions.isNotEmpty() else host.isNotBlank(),
        ) {
            Text("Connect")
        }

        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun VncViewer(
    frame: Bitmap,
    onTap: (Int, Int) -> Unit,
    onDragStart: (Int, Int) -> Unit,
    onDrag: (Int, Int) -> Unit,
    onDragEnd: () -> Unit,
    onScrollUp: () -> Unit,
    onScrollDown: () -> Unit,
    onTypeChar: (Char) -> Unit,
    onKeyDown: (Int) -> Unit,
    onKeyUp: (Int) -> Unit,
    onDisconnect: () -> Unit,
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val imageBitmap = remember(frame) { frame.asImageBitmap() }

    // Zoom & pan state
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

    // Keyboard
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var keyboardVisible by remember { mutableStateOf(false) }

    // Sentinel for the hidden text field — keep a space so backspace has something to delete
    val sentinel = " "
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(sentinel, TextRange(sentinel.length)))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // VNC canvas
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .onSizeChanged { viewSize = it }
                // All touch handling: tap, drag, pinch-to-zoom, two-finger pan/scroll.
                // Uses Initial pass and consumes all events so the pager can't steal them.
                .pointerInput(frame.width, frame.height, viewSize, zoom, panX, panY) {
                    val touchSlopPx = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        firstDown.consume()
                        var totalFingers = 1
                        var prevCentroid = firstDown.position
                        var prevSpan = 0f
                        var gestureStarted = false
                        var cumulativeScrollY = 0f
                        var totalMovement = 0f
                        var lastSinglePos = firstDown.position
                        var dragging = false

                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pointers = event.changes.filter { it.pressed }
                            val count = pointers.size

                            if (count >= 2) {
                                // If we were dragging with button 1, release it
                                if (dragging) {
                                    onDragEnd()
                                    dragging = false
                                }
                                totalFingers = maxOf(totalFingers, count)
                                val centroid = Offset(
                                    pointers.map { it.position.x }.average().toFloat(),
                                    pointers.map { it.position.y }.average().toFloat(),
                                )
                                val span = pointers.map {
                                    (it.position - centroid).getDistance()
                                }.average().toFloat()

                                if (gestureStarted) {
                                    if (prevSpan > 0f && span > 0f) {
                                        val scaleFactor = span / prevSpan
                                        val newZoom = (zoom * scaleFactor).coerceIn(0.5f, 5f)
                                        panX += (centroid.x - panX) * (1 - scaleFactor)
                                        panY += (centroid.y - panY) * (1 - scaleFactor)
                                        zoom = newZoom
                                    }
                                    val dx = centroid.x - prevCentroid.x
                                    val dy = centroid.y - prevCentroid.y
                                    panX += dx
                                    panY += dy

                                    cumulativeScrollY += centroid.y - prevCentroid.y
                                    if (abs(cumulativeScrollY) > 40f) {
                                        if (cumulativeScrollY < 0) onScrollUp() else onScrollDown()
                                        cumulativeScrollY = 0f
                                    }
                                }

                                gestureStarted = true
                                prevCentroid = centroid
                                prevSpan = span

                                pointers.forEach { it.consume() }
                            } else if (count == 1 && totalFingers == 1) {
                                val change = pointers.first()
                                totalMovement += change.positionChange().getDistance()
                                lastSinglePos = change.position
                                val pos = screenToVnc(
                                    change.position, viewSize,
                                    frame.width, frame.height,
                                    zoom, panX, panY,
                                )
                                // Start drag (button 1 press) once movement exceeds touch slop
                                if (!dragging && totalMovement >= touchSlopPx) {
                                    onDragStart(pos.first, pos.second)
                                    dragging = true
                                } else if (dragging) {
                                    onDrag(pos.first, pos.second)
                                }
                                change.consume()
                            }
                        } while (event.changes.any { it.pressed })

                        // Release button 1 if drag was active
                        if (dragging) {
                            onDragEnd()
                        }

                        // Short tap with little movement = click
                        if (totalFingers == 1 && totalMovement < touchSlopPx) {
                            val (vx, vy) = screenToVnc(
                                lastSinglePos, viewSize,
                                frame.width, frame.height,
                                zoom, panX, panY,
                            )
                            onTap(vx, vy)
                        }
                    }
                },
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = panX
                        translationY = panY
                    },
            ) {
                drawVncFrame(imageBitmap, frame.width, frame.height)
            }
        }

        // Hidden text field for keyboard input capture
        BasicTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
                val oldText = textFieldValue.text
                val newText = newValue.text

                if (newText.length > oldText.length) {
                    // Characters were typed
                    val added = newText.substring(oldText.length)
                    for (ch in added) {
                        onTypeChar(ch)
                    }
                } else if (newText.length < oldText.length) {
                    // Backspace
                    val deleted = oldText.length - newText.length
                    repeat(deleted) {
                        onKeyDown(XK_BACKSPACE)
                        onKeyUp(XK_BACKSPACE)
                    }
                }

                // Reset to sentinel
                textFieldValue = TextFieldValue(sentinel, TextRange(sentinel.length))
            },
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    val keySym = androidKeyToKeySym(event.key)
                    if (keySym != null) {
                        when (event.type) {
                            KeyEventType.KeyDown -> onKeyDown(keySym)
                            KeyEventType.KeyUp -> onKeyUp(keySym)
                        }
                        true
                    } else {
                        false
                    }
                },
        )

        // Bottom toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onDisconnect) {
                Text("Disconnect")
            }

            Spacer(Modifier.width(8.dp))

            // Keyboard toggle
            IconButton(onClick = {
                keyboardVisible = !keyboardVisible
                if (keyboardVisible) {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                } else {
                    keyboardController?.hide()
                }
            }) {
                Icon(
                    if (keyboardVisible) Icons.Default.KeyboardHide
                    else Icons.Default.Keyboard,
                    contentDescription = "Toggle keyboard",
                )
            }

            Spacer(Modifier.weight(1f))

            // Reset zoom button
            if (zoom != 1f || panX != 0f || panY != 0f) {
                Button(onClick = {
                    zoom = 1f
                    panX = 0f
                    panY = 0f
                }) {
                    Text("Reset Zoom")
                }
            }
        }
    }
}

private fun DrawScope.drawVncFrame(
    image: androidx.compose.ui.graphics.ImageBitmap,
    srcWidth: Int,
    srcHeight: Int,
) {
    val viewW = size.width
    val viewH = size.height
    val scale = minOf(viewW / srcWidth, viewH / srcHeight)
    val dstW = srcWidth * scale
    val dstH = srcHeight * scale
    val offsetX = (viewW - dstW) / 2
    val offsetY = (viewH - dstH) / 2

    drawImage(
        image = image,
        srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
        srcSize = androidx.compose.ui.unit.IntSize(srcWidth, srcHeight),
        dstOffset = androidx.compose.ui.unit.IntOffset(offsetX.toInt(), offsetY.toInt()),
        dstSize = androidx.compose.ui.unit.IntSize(dstW.toInt(), dstH.toInt()),
    )
}

/**
 * Map a screen touch coordinate to VNC framebuffer coordinates,
 * accounting for zoom and pan.
 */
private fun screenToVnc(
    offset: Offset,
    viewSize: IntSize,
    fbWidth: Int,
    fbHeight: Int,
    zoom: Float,
    panX: Float,
    panY: Float,
): Pair<Int, Int> {
    if (viewSize.width == 0 || viewSize.height == 0) return 0 to 0
    val viewW = viewSize.width.toFloat()
    val viewH = viewSize.height.toFloat()

    // Reverse the graphicsLayer transform: the canvas is scaled by zoom and translated by pan.
    // The center of the view is the pivot point for graphicsLayer scaling.
    val cx = viewW / 2f
    val cy = viewH / 2f
    val localX = (offset.x - cx - panX) / zoom + cx
    val localY = (offset.y - cy - panY) / zoom + cy

    // Now map from view coordinates to VNC coordinates (same as before)
    val fitScale = minOf(viewW / fbWidth, viewH / fbHeight)
    val dstW = fbWidth * fitScale
    val dstH = fbHeight * fitScale
    val offsetX = (viewW - dstW) / 2
    val offsetY = (viewH - dstH) / 2

    val vncX = ((localX - offsetX) / fitScale).toInt().coerceIn(0, fbWidth - 1)
    val vncY = ((localY - offsetY) / fitScale).toInt().coerceIn(0, fbHeight - 1)
    return vncX to vncY
}

// X11 KeySym constants for special keys
private const val XK_BACKSPACE = 0xff08
private const val XK_TAB = 0xff09
private const val XK_RETURN = 0xff0d
private const val XK_ESCAPE = 0xff1b
private const val XK_DELETE = 0xffff
private const val XK_HOME = 0xff50
private const val XK_LEFT = 0xff51
private const val XK_UP = 0xff52
private const val XK_RIGHT = 0xff53
private const val XK_DOWN = 0xff54
private const val XK_PAGE_UP = 0xff55
private const val XK_PAGE_DOWN = 0xff56
private const val XK_END = 0xff57
private const val XK_INSERT = 0xff63
private const val XK_SHIFT_L = 0xffe1
private const val XK_CONTROL_L = 0xffe3
private const val XK_ALT_L = 0xffe9

/** Convert a printable character to its X11 KeySym. */
private fun charToKeySym(ch: Char): Int = when (ch) {
    '\n', '\r' -> XK_RETURN
    '\t' -> XK_TAB
    '\b' -> XK_BACKSPACE
    else -> ch.code // Latin-1 characters map directly to Unicode code point
}

/** Map Android Key to X11 KeySym for special (non-printable) keys. */
private fun androidKeyToKeySym(key: Key): Int? = when (key) {
    Key.Enter -> XK_RETURN
    Key.Tab -> XK_TAB
    Key.Escape -> XK_ESCAPE
    Key.Backspace -> XK_BACKSPACE
    Key.Delete -> XK_DELETE
    Key.DirectionLeft -> XK_LEFT
    Key.DirectionRight -> XK_RIGHT
    Key.DirectionUp -> XK_UP
    Key.DirectionDown -> XK_DOWN
    Key.MoveHome -> XK_HOME
    Key.MoveEnd -> XK_END
    Key.PageUp -> XK_PAGE_UP
    Key.PageDown -> XK_PAGE_DOWN
    Key.Insert -> XK_INSERT
    Key.ShiftLeft, Key.ShiftRight -> XK_SHIFT_L
    Key.CtrlLeft, Key.CtrlRight -> XK_CONTROL_L
    Key.AltLeft, Key.AltRight -> XK_ALT_L
    else -> null
}
