package com.parkingconcepts.mortimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {
    @Test fun parsesCanonicalAndroidVersions() {
        assertEquals(900, parseAndroidVersionCode("android-v0.9.0"))
        assertEquals(901, parseAndroidVersionCode("android-v0.9.1"))
        assertEquals(10000, parseAndroidVersionCode("android-v1.0.0"))
        assertEquals(209999, parseAndroidVersionCode("android-v20.99.99"))
    }

    @Test fun rejectsMalformedOrUnsupportedTags() {
        listOf(
            "v0.9.1", "android-0.9.1", "android-v0.9", "android-v0.9.1-extra",
            "android-v01.2.3", "android-v1.100.0", "android-v1.0.100",
            "android-v21.0.0", "android-v999999999999.0.0", " android-v1.0.0"
        ).forEach { assertNull("Expected rejection for $it", parseAndroidVersionCode(it)) }
    }

    @Test fun trustsOnlyHttpsGitHubReleaseAssetUrls() {
        assertTrue(isTrustedGitHubReleaseAsset("https://github.com/parkingconceptsinc/MUGEN/releases/download/android-v0.9.1/app-release.apk"))
        assertFalse(isTrustedGitHubReleaseAsset("http://github.com/parkingconceptsinc/MUGEN/releases/download/android-v0.9.1/app-release.apk"))
        assertFalse(isTrustedGitHubReleaseAsset("https://evil.example/app-release.apk"))
        assertFalse(isTrustedGitHubReleaseAsset("https://user@github.com/app-release.apk"))
        assertFalse(isTrustedGitHubReleaseAsset("https://github.com:8443/app-release.apk"))
        assertFalse(isTrustedGitHubReleaseAsset("not a URL"))
    }
}
