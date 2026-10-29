package com.parkingconcepts.mortimer

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only check requested for a real device library. Never logs titles or saves media frames. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class StudioLibraryVideoDeviceTest {
    @Test fun importReadableLibraryVideoWithoutChangingLibraryOrProject() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("probeExistingLibrary") == "yes")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = LibraryStore.load(context)
        val videos = library.filter { it.kind == "videos" }
        require(videos.isNotEmpty()) { "No videos in the selected library" }
        val readable = videos.firstOrNull { runCatching { checkStudioSourceAccess(context, it) }.isSuccess }
            ?: error("All saved videos need their folder access restored")
        val imported = importStudioClips(context, listOf(readable)).single()
        assertTrue(imported.source.duration >= MIN_TRIM_MS)
        assertTrue(imported.source.width > 0 && imported.source.height > 0)
        assertEquals(readable.uri, imported.source.uri)
        assertEquals(library, LibraryStore.load(context))
        println("Imported a real library video: ${imported.source.width}x${imported.source.height}, ${imported.source.duration} ms; library unchanged")
    }
}
