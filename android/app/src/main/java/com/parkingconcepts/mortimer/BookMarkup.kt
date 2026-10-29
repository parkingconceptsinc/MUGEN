package com.parkingconcepts.mortimer

import java.nio.charset.Charset

/** XML/HTML may declare UTF-16 or a legacy encoding. Keep the declaration and emitted bytes consistent. */
internal fun decodeBookMarkup(bytes: ByteArray): String {
    val charset = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
        bytes.size >= 4 && bytes[0] == '<'.code.toByte() && bytes[1] == 0.toByte() -> Charsets.UTF_16LE
        bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == '<'.code.toByte() -> Charsets.UTF_16BE
        else -> {
            val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1)
            val declared = Regex("""(?i)(?:encoding\s*=\s*["']|charset\s*=\s*["']?)([A-Za-z0-9._-]+)""").find(head)?.groupValues?.get(1)
            declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        }
    }
    return String(bytes, charset).removePrefix("\uFEFF")
}
