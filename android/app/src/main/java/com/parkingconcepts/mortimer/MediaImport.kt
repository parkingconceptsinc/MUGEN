package com.parkingconcepts.mortimer

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/** One classifier for picked files, folder imports and the device scanner. */
internal data class LibraryFileRoute(val kind: String, val format: String)
internal val PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "jfif", "png", "gif", "webp", "avif", "bmp", "heic", "heif")
internal val TEXT_EXTENSIONS = setOf("txt", "md", "markdown", "log", "nfo", "csv", "tsv", "json", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf", "srt", "vtt", "ass", "ssa", "sub")

/** Inspect the ZIP directory without extracting images or loading the whole file. */
internal fun classifyZipLibraryFile(file: File): LibraryFileRoute? = runCatching {
    if (file.length() > MAX_CACHE_BYTES) return@runCatching null
    ZipFile(file).use { zip ->
        if (zip.getEntry("META-INF/container.xml") != null) return@use LibraryFileRoute("books", "epub")
        val entries = zip.entries()
        var count = 0
        var images = 0
        while (entries.hasMoreElements()) {
            if (++count > 5000) return@use null
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val extension = extensionOf(entry.name)
            if (extension in PHOTO_EXTENSIONS) images++
            else if (extension !in setOf("xml", "txt", "nfo", "json", "db")) return@use null
        }
        if (images > 0) LibraryFileRoute("comics", "cbz") else null
    }
}.getOrNull()

internal fun classifyLibraryDocument(context: Context, uri: Uri, name: String, mime: String): LibraryFileRoute? {
    classifyLibraryFile(name, mime)?.let { return it }
    if (extensionOf(name) != "zip") return null
    // Seekable local SAF documents can be inspected through their descriptor.
    // Pipes/cloud streams are skipped rather than copying an unbounded archive.
    return runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            if (descriptor.statSize < 0) return@use null
            classifyZipLibraryFile(File("/proc/self/fd/${descriptor.fd}"))
        }
    }.getOrNull()
}

internal fun classifyLibraryFile(name: String, mime: String = ""): LibraryFileRoute? {
    val extension = extensionOf(name)
    when {
        extension in VIDEO_EXTENSIONS -> return LibraryFileRoute("videos", "video")
        extension in AUDIO_EXTENSIONS -> return LibraryFileRoute("audio", "audio")
        extension in PHOTO_EXTENSIONS -> return LibraryFileRoute("photos", "photo")
        extension == "epub" || extension == "pdf" -> return LibraryFileRoute("books", extension)
        extension in TEXT_EXTENSIONS -> return LibraryFileRoute("books", "txt")
        extension == "cbz" -> return LibraryFileRoute("comics", "cbz")
        extension in setOf("cbr", "rar", "cb7", "7z") -> return LibraryFileRoute("comics", "cbr")
    }
    // Do not let an incorrect provider MIME type override an unsupported extension.
    if (extension.isNotEmpty() && extension != "bin") return null
    val type = mime.substringBefore(';').trim().lowercase(Locale.ROOT)
    return when {
        type.startsWith("audio/") -> LibraryFileRoute("audio", "audio")
        type.startsWith("video/") -> LibraryFileRoute("videos", "video")
        type in setOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/avif", "image/bmp", "image/heic", "image/heif") -> LibraryFileRoute("photos", "photo")
        type == "application/pdf" -> LibraryFileRoute("books", "pdf")
        type == "application/epub+zip" -> LibraryFileRoute("books", "epub")
        type in setOf("text/plain", "text/markdown", "text/csv") -> LibraryFileRoute("books", "txt")
        type in setOf("application/x-cbz", "application/vnd.comicbook+zip") -> LibraryFileRoute("comics", "cbz")
        type in setOf("application/x-cbr", "application/vnd.comicbook-rar") -> LibraryFileRoute("comics", "cbr")
        else -> null
    }
}

internal fun importSummary(items: List<LibraryItem>): String {
    val labels = listOf("audio" to "music", "videos" to "videos", "books" to "books", "comics" to "comics", "photos" to "photos")
    return labels.mapNotNull { (kind, label) -> items.count { it.kind == kind }.takeIf { it > 0 }?.let { "$it $label" } }.joinToString(" · ")
}
