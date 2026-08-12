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
    TEN_SECONDS("10 s", 10_000L),
    THIRTY_SECONDS("30 s", 30_000L),
    // Short enough that all four fit across a phone without the last one being
    // pushed off the edge, and "∞" is the same convention a bulb setting uses.
    // The line above the buttons spells out what it means.
    INDEFINITE("∞", Long.MAX_VALUE);

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

    /**
     * The scene is too bright for the longest frame the sensor allows, even at its
     * lowest ISO, so the shutter was shortened instead.
     */
    SCENE_BRIGHTNESS,
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
        /**
         * Always formatted in Latin digits.
         *
         * Without an explicit locale this follows the phone's, and on a device set
         * to Persian the readouts came back as "۰٫۶۷ s" — correct, and unreadable
         * next to the Latin numerals everywhere else in the app. Camera settings
         * are notation, not prose.
         */
        fun formatSeconds(ns: Long): String {
            val seconds = ns / 1_000_000_000.0
            return if (seconds >= 1.0) String.format(java.util.Locale.US, "%.1f s", seconds)
            else String.format(java.util.Locale.US, "%.2f s", seconds)
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
     * Ceiling on how many frames one stack may gather.
     *
     * Frame count is total time divided by shutter length, and when the shutter is
     * short that division runs away: a daylight test asked for ten seconds, got a
     * 22 ms shutter, and dutifully planned 455 frames — which took forty-four
     * seconds to shoot and stack. A capture labelled "10 s" must not take the best
     * part of a minute.
     *
     * The cost of the cap is nil where it matters. On a dark sky the shutter sits at
     * the sensor's maximum, so thirty seconds needs 67 frames and this never binds.
     * It only engages when frames are short, which is to say when the scene is
     * bright enough that stacking had little left to offer anyway.
     */
    const val MAX_STACK_FRAMES = 100

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
        var subExposure = minOf(hardwareLimit, trailLimit)
            .coerceAtLeast(camera.minExposureNs)

        var limitedBy =
            if (trailLimit < hardwareLimit) ExposureLimit.STAR_TRAILING else ExposureLimit.HARDWARE

        // Re-spend the metered light across the shutter length we settled on: a
        // shorter frame than the meter assumed has to buy the difference in ISO.
        var requestedIso = (metering.lightBudget / subExposure) * 2.0.pow(evOffset.toDouble())

        // If the scene needs *less* light than base ISO at this shutter can give,
        // there is nothing left to turn down and the frame blows out to white.
        //
        // This is not a theoretical case. It is what happens the first time anyone
        // opens the app indoors or before dusk to see what it does, and handing them
        // a white rectangle reads as a broken app rather than as "too bright for a
        // night-sky exposure". Shortening the shutter keeps it honest in any light;
        // on an actually dark sky the branch never runs.
        if (requestedIso < camera.minIso) {
            val shortened = (subExposure * (requestedIso / camera.minIso)).toLong()
            subExposure = shortened.coerceIn(camera.minExposureNs, hardwareLimit)
            requestedIso = camera.minIso.toDouble()
            limitedBy = ExposureLimit.SCENE_BRIGHTNESS
        }

        val iso = requestedIso.roundToInt().coerceIn(camera.minIso, camera.maxIso)

        val frameCount = when {
            mode == CaptureMode.SINGLE -> 1
            mode.isIndefinite -> Int.MAX_VALUE
            else -> {
                val subMs = (subExposure / 1_000_000L).coerceAtLeast(1L)
                val needed = ceil(mode.targetIntegrationMs.toDouble() / subMs).toInt()
                needed.coerceIn(1, MAX_STACK_FRAMES)
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
