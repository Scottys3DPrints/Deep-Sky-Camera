package com.deepsky.camera.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A capture that never ends is the worst failure this app has: the shutter stays
 * open, the phone gets hot, and the only way out is to close the app and lose the
 * photograph. These pin down the exact frame on which each mode stops.
 */
class CaptureBudgetTest {

    @Test
    fun `a thirty second capture finishes on its target frame`() {
        // 0.67 s frames, so thirty seconds is 45 of them.
        val budget = CaptureBudget(target = 45)

        // The warm-up frame is discarded and must not count toward the total.
        assertEquals(CaptureBudget.Action.DISCARD, budget.onFrameDelivered())
        assertEquals(0, budget.stacked)

        repeat(44) {
            assertEquals(
                "stopped early at frame ${budget.stacked}",
                CaptureBudget.Action.STACK,
                budget.onFrameDelivered(),
            )
        }

        assertEquals(CaptureBudget.Action.STACK_AND_FINISH, budget.onFrameDelivered())
        assertEquals(45, budget.stacked)
        assertTrue(budget.isComplete)
    }

    @Test
    fun `single takes exactly one frame after the warm up`() {
        val budget = CaptureBudget(target = 1)

        assertEquals(CaptureBudget.Action.DISCARD, budget.onFrameDelivered())
        assertEquals(CaptureBudget.Action.STACK_AND_FINISH, budget.onFrameDelivered())
        assertEquals(1, budget.stacked)
    }

    @Test
    fun `indefinite never finishes on its own`() {
        val budget = CaptureBudget(target = Int.MAX_VALUE)

        budget.onFrameDelivered()
        repeat(500) {
            assertEquals(
                "an indefinite capture ended by itself",
                CaptureBudget.Action.STACK,
                budget.onFrameDelivered(),
            )
        }
        assertFalse(budget.isComplete)
        assertEquals(500, budget.stacked)
    }

    @Test
    fun `a late frame arriving after the target still reports finished`() {
        // Frames already in flight when the capture ends do arrive afterwards. If
        // completion were tested with equality rather than "at least", this frame
        // would push the count past the target, every later test would fail, and
        // the capture would run until the user forced it to stop.
        val budget = CaptureBudget(target = 3)

        budget.onFrameDelivered() // warm-up
        budget.onFrameDelivered()
        budget.onFrameDelivered()
        assertEquals(CaptureBudget.Action.STACK_AND_FINISH, budget.onFrameDelivered())

        assertEquals(CaptureBudget.Action.STACK_AND_FINISH, budget.onFrameDelivered())
        assertTrue(budget.isComplete)
    }

    @Test
    fun `a zero warm up stacks the very first frame`() {
        val budget = CaptureBudget(target = 2, warmupFrames = 0)

        assertEquals(CaptureBudget.Action.STACK, budget.onFrameDelivered())
        assertEquals(CaptureBudget.Action.STACK_AND_FINISH, budget.onFrameDelivered())
    }

    @Test
    fun `every planned mode terminates at the frame count it asked for`() {
        // Ties the budget back to the planner, so a change to either that made a
        // mode unstoppable would fail here.
        val camera = AstroCamera(
            id = "0",
            label = "Main (0)",
            facing = 1,
            focalLengthMm = 5.23f,
            apertureF = 1.8f,
            minExposureNs = 30_488L,
            maxExposureNs = 450_999_590L,
            minIso = 26,
            maxIso = 3368,
            hardwareLevel = 3,
            supportsManual = true,
            supportsRaw = true,
            pixelPitchUm = 1.6f,
            captureSize = android.util.Size(4624, 3468),
            previewSize = android.util.Size(1920, 1440),
            sensorOrientation = 90,
        )
        val metering = SceneMetering(exposureNs = 450_999_590L, iso = 3200)

        CaptureMode.entries.filterNot { it.isIndefinite }.forEach { mode ->
            val plan = CapturePlanner.plan(camera, mode, metering)
            val budget = CaptureBudget(plan.frameCount)

            var frames = 0
            var finished = false
            // Generous ceiling: if a mode cannot finish within it, it never would.
            while (frames < CapturePlanner.MAX_STACK_FRAMES * 4 && !finished) {
                finished = budget.onFrameDelivered() == CaptureBudget.Action.STACK_AND_FINISH
                frames++
            }

            assertTrue("${mode.label} never finished", finished)
            assertEquals("${mode.label} stacked the wrong number", plan.frameCount, budget.stacked)
        }
    }
}
