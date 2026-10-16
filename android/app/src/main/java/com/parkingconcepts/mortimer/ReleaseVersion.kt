package com.parkingconcepts.mortimer

import android.net.Uri

/**
 * Accept only canonical Android release tags. Keep this mapping identical to
 * .github/workflows/android-release.yml so the updater never offers malformed
 * or ambiguous versions.
 */
internal fun parseAndroidVersionCode(tag: String): Int? {
    val match = Regex("^android-v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$").matchEntire(tag)
        ?: return null
    val major = match.groupValues[1].toIntOrNull() ?: return null
    val minor = match.groupValues[2].toIntOrNull() ?: return null
    val patch = match.groupValues[3].toIntOrNull() ?: return null
    if (major > 20 || minor > 99 || patch > 99) return null
    return major * 10000 + minor * 100 + patch
}

/** Only allow the canonical HTTPS GitHub release download host. */
internal fun isTrustedGitHubReleaseAsset(rawUrl: String): Boolean {
    val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals("github.com", ignoreCase = true) &&
        uri.userInfo == null &&
        uri.port == -1
}
