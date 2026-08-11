package com.deepsky.camera.camera

import android.util.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The planner is the one piece of this app whose mistakes are silent: a bad plan
 * still produces a photo, just a dark or streaked one, and you do not find out
 * until you are standing in a field. So it is pinned down here with the real
 * numbers read off the Galaxy A52s with `adb shell dumpsys media.camera`.
 */
class CapturePlannerTest {

    /** Galaxy A52s main camera, exactly as the HAL reports it. */
    private val mainCamera = AstroCamera(
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
        captureSize = Size(4624, 3468),
        previewSize = Size(1920, 1440),
        sensorOrientation = 90,
    )

    private val meteredDarkSky = SceneMetering(exposureNs = 450_999_590L, iso = 3200)

    @Test
    fun `thirty second mode stacks enough frames to reach thirty seconds`() {
        val plan = CapturePlanner.plan(mainCamera, CaptureMode.THIRTY_SECONDS, meteredDarkSky)

        // 0.45 s per frame, so it takes 67 of them.
        assertEquals(67, plan.frameCount)
        assertTrue(
            "planned integration must reach the requested 30 s",
            plan.plannedIntegrationMs >= 30_000L,
        )
    }

    @Test
    fun `sub exposure never exceeds what the hardware allows`() {
        CaptureMode.entries.forEach { mode ->
            val plan = CapturePlanner.plan(mainCamera, mode, meteredDarkSky)
            assertTrue(
                "${mode.label} asked for a longer frame than the HAL permits",
                plan.subExposureNs <= mainCamera.maxExposureNs,
            )
        }
    }

    @Test
    fun `on this phone the hardware binds long before star trailing does`() {
        val plan = CapturePlanner.plan(mainCamera, CaptureMode.TEN_SECONDS, meteredDarkSky)

        assertEquals(ExposureLimit.HARDWARE, plan.limitedBy)
        // NPF gives about 21 s here; the sensor stops at 0.45 s.
        assertTrue("trail limit should be far above the hardware cap", plan.trailLimitNs > 10_000_000_000L)
    }

    @Test
    fun `a camera that allows very long exposures is capped by star trailing instead`() {
        // Hypothetical hardware that honours a full minute, to prove the trail
        // rule actually takes over rather than sitting there as decoration.
        val generous = mainCamera.copy(maxExposureNs = 60_000_000_000L)
        val plan = CapturePlanner.plan(generous, CaptureMode.THIRTY_SECONDS, meteredDarkSky)

        assertEquals(ExposureLimit.STAR_TRAILING, plan.limitedBy)
        assertTrue(plan.subExposureNs < 60_000_000_000L)
        assertEquals(plan.trailLimitNs, plan.subExposureNs)
    }

    @Test
    fun `iso rises to buy back the light lost to a shorter frame`() {
        // Meter says 0.9 s at ISO 800. The hardware only allows 0.45 s, i.e. half
        // the time, so the plan must double the ISO to land equally bright.
        val metering = SceneMetering(exposureNs = 901_999_180L, iso = 800)
        val plan = CapturePlanner.plan(mainCamera, CaptureMode.TEN_SECONDS, metering)

        assertEquals(1600, plan.iso)
    }

    @Test
    fun `iso is clamped to the sensor's real range`() {
        val absurdlyDark = SceneMetering(exposureNs = 10_000_000_000L, iso = 3200)
        val plan = CapturePlanner.plan(mainCamera, CaptureMode.TEN_SECONDS, absurdlyDark)

        assertEquals(mainCamera.maxIso, plan.iso)
    }

    @Test
    fun `ev offset moves the plan by whole stops`() {
        val base = CapturePlanner.plan(mainCamera, CaptureMode.TEN_SECONDS, SceneMetering(450_999_590L, 800))
        val brighter = CapturePlanner.plan(
            mainCamera, CaptureMode.TEN_SECONDS, SceneMetering(450_999_590L, 800), evOffset = 1f,
        )

        assertEquals(base.iso * 2, brighter.iso)
    }

    @Test
    fun `single mode takes exactly one frame and indefinite never stops on its own`() {
        assertEquals(1, CapturePlanner.plan(mainCamera, CaptureMode.SINGLE, meteredDarkSky).frameCount)
        assertEquals(
            Int.MAX_VALUE,
            CapturePlanner.plan(mainCamera, CaptureMode.INDEFINITE, meteredDarkSky).frameCount,
        )
    }
}
