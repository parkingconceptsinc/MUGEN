package com.parkingconcepts.mortimer

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class BookSearchHit(val chapter: Int, val count: Int, val snippet: String)
internal data class BookSearchResult(val hits: List<BookSearchHit>, val searched: Int, val limited: Boolean, val unreadable: Int)

/** Skip non-reading content with a forward-only scan, including malformed/unclosed head or script tags. */
internal fun bookSearchText(html: String, checkCancelled: () -> Unit = {}): String {
    val visible = StringBuilder(minOf(html.length, 1024 * 1024))
    var cursor = 0
    while (cursor < html.length) {
        checkCancelled()
        val open = html.indexOf('<', cursor)
        if (open < 0) { visible.append(html, cursor, html.length); break }
        visible.append(html, cursor, open)
        val end = html.indexOf('>', open + 1)
        if (end < 0) { visible.append(html, open, html.length); break }
        val tag = html.substring(open + 1, minOf(end, open + 64)).trimStart()
            .takeWhile { it.isLetterOrDigit() }.lowercase(java.util.Locale.ROOT)
        if (tag in listOf("head", "script", "style")) {
            val close = html.indexOf("</$tag", end + 1, ignoreCase = true)
            cursor = if (close < 0) html.length else {
                val closeEnd = html.indexOf('>', close + 2 + tag.length)
                if (closeEnd < 0) html.length else closeEnd + 1
            }
        } else { visible.append(html, open, end + 1); cursor = end + 1 }
    }
    return android.text.Html.fromHtml(visible.toString(), android.text.Html.FROM_HTML_MODE_LEGACY).toString()
}

/** Search one resource at a time. Never retain a whole decompressed book or build a regex from user input. */
internal suspend fun searchBook(chapters: List<String>, query: String, read: (String) -> ByteArray?,
    plainText: (String) -> String, maxBytes: Long = 64L * 1024 * 1024, maxMatches: Int = 500): BookSearchResult {
    require(maxBytes > 0 && maxMatches > 0)
    val term = query.trim().take(512)
    if (term.length < 2) return BookSearchResult(emptyList(), 0, false, 0)
    val results = ArrayList<BookSearchHit>()
    var bytes = 0L
    var count = 0
    var searched = 0
    var unreadable = 0
    for ((index, path) in chapters.withIndex()) {
        currentCoroutineContext().ensureActive()
        val resource = try { read(path) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        if (resource == null) { unreadable++; continue }
        bytes += resource.size
        if (bytes > maxBytes) return BookSearchResult(results, searched, true, unreadable)
        val text = plainText(decodeBookMarkup(resource))
        var at = text.indexOf(term, ignoreCase = true)
        val first = at
        var chapterCount = 0
        while (at >= 0 && count < maxMatches) {
            currentCoroutineContext().ensureActive()
            chapterCount++; count++
            at = text.indexOf(term, at + term.length, ignoreCase = true)
        }
        searched++
        if (first >= 0) results.add(BookSearchHit(index, chapterCount,
            text.substring((first - 60).coerceAtLeast(0), (first + term.length + 100).coerceAtMost(text.length))
                .replace(Regex("\\s+"), " ").trim()))
        if (count >= maxMatches) return BookSearchResult(results, searched, at >= 0 || index < chapters.lastIndex, unreadable)
    }
    return BookSearchResult(results, searched, false, unreadable)
}
