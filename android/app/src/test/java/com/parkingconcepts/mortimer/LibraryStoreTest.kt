package com.parkingconcepts.mortimer

import android.content.Context
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LibraryStoreTest {
    private lateinit var context: Context
    private fun file(name: String) = File(context.filesDir, name)
    private fun item(id: String) = LibraryItem("content://library/$id", "$id.epub", "books", "epub")

    @Before fun reset() {
        context = RuntimeEnvironment.getApplication()
        context.filesDir.listFiles()?.filter { it.name.startsWith("library.") }?.forEach { it.delete() }
        context.getSharedPreferences("mortimer_library", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun savesBothPrimaryAndRecoverySnapshots() {
        val expected = listOf(item("one"))
        assertEquals(expected, LibraryStore.add(context, expected))
        assertEquals(expected, LibraryStore.load(context))
        assertEquals(file("library.json").readText(), file("library.backup.json").readText())
    }

    @Test fun recoversDamagedPrimaryAndPreservesItsBytes() {
        val expected = LibraryStore.add(context, listOf(item("one")))
        file("library.json").writeText("[damaged")
        assertEquals(expected, LibraryStore.load(context))
        assertEquals("[damaged", file("library.damaged.json").readText())
        assertEquals(expected, LibraryStore.load(context))
        assertEquals(1, JSONArray(file("library.json").readText()).length())
    }

    @Test fun recoversMissingPrimaryFromBackupWithoutMigratingOldPreferences() {
        val expected = LibraryStore.add(context, listOf(item("one")))
        file("library.json").delete()
        assertEquals(expected, LibraryStore.load(context))
    }

    @Test fun malformedRecordDoesNotSilentlyDiscardPartOfLibrary() {
        val expected = LibraryStore.add(context, listOf(item("one"), item("two")))
        file("library.json").writeText("[${item("one").toJson()},{}]")
        assertEquals(expected, LibraryStore.load(context))
    }

    @Test fun refusesToOverwriteWhenBothSnapshotsAreDamaged() {
        LibraryStore.add(context, listOf(item("one")))
        file("library.json").writeText("broken-primary")
        file("library.backup.json").writeText("broken-backup")
        assertThrows(IOException::class.java) { LibraryStore.add(context, listOf(item("two"))) }
        assertEquals("broken-primary", file("library.json").readText())
        assertEquals("broken-backup", file("library.backup.json").readText())
    }

    @Test fun protectsExistingInstallWithoutBackupWhenItsFileIsDamaged() {
        file("library.json").writeText("broken-old-library")
        assertThrows(IOException::class.java) { LibraryStore.update(context) { emptyList() } }
        assertEquals("broken-old-library", file("library.json").readText())
    }

    @Test fun failedTransformLeavesBothSnapshotsUntouched() {
        val expected = LibraryStore.add(context, listOf(item("one")))
        val before = file("library.json").readText()
        assertThrows(IllegalStateException::class.java) { LibraryStore.update(context) { error("failed") } }
        assertEquals(before, file("library.json").readText())
        assertEquals(before, file("library.backup.json").readText())
        assertEquals(expected, LibraryStore.load(context))
    }

    @Test fun duplicateImportsKeepExistingProgressAndProduceUniqueEntries() {
        val current = item("one").copy(progress = 0.5f, position = "2:0.5", openedAt = 123)
        LibraryStore.add(context, listOf(current))
        val result = LibraryStore.add(context, listOf(item("one"), item("two"), item("two")))
        assertEquals(listOf(current, item("two")).map { it.uri }, result.map { it.uri })
        assertEquals(current, result.first())
        assertEquals(2, LibraryStore.load(context).size)
    }

    @Test fun concurrentImportsDoNotLoseFiles() {
        LibraryStore.load(context)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val writes = (0 until 40).map { id -> executor.submit { LibraryStore.add(context, listOf(item("$id"))) } }
            writes.forEach { it.get(30, TimeUnit.SECONDS) }
            val result = LibraryStore.load(context)
            assertEquals(40, result.size)
            assertEquals(40, result.map { it.uri }.toSet().size)
        } finally { executor.shutdownNow() }
    }

    @Test fun editUsesLatestProgressInsteadOfAnOldUiSnapshot() {
        val original = item("one")
        LibraryStore.add(context, listOf(original, item("two")))
        LibraryStore.saveProgress(context, original.uri, "3:0.75", 0.75f, 5)
        LibraryStore.update(context) { latest -> latest.filter { it.uri != item("two").uri } }
        val saved = LibraryStore.load(context).single()
        assertEquals(0.75f, saved.progress)
        assertEquals("3:0.75", saved.position)
    }

    @Test fun migratesLegacyMusicTagsWithoutLosingArtistAndAlbum() {
        context.getSharedPreferences("mortimer_library", Context.MODE_PRIVATE).edit()
            .putStringSet("audio", setOf("content://music/1\tTitle.mp3\tArtist\tAlbum")).commit()
        val migrated = LibraryStore.load(context).single()
        assertEquals("Title.mp3", migrated.title)
        assertEquals("Artist", migrated.artist)
        assertEquals("Album", migrated.album)
    }
}
