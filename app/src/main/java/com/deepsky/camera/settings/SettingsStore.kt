package com.deepsky.camera.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.deepsky.camera.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("deepsky")

/**
 * The handful of choices worth remembering between nights out.
 *
 * Everything else — shutter, ISO, frame count — is deliberately not stored,
 * because it is derived fresh from the camera and the sky each time. Saving it
 * would only let a stale setting from a different night quietly override what
 * the planner worked out.
 */
data class Settings(
    val updateUrl: String = BuildConfig.DEFAULT_UPDATE_URL,
    val alignFrames: Boolean = true,
    val autoStretch: Boolean = true,
    val evOffset: Float = 0f,
    val cameraId: String = "",
    val focusDiopters: Float = 0f,
    /**
     * Seconds to wait after the shutter is tapped before the sensor starts.
     *
     * Tapping a phone resting on a wall moves it, and the wobble outlasts the tap
     * by a second or more. Three seconds of nothing is the cheapest sharpness
     * available, which is why it is the default rather than an option to discover.
     */
    val timerSeconds: Int = 3,
)

class SettingsStore(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map { preferences ->
        Settings(
            updateUrl = preferences[UPDATE_URL] ?: BuildConfig.DEFAULT_UPDATE_URL,
            alignFrames = preferences[ALIGN_FRAMES] ?: true,
            autoStretch = preferences[AUTO_STRETCH] ?: true,
            evOffset = preferences[EV_OFFSET] ?: 0f,
            cameraId = preferences[CAMERA_ID] ?: "",
            focusDiopters = preferences[FOCUS_DIOPTERS] ?: 0f,
            timerSeconds = preferences[TIMER_SECONDS] ?: 3,
        )
    }

    suspend fun setUpdateUrl(value: String) = edit { it[UPDATE_URL] = value.trim() }
    suspend fun setAlignFrames(value: Boolean) = edit { it[ALIGN_FRAMES] = value }
    suspend fun setAutoStretch(value: Boolean) = edit { it[AUTO_STRETCH] = value }
    suspend fun setEvOffset(value: Float) = edit { it[EV_OFFSET] = value }
    suspend fun setCameraId(value: String) = edit { it[CAMERA_ID] = value }
    suspend fun setFocusDiopters(value: Float) = edit { it[FOCUS_DIOPTERS] = value }
    suspend fun setTimerSeconds(value: Int) = edit { it[TIMER_SECONDS] = value }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private companion object {
        val UPDATE_URL = stringPreferencesKey("update_url")
        val ALIGN_FRAMES = booleanPreferencesKey("align_frames")
        val AUTO_STRETCH = booleanPreferencesKey("auto_stretch")
        val EV_OFFSET = floatPreferencesKey("ev_offset")
        val CAMERA_ID = stringPreferencesKey("camera_id")
        val FOCUS_DIOPTERS = floatPreferencesKey("focus_diopters")
        val TIMER_SECONDS = androidx.datastore.preferences.core.intPreferencesKey("timer_seconds")
    }
}
