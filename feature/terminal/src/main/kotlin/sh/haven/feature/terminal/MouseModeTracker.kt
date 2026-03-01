package sh.haven.feature.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Scans terminal output byte buffers for DECSET/DECRST escape sequences that
 * enable or disable mouse tracking modes.
 *
 * Tracks modes 1000 (basic), 1002 (button-event), and 1003 (any-event).
 * Exposes whether any mouse tracking mode is currently active.
 *
 * Pattern: ESC [ ? <digits> h  (enable)
 *          ESC [ ? <digits> l  (disable)
 *
 * Handles partial sequences across buffer boundaries via a simple state machine.
 */
class MouseModeTracker {

    private enum class State {
        GROUND,    // Waiting for ESC
        ESC,       // Got ESC
        BRACKET,   // Got ESC [
        QUESTION,  // Got ESC [ ?
        DIGITS,    // Collecting mode number digits
    }

    private var state = State.GROUND
    private var modeAccum = 0
    private val pendingModes = mutableListOf<Int>()

    private val activeModes = mutableSetOf<Int>()

    private val _mouseMode = MutableStateFlow(false)
    val mouseMode: StateFlow<Boolean> = _mouseMode.asStateFlow()

    companion object {
        private val MOUSE_MODES = setOf(1000, 1002, 1003)
    }

    /**
     * Process a chunk of terminal output data. Call this before feeding
     * the same data to the terminal emulator.
     */
    fun process(data: ByteArray, offset: Int, length: Int) {
        val end = offset + length
        for (i in offset until end) {
            val b = data[i].toInt() and 0xFF
            when (state) {
                State.GROUND -> {
                    if (b == 0x1B) state = State.ESC
                }
                State.ESC -> {
                    state = if (b == '['.code) State.BRACKET else State.GROUND
                }
                State.BRACKET -> {
                    state = if (b == '?'.code) {
                        modeAccum = 0
                        State.QUESTION
                    } else {
                        State.GROUND
                    }
                }
                State.QUESTION -> {
                    when {
                        b in '0'.code..'9'.code -> {
                            modeAccum = b - '0'.code
                            pendingModes.clear()
                            state = State.DIGITS
                        }
                        else -> state = State.GROUND
                    }
                }
                State.DIGITS -> {
                    when {
                        b in '0'.code..'9'.code -> {
                            modeAccum = modeAccum * 10 + (b - '0'.code)
                        }
                        b == ';'.code -> {
                            // Multiple modes in one sequence (e.g., ESC[?1000;1006h)
                            pendingModes.add(modeAccum)
                            modeAccum = 0
                        }
                        b == 'h'.code -> {
                            pendingModes.add(modeAccum)
                            for (mode in pendingModes) applyMode(mode, enable = true)
                            pendingModes.clear()
                            state = State.GROUND
                        }
                        b == 'l'.code -> {
                            pendingModes.add(modeAccum)
                            for (mode in pendingModes) applyMode(mode, enable = false)
                            pendingModes.clear()
                            state = State.GROUND
                        }
                        else -> {
                            pendingModes.clear()
                            state = State.GROUND
                        }
                    }
                }
            }
        }
    }

    private fun applyMode(mode: Int, enable: Boolean) {
        if (mode !in MOUSE_MODES) return
        if (enable) {
            activeModes.add(mode)
        } else {
            activeModes.remove(mode)
        }
        _mouseMode.value = activeModes.isNotEmpty()
    }
}
