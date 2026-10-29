package com.parkingconcepts.mortimer

import org.junit.Assert.*
import org.junit.Test

class SubtitleTimelineTest {
    @Test fun parsesSrtWithBomCrLfAndMultilineMarkup() {
        val timeline = parseSubtitles("\uFEFF1\r\n00:00:01,000 --> 00:00:03,000\r\n<i>Hola</i> &amp; adiós\r\nSegunda línea\r\n\r\n")
        assertEquals("Hola & adiós\nSegunda línea", timeline.textAt(2000, SubtitleSync()))
        assertEquals("", timeline.textAt(3000, SubtitleSync()))
    }
    @Test fun parsesWebVttSettingsAndSkipsNotes() {
        val timeline = parseSubtitles("WEBVTT\n\nNOTE Ignore this\n00:00.000 --> 00:01.000\nNote\n\n00:01.500 --> 00:02.500 align:start position:10%\n<v Voice>Hello</v>\n")
        assertEquals(1, timeline.cues.size)
        assertEquals("Hello", timeline.textAt(1500, SubtitleSync()))
    }
    @Test fun rejectsInvalidAndEmptyFiles() {
        listOf("", "1\n00:00:03,000 --> 00:00:01,000\nWrong", "00:99:00.000 --> 00:99:01.000\nWrong").forEach {
            assertThrows(IllegalArgumentException::class.java) { parseSubtitles(it) }
        }
    }
    @Test fun overlappingPhrasesRemainVisibleWhenShorterLaterPhraseHasEnded() {
        val timeline = SubtitleTimeline(listOf(SubtitleCue(0, 10_000, "Long"), SubtitleCue(1000, 2000, "Short")))
        assertEquals("Long\nShort", timeline.textAt(1500, SubtitleSync()))
        assertEquals("Long", timeline.textAt(3000, SubtitleSync()))
    }
    @Test fun manualPositiveOffsetDisplaysSubtitlesLater() {
        val timeline = SubtitleTimeline(listOf(SubtitleCue(1000, 2000, "One")))
        assertEquals("", timeline.textAt(1000, SubtitleSync(500)))
        assertEquals("One", timeline.textAt(1500, SubtitleSync(500)))
        assertEquals("One", timeline.textAt(500, SubtitleSync(-500)))
    }
    @Test fun oneReferenceCorrectsOffsetAndTwoReferencesCorrectDrift() {
        val first = SubtitleReference(10_000, 12_000)
        assertEquals(SubtitleSync(2000), SubtitleSync.calibrate(first))
        val sync = SubtitleSync.calibrate(first, SubtitleReference(110_000, 117_000))
        assertEquals(12_000, sync.videoTime(10_000))
        assertEquals(117_000, sync.videoTime(110_000))
        assertEquals(64_500, sync.videoTime(60_000))
    }
    @Test fun invalidReferencesAndScalesDoNotProduceBrokenTimelines() {
        assertThrows(IllegalArgumentException::class.java) { SubtitleReference(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { SubtitleSync(scale = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { SubtitleSync.calibrate(SubtitleReference(1000, 1000), SubtitleReference(1000, 2000)) }
        assertThrows(IllegalArgumentException::class.java) { SubtitleSync.calibrate(SubtitleReference(1000, 1000), SubtitleReference(2000, 100_000)) }
    }
    @Test fun seekingAndPlaybackSpeedUseMediaTimeNotWallClock() {
        val timeline = SubtitleTimeline(listOf(SubtitleCue(1000, 2000, "First"), SubtitleCue(3000, 4000, "Second")))
        assertEquals("Second", timeline.textAt(3500, SubtitleSync()))
        assertEquals("First", timeline.textAt(1500, SubtitleSync()))
        assertEquals("", timeline.textAt(0, SubtitleSync()))
    }
    @Test fun handlesUtf16AndWindows1252() {
        assertEquals("\uFEFFHola", decodeSubtitleBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Hola".toByteArray(Charsets.UTF_16LE)))
        assertEquals("Español", decodeSubtitleBytes("Español".toByteArray(java.nio.charset.Charset.forName("windows-1252"))))
    }
    @Test fun rejectsExcessiveCueText() {
        assertThrows(IllegalArgumentException::class.java) { parseSubtitles("00:00.000 --> 00:01.000\n" + "x".repeat(8193)) }
    }
    @Test fun acceptsFractionalTimestampPrecisionAndRejectsBadSeconds() {
        assertEquals(1500L, subtitleTimestamp("00:01.5"))
        assertEquals(1510L, subtitleTimestamp("00:01.51"))
        assertNull(subtitleTimestamp("00:60.000"))
    }
}
