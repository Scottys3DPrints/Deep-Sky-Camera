package com.deepsky.camera.camera

import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How long the shutter is open in total.
 *
 * These are *integration times*, not shutter speeds, and the distinction is the
 * single most important idea in this app. Phone camera HALs cap one exposure at
 * a fraction of a second — 0.45 s on this phone's main camera — so a thirty
 * second shutter simply cannot be requested. Thirty seconds of light is still
 * entirely reachable: take sixty-seven exposures and add them together.
 *
 * Stacking is not a workaround forced on us either. Adding N frames grows the
 * signal by N and the random noise by sqrt(N), so the picture gets cleaner by
 * sqrt(N) — while each individual frame stays short enough that the stars stay
 * points instead of streaks.
 */
enum class CaptureMode(val label: String, val targetIntegrationMs: Long) {
    SINGLE("Single", 0L),
    TEN_SECONDS("10 sec", 10_000L),
    THIRTY_SECONDS("30 sec", 30_000L),
    INDEFINITE("Indefinite", Long.MAX_VALUE);

    val isIndefinite: Boolean get() = this == INDEFINITE
}

/** What the auto-exposure pass measured off the sky before the real capture. */
data class SceneMetering(
    val exposureNs: Long,
    val iso: Int,
) {
    /**
     * Exposure and sensitivity collapse into one number for planning: double the
     * time or double the ISO and the frame lands equally bright. Working in this
     * currency lets the planner re-spend the same light across a different
     * shutter length without re-metering.
     */
    val lightBudget: Double get() = exposureNs.toDouble() * iso

    companion object {
        /**
         * Used when metering never returned — a plausible dark-sky starting point
         * rather than a refusal, so the shutter still works on a phone whose AE
         * gives up in the dark.
         */
        val DARK_SKY_FALLBACK = SceneMetering(exposureNs = 250_000_000L, iso = 1600)
    }
}

/** Why the sub-exposure ended up as short as it did. */
enum class ExposureLimit {
    /** The camera HAL refuses anything longer. Nothing the app can do. */
    HARDWARE,

    /** Longer would smear stars into arcs as the sky rotates. */
    STAR_TRAILING,
}

data class CapturePlan(
    val subExposureNs: Long,
    val iso: Int,
    /** [Int.MAX_VALUE] for indefinite capture. */
    val frameCount: Int,
    val limitedBy: ExposureLimit,
    val trailLimitNs: Long,
    val mode: CaptureMode,
) {
    val subExposureMs: Long get() = subExposureNs / 1_000_000L
    val plannedIntegrationMs: Long
        get() = if (frameCount == Int.MAX_VALUE) Long.MAX_VALUE
        else frameCount.toLong() * subExposureMs

    /** e.g. "67 × 0.45 s @ ISO 3200" — the whole plan in one line. */
    fun summary(): String {
        val frames = if (frameCount == Int.MAX_VALUE) "∞" else frameCount.toString()
        return "$frames × ${formatSeconds(subExposureNs)} @ ISO $iso"
    }

    companion object {
        fun formatSeconds(ns: Long): String {
            val seconds = ns / 1_000_000_000.0
            return if (seconds >= 1.0) String.format("%.1f s", seconds)
            else String.format("%.2f s", seconds)
        }
    }
}

/**
 * Turns "I want thirty seconds of the night sky" into concrete sensor settings.
 *
 * The user chooses a duration and nothing else. Everything the camera needs —
 * shutter, ISO, how many frames — is derived here from what the hardware admits
 * to and what the sky will tolerate.
 */
object CapturePlanner {

    /**
     * Longest single exposure before stars visibly trail, by the NPF rule:
     *
     *     t = (35 × aperture + 30 × pixel pitch) / focal length
     *
     * Preferred over the old "500 rule" because it accounts for aperture and
     * pixel pitch, which is what actually decides whether a star lands on one
     * pixel or smears across three. On phone lenses this lands around 20 s for a
     * main camera and a minute or more for an ultra-wide — comfortably above what
     * the HAL allows, so on most phones it never binds. It binds on hardware
     * that permits genuinely long exposures, and that is exactly when it matters.
     */
    fun starTrailLimitNs(camera: AstroCamera): Long {
        val seconds = (35f * camera.apertureF + 30f * camera.pixelPitchUm) / camera.focalLengthMm
        return (seconds.toDouble() * 1_000_000_000.0).toLong().coerceAtLeast(1_000_000L)
    }

    /**
     * @param evOffset user brightness nudge in stops; +1 doubles the ISO.
     */
    fun plan(
        camera: AstroCamera,
        mode: CaptureMode,
        metering: SceneMetering,
        evOffset: Float = 0f,
    ): CapturePlan {
        val trailLimit = starTrailLimitNs(camera)

        // Take the longest single frame that both the hardware and the sky allow.
        // Long frames beat many short ones: every frame carries its own read
        // noise, so fewer, longer frames is a cleaner stack for the same total.
        val hardwareLimit = camera.maxExposureNs
        val subExposure = minOf(hardwareLimit, trailLimit)
            .coerceAtLeast(camera.minExposureNs)

        val limitedBy =
            if (trailLimit < hardwareLimit) ExposureLimit.STAR_TRAILING else ExposureLimit.HARDWARE

        // Re-spend the metered light across the shutter length we settled on: a
        // shorter frame than the meter assumed has to buy the difference in ISO.
        val requestedIso = (metering.lightBudget / subExposure) * 2.0.pow(evOffset.toDouble())
        val iso = requestedIso.roundToInt().coerceIn(camera.minIso, camera.maxIso)

        val frameCount = when {
            mode == CaptureMode.SINGLE -> 1
            mode.isIndefinite -> Int.MAX_VALUE
            else -> {
                val subMs = (subExposure / 1_000_000L).coerceAtLeast(1L)
                ceil(mode.targetIntegrationMs.toDouble() / subMs).toInt().coerceAtLeast(1)
            }
        }

        return CapturePlan(
            subExposureNs = subExposure,
            iso = iso,
            frameCount = frameCount,
            limitedBy = limitedBy,
            trailLimitNs = trailLimit,
            mode = mode,
        )
    }
}
