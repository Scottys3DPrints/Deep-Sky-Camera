package com.deepsky.camera.update

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.deepsky.camera.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates Deep Sky Camera in place, without ever uninstalling it.
 *
 * The app is not on any store, so this is its distribution channel. It polls a
 * small JSON manifest published alongside each GitHub release; because that
 * manifest lives at a `releases/latest/download/` address, the URL never changes
 * no matter how many versions ship, and the phone never needs reconfiguring.
 *
 * Because every release APK is signed with the same key, Android treats a
 * downloaded build as an upgrade rather than a different app, so settings
 * survive the update untouched.
 */
class UpdateChecker(private val context: Context) {

    @Serializable
    data class Manifest(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        /** Lower-case hex SHA-256 of the APK. Refuses to install without a match. */
        val sha256: String,
        val notes: String = "",
        val releasedAt: String = "",
    )

    sealed interface Result {
        data object NotConfigured : Result
        data object UpToDate : Result
        data class Available(val manifest: Manifest) : Result
        data class Failed(val reason: String) : Result
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun check(manifestUrl: String): Result = withContext(Dispatchers.IO) {
        if (manifestUrl.isBlank()) return@withContext Result.NotConfigured

        runCatching {
            val body = fetch(manifestUrl)
            val manifest = json.decodeFromString<Manifest>(body)
            if (manifest.versionCode > BuildConfig.VERSION_CODE) Result.Available(manifest)
            else Result.UpToDate
        }.getOrElse { error ->
            Result.Failed(error.message ?: "Could not reach the update server")
        }
    }

    /**
     * Downloads and verifies the APK, returning the file ready to install.
     *
     * The hash check is not ceremony: this installs a signed application on the
     * user's phone, and a truncated download or a tampered file must never get
     * as far as the installer. A mismatch deletes the file and fails.
     */
    suspend fun download(
        manifest: Manifest,
        onProgress: (Float) -> Unit = {},
    ): kotlin.Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val directory = File(context.filesDir, "updates").apply { mkdirs() }
            directory.listFiles()?.forEach { it.delete() }
            val target = File(directory, "deepsky-${manifest.versionCode}.apk")

            val connection = (URL(manifest.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }

            connection.inputStream.use { input ->
                val total = connection.contentLengthLong.takeIf { it > 0 }
                var read = 0L
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        read += count
                        total?.let { onProgress((read.toFloat() / it).coerceIn(0f, 1f)) }
                    }
                }
            }
            connection.disconnect()

            val actual = target.sha256()
            if (!actual.equals(manifest.sha256, ignoreCase = true)) {
                target.delete()
                error("Download did not match the expected checksum — refusing to install it.")
            }
            target
        }
    }

    /**
     * Hands the verified APK to the installer.
     *
     * Prefers a PackageInstaller session so the app becomes its own installer of
     * record, which avoids the system re-gating a sideloaded app's permissions on
     * every update. Falls back to the intent path if a session cannot be opened.
     */
    fun install(apk: File) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val failure = SelfInstaller.install(context, apk)
            if (failure == null) return
        }
        SelfInstaller.installByIntent(context, apk)
    }

    /** Android requires explicit consent before one app may install another. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode !in 200..299) {
                error("Server returned ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        val currentVersion: String get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    }
}
