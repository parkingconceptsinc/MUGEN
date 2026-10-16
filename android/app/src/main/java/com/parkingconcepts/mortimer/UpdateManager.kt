package com.parkingconcepts.mortimer

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private data class MugenRelease(val tag: String, val versionCode: Int, val apkUrl: String, val apkName: String)

private fun fetchLatestMugenRelease(): MugenRelease {
    val connection = (URL("https://api.github.com/repos/parkingconceptsinc/MUGEN/releases?per_page=100").openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 8000
        readTimeout = 10000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "MUGEN-Android-Updater")
    }
    try {
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("GitHub update check failed (HTTP ${connection.responseCode}). Check your connection and try again.")
        }
        val releases = connection.inputStream.bufferedReader(Charsets.UTF_8).use { org.json.JSONArray(it.readText()) }
        var best: MugenRelease? = null
        for (releaseIndex in 0 until releases.length()) {
            val releaseJson = releases.optJSONObject(releaseIndex) ?: continue
            if (releaseJson.optBoolean("draft", false) || releaseJson.optBoolean("prerelease", false)) continue
            val tag = releaseJson.optString("tag_name")
            // Ignore website/PWA releases. The Android updater must only use explicitly versioned APK releases.
            if (!tag.startsWith("android-v", ignoreCase = true)) continue
            val versionCode = parseAndroidVersionCode(tag) ?: continue
            val assets = releaseJson.optJSONArray("assets") ?: continue
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (name.equals("MUGEN.apk", ignoreCase = true) && isTrustedGitHubReleaseAsset(url)) {
                    val candidate = MugenRelease(tag, versionCode, url, name)
                    if (best == null || candidate.versionCode > best.versionCode) best = candidate
                    break
                }
            }
        }
        return best ?: throw IllegalStateException(
            "No MUGEN Android update is published yet. A signed MUGEN.apk must be attached to a GitHub Release tagged android-vMAJOR.MINOR.PATCH."
        )
    } finally {
        connection.disconnect()
    }
}

@Composable
internal fun AppUpdateCard() {
    val context = LocalContext.current
    val c = LocalMortimerColors.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var release by remember { mutableStateOf<MugenRelease?>(null) }
    var downloadedName by remember { mutableStateOf<String?>(null) }
    var downloadingRelease by remember { mutableStateOf<MugenRelease?>(null) }
    var downloadId by remember { mutableStateOf<Long?>(null) }
    var message by remember { mutableStateOf("Checking for updates…") }

    fun checkForUpdates() {
        if (checking) return
        checking = true
        message = "Checking for updates…"
        scope.launch {
            try {
                val latest = withContext(Dispatchers.IO) { fetchLatestMugenRelease() }
                release = if (latest.versionCode > BuildConfig.VERSION_CODE) latest else null
                message = if (release != null) "Version ${latest.tag} is available." else "You're using the latest published version."
            } catch (error: Exception) {
                message = error.message ?: "Could not check for updates. Try again later."
            } finally {
                checking = false
            }
        }
    }

    LaunchedEffect(Unit) { checkForUpdates() }

    Card(
        colors = CardDefaults.cardColors(containerColor = c.panel),
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("APP UPDATES", color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("Installed: ${BuildConfig.VERSION_NAME}", color = c.muted, fontSize = 10.sp)
            }
            Text(message, color = c.text, fontSize = 12.sp)
            if (release != null) {
                Button(
                    onClick = {
                        val latest = release ?: return@Button
                        try {
                            val request = DownloadManager.Request(Uri.parse(latest.apkUrl))
                                .setTitle("MUGEN update ${latest.tag}")
                                .setDescription("Downloading the official MUGEN APK")
                                .setMimeType("application/vnd.android.package-archive")
                                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                .setAllowedOverMetered(true)
                                .setAllowedOverRoaming(false)
                                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, latest.apkName)
                            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                            downloadingRelease = latest
                            downloadId = manager.enqueue(request)
                            downloadedName = null
                            message = "Downloading ${latest.tag}…"
                        } catch (error: Exception) {
                            message = "Download could not start: ${error.localizedMessage ?: "check your connection"}"
                        }
                    },
                    enabled = downloadId == null,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (downloadId != null) "Downloading…" else "Download update") }
            }
            if (downloadedName != null) {
                Button(onClick = {
                    val apk = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), downloadedName!!)
                    if (!apk.isFile || apk.length() <= 0L) {
                        message = "The downloaded APK is missing. Download it again."
                        downloadedName = null
                        return@Button
                    }
                    if (!context.packageManager.canRequestPackageInstalls()) {
                        message = "Allow installs from MUGEN in Android Settings, then return and tap Install update."
                        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        return@Button
                    }
                    runCatching {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
                        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }.onFailure { message = "Could not open Android installer: ${it.localizedMessage ?: "try again"}" }
                }, modifier = Modifier.fillMaxWidth()) { Text("Install update") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { checkForUpdates() }, enabled = !checking, modifier = Modifier.weight(1f)) {
                    Text(if (checking) "Checking…" else "Check again")
                }
                if (release == null) {
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/parkingconceptsinc/MUGEN/releases")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }, modifier = Modifier.weight(1f)) { Text("Release page") }
                }
            }
        }
    }

    LaunchedEffect(downloadId) {
        val id = downloadId ?: return@LaunchedEffect
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        while (true) {
            kotlinx.coroutines.delay(700)
            val query = DownloadManager.Query().setFilterById(id)
            val cursor = runCatching { manager.query(query) }.getOrNull()
            var finished = false
            if (cursor == null) {
                message = "Could not check download status. Open Downloads and try again."
                finished = true
            } else cursor.use {
                if (it.moveToFirst()) {
                    when (it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            downloadedName = downloadingRelease?.apkName
                            message = if (downloadingRelease != null) "Update downloaded. Tap Install update to continue." else "Download finished, but the update details were lost. Check for updates and download again."
                            finished = true
                        }
                        DownloadManager.STATUS_FAILED -> {
                            message = "Download failed. Check your connection and try again."
                            finished = true
                        }
                    }
                } else {
                    message = "Download was not found. Try again."
                    finished = true
                }
            }
            if (finished) {
                downloadId = null
                downloadingRelease = null
                break
            }
        }
    }
}
