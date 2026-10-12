package com.parkingconcepts.mortimer

import android.net.Uri
import android.text.Html
import java.util.zip.ZipFile

/** The parts of an EPUB that the reader needs: reading order, contents and cover. */
class EpubPackage(
    val title: String?,
    val spine: List<String>,
    val toc: List<TocEntry>,
    val cover: String?,
) {
    data class TocEntry(val label: String, val href: String, val depth: Int)

    companion object {
        private fun attr(tag: String, name: String): String? =
            Regex("""(?:^|\s)$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)

        private fun text(zip: ZipFile, path: String): String? =
            zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) } }

        private fun plain(html: String): String =
            Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString().replace(Regex("\\s+"), " ").trim()

        /** Resolves [href] relative to the folder of [base], dropping "." and ".." segments. */
        fun resolve(base: String, href: String): String {
            val clean = Uri.decode(href.substringBefore('#'))
            val fragment = href.substringAfter('#', "")
            val parts = ArrayList<String>()
            val dir = base.substringBeforeLast('/', "")
            (if (clean.startsWith("/")) clean.drop(1) else if (dir.isEmpty()) clean else "$dir/$clean").split('/').forEach { p ->
                when (p) {
                    "", "." -> {}
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts.add(p)
                }
            }
            val path = parts.joinToString("/")
            return if (fragment.isNotEmpty()) "$path#$fragment" else path
        }

        fun read(zip: ZipFile): EpubPackage {
            val container = text(zip, "META-INF/container.xml") ?: error("EPUB no válido: falta container.xml.")
            val opfPath = Regex("""full-path\s*=\s*["']([^"']+)["']""").find(container)?.groupValues?.get(1)
                ?: error("No se encontró el paquete de lectura EPUB.")
            val opf = text(zip, opfPath) ?: error("Falta el archivo OPF.")

            data class Item(val id: String, val href: String, val type: String, val props: String)
            val items = Regex("""<(?:\w+:)?item\b([^>]*)>""", RegexOption.IGNORE_CASE).findAll(opf).mapNotNull { m ->
                val tag = m.groupValues[1]
                val id = attr(tag, "id") ?: return@mapNotNull null
                val href = attr(tag, "href") ?: return@mapNotNull null
                Item(id, resolve(opfPath, href), attr(tag, "media-type") ?: "", attr(tag, "properties") ?: "")
            }.toList()
            val byId = items.associateBy { it.id }

            val spine = Regex("""<(?:\w+:)?itemref\b([^>]*)>""", RegexOption.IGNORE_CASE).findAll(opf)
                .mapNotNull { m -> attr(m.groupValues[1], "idref")?.let { byId[it] } }
                .filter { it.type.contains("html", true) || it.href.endsWith(".xhtml", true) || it.href.endsWith(".html", true) || it.href.endsWith(".htm", true) }
                .map { it.href }
                .filter { zip.getEntry(it) != null }
                .toList()

            val title = Regex("""<dc:title[^>]*>(.*?)</dc:title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(opf)?.groupValues?.get(1)?.let { plain(it) }?.takeIf { it.isNotBlank() }

            val coverId = Regex("""<meta\b([^>]*)>""", RegexOption.IGNORE_CASE).findAll(opf)
                .map { it.groupValues[1] }
                .firstOrNull { attr(it, "name").equals("cover", true) }
                ?.let { attr(it, "content") }
            val cover = items.firstOrNull { it.props.split(' ').contains("cover-image") }?.href
                ?: coverId?.let { byId[it]?.href }
                ?: items.firstOrNull { it.type.startsWith("image/") && it.href.contains("cover", true) }?.href
                ?: items.firstOrNull { it.type.startsWith("image/") }?.href

            val toc = ArrayList<TocEntry>()
            val nav = items.firstOrNull { it.props.split(' ').contains("nav") }
            if (nav != null) {
                text(zip, nav.href)?.let { html ->
                    val section = Regex("""<nav\b[^>]*type\s*=\s*["'][^"']*toc[^"']*["'][^>]*>(.*?)</nav>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                        .find(html)?.groupValues?.get(1) ?: html
                    var depth = 0
                    Regex("""<(/?)(ol|a)\b([^>]*)>(.*?)(?=<)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(section).forEach { m ->
                        val closing = m.groupValues[1] == "/"
                        val tag = m.groupValues[2].lowercase()
                        if (tag == "ol") depth += if (closing) -1 else 1
                        else if (!closing) {
                            val href = attr(m.groupValues[3], "href")
                            val label = Regex("""<a\b[^>]*>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                                .find(section, m.range.first)?.groupValues?.get(1)?.let { plain(it) }
                            if (href != null && !label.isNullOrBlank()) toc.add(TocEntry(label, resolve(nav.href, href), (depth - 1).coerceAtLeast(0)))
                        }
                    }
                }
            }
            if (toc.isEmpty()) {
                val ncxId = Regex("""<(?:\w+:)?spine\b([^>]*)>""", RegexOption.IGNORE_CASE).find(opf)?.groupValues?.get(1)?.let { attr(it, "toc") }
                val ncx = (ncxId?.let { byId[it] } ?: items.firstOrNull { it.type.contains("ncx") })
                if (ncx != null) text(zip, ncx.href)?.let { xml ->
                    Regex("""<navPoint\b.*?<text>(.*?)</text>.*?<content\b[^>]*src\s*=\s*["']([^"']+)["']""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                        .findAll(xml).forEach { m ->
                            val label = plain(m.groupValues[1])
                            if (label.isNotBlank()) toc.add(TocEntry(label, resolve(ncx.href, m.groupValues[2]), 0))
                        }
                }
            }
            return EpubPackage(title, spine, toc, cover)
        }
    }
}
