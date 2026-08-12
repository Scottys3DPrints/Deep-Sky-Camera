package com.deepsky.camera.camera

/**
 * Decides what becomes of each frame the camera delivers, and when a capture is
 * finished.
 *
 * This is four lines of arithmetic that were previously buried inside an
 * `onImageAvailable` callback, entangled with `ImageReader` and a background
 * Handler, and therefore impossible to test without a phone in your hand. That is
 * precisely the wrong place for it: a mistake here does not crash or log, it
 * produces a capture that ends too early, too late, or never — and "never" means
 * the exposure runs until the user gives up and closes the app.
 *
 * Pulled out here, every path is provable on a laptop in milliseconds.
 */
class CaptureBudget(
    /** Frames wanted, or [Int.MAX_VALUE] for a capture the user ends by hand. */
    private val target: Int,
    /**
     * Frames discarded at the start. The first frame after switching into manual
     * arrives while the sensor is still ramping to the requested exposure, and
     * carries the shake from the tap that began the capture.
     */
    private val warmupFrames: Int = 1,
) {
    enum class Action {
        /** Still ramping. Throw this frame away. */
        DISCARD,

        /** Fold it into the stack and keep going. */
        STACK,

        /** Fold it in; that was the last one wanted. */
        STACK_AND_FINISH,
    }

    var delivered = 0
        private set

    var stacked = 0
        private set

    val isIndefinite: Boolean get() = target == Int.MAX_VALUE

    /** True once enough frames are in that nothing further should be requested. */
    val isComplete: Boolean get() = !isIndefinite && stacked >= target

    fun onFrameDelivered(): Action {
        delivered++
        if (delivered <= warmupFrames) return Action.DISCARD

        stacked++
        // Deliberately >= rather than ==. If a frame slipped through after the
        // target was met — a callback already in flight when the capture ended —
        // equality would miss it and the capture would run on forever.
        return if (!isIndefinite && stacked >= target) Action.STACK_AND_FINISH else Action.STACK
    }
}
