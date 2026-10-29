package com.parkingconcepts.mortimer

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Manual, explicitly authorized migration only. Uses existing app grants; never shell/root permissions. */
@RunWith(AndroidJUnit4::class)
class StudioFolderMigrationDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val main = "com.parkingconcepts.mortimer"
    private fun approvedRoots(): List<Uri> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit user authorization for the three existing folders", args.getString("approvedFolderMigration") == "yes")
        val json = JSONArray(String(Base64.decode(args.getString("rootsB64") ?: error("Missing approved roots"), Base64.DEFAULT), Charsets.UTF_8))
        require(json.length() == 3)
        return (0 until json.length()).map { Uri.parse(json.getString(it)).also { uri ->
            require(uri.scheme == "content" && uri.authority == "com.android.externalstorage.documents" && DocumentsContract.isTreeUri(uri))
        } }.also { require(it.distinct().size == 3) }
    }
    @Test fun sharePreviouslyApprovedFoldersFromPreview() {
        val roots = approvedRoots()
        assertEquals("$main.preview", context.packageName)
        assertEquals("Only a recipient signed with this same key may receive the grants",
            PackageManager.SIGNATURE_MATCH, context.packageManager.checkSignatures(context.packageName, main))
        val owned = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }.toSet()
        assertTrue("Preview must already own every requested folder", roots.all { it in owned })
        roots.forEach { uri ->
            assertEquals(PackageManager.PERMISSION_GRANTED, context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        roots.forEach { uri -> context.grantUriPermission(main, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }
        println("Shared ${roots.size} previously approved folder grants with MUGEN normal")
    }
    @Test fun persistTransferredFoldersInNormalApp() {
        val roots = approvedRoots()
        assertEquals(main, context.packageName)
        // Instrumentation force-stops its target when starting. The sender must grant
        // after this process starts so Android does not clear the transient offer.
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (roots.any { context.checkUriPermission(it, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED }
            && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        roots.forEach { uri -> context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val owned = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }.toSet()
        assertTrue(roots.all { it in owned })
        roots.forEach { uri ->
            assertEquals(PackageManager.PERMISSION_GRANTED, context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        println("Persisted ${roots.size} restored folder grants in MUGEN normal")
    }
}
