package com.parkingconcepts.mortimer

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.roundToLong

internal const val STUDIO_BUNDLE_MEDIA_LIMIT = 384L * 1024 * 1024
internal const val STUDIO_BUNDLE_MANIFEST_LIMIT = 1024 * 1024
internal const val STUDIO_BUNDLE_ARCHIVE_LIMIT = STUDIO_BUNDLE_MEDIA_LIMIT + 2 * 1024 * 1024
internal data class StudioBundleMedia(val id: String, val path: String, val name: String, val duration: Double,
    val width: Int, val height: Int, val size: Long, val type: String)
internal data class StudioBundleClip(val id: String, val mediaId: String, val start: Double, val end: Double,
    val speed: Double, val volume: Double, val mute: Boolean, val rotation: Int, val flip: Boolean,
    val brightness: Double, val contrast: Double, val saturation: Double)
internal data class StudioBundleManifest(val id: String, val name: String, val aspect: String, val height: Int,
    val media: List<StudioBundleMedia>, val clips: List<StudioBundleClip>) {
    fun json(): JSONObject = JSONObject().put("format", "mugen-studio").put("version", 1)
        .put("project", JSONObject().put("id", id).put("name", name).put("aspect", aspect).put("height", height)
            .put("clips", JSONArray().apply { clips.forEach { c -> put(JSONObject().put("id", c.id).put("mediaId", c.mediaId)
                .put("in", c.start).put("out", c.end).put("speed", c.speed).put("volume", c.volume).put("mute", c.mute)
                .put("rotation", c.rotation).put("flip", c.flip).put("brightness", c.brightness).put("contrast", c.contrast).put("saturation", c.saturation)) } }))
        .put("media", JSONArray().apply { media.forEach { m -> put(JSONObject().put("id", m.id).put("path", m.path).put("name", m.name)
            .put("duration", m.duration).put("width", m.width).put("height", m.height).put("size", m.size).put("type", m.type)) } })
}

/** A neutral schema, deliberately independent of SAF URIs and the native project JSON. */
internal object StudioBundleCodec {
    private fun exact(o: JSONObject, names: String) {
        require(o.keys().asSequence().toSet() == names.split(' ').toSet()) { "Unsupported or incomplete Studio bundle fields. Keep the original project." }
    }
    private fun number(o: JSONObject, key: String, min: Double, max: Double): Double {
        val raw = o.opt(key)
        require(raw is Number && raw.toDouble().isFinite() && raw.toDouble() in min..max) { "Invalid bundle value: $key." }
        return raw.toDouble()
    }
    private fun integer(o: JSONObject, key: String, min: Long, max: Long): Long {
        val n = number(o, key, min.toDouble(), max.toDouble())
        require(n % 1.0 == 0.0) { "$key must be an integer." }; return n.toLong()
    }
    private fun text(o: JSONObject, key: String, max: Int, allowEmpty: Boolean = false): String {
        val raw = o.opt(key)
        require(raw is String && raw.length <= max && !raw.contains('\u0000') && (allowEmpty || raw.isNotBlank())) { "Invalid bundle text: $key." }; return raw
    }
    private fun id(o: JSONObject, key: String): String = text(o, key, 36).also { require(runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false)) { "Bundle identifiers must be canonical UUIDs." } }
    private fun flag(o: JSONObject, key: String): Boolean { val raw = o.opt(key); require(raw is Boolean) { "Invalid bundle flag: $key." }; return raw }
    fun parse(text: String): StudioBundleManifest {
        require(text.toByteArray(Charsets.UTF_8).size <= STUDIO_BUNDLE_MANIFEST_LIMIT) { "Studio manifest exceeds 1 MiB." }
        val root = JSONObject(text); exact(root, "format version project media")
        require(root.opt("format") == "mugen-studio" && number(root, "version", 1.0, 1.0) == 1.0) { "Unsupported Studio bundle version." }
        val p = root.getJSONObject("project"); exact(p, "id name aspect height clips")
        val sources = root.getJSONArray("media"); val edits = p.getJSONArray("clips")
        require(sources.length() in 1..32 && edits.length() in 1..32) { "Portable projects support 1–32 sources and clips." }
        val media = (0 until sources.length()).map { i ->
            val m = sources.getJSONObject(i); exact(m, "id path name duration width height size type")
            val path = text(m, "path", 64)
            require(path == "media/source-${i.toString().padStart(3, '0')}.bin") { "Unsafe or noncanonical bundle media path." }
            StudioBundleMedia(id(m, "id"), path, text(m, "name", 1024), number(m, "duration", .5, 604800.0),
                integer(m, "width", 1, 32768).toInt(), integer(m, "height", 1, 32768).toInt(), integer(m, "size", 1, STUDIO_BUNDLE_MEDIA_LIMIT), text(m, "type", 128, true))
        }
        require(media.map { it.id }.distinct().size == media.size && media.sumOf { it.size } <= STUDIO_BUNDLE_MEDIA_LIMIT) { "Duplicate media or bundle exceeds 384 MiB." }
        val clips = (0 until edits.length()).map { i ->
            val c = edits.getJSONObject(i); exact(c, "id mediaId in out speed volume mute rotation flip brightness contrast saturation")
            val mediaId = id(c, "mediaId"); val source = media.singleOrNull { it.id == mediaId } ?: error("Clip media is missing.")
            val start = number(c, "in", 0.0, source.duration); val end = number(c, "out", 0.0, source.duration)
            require(end - start >= .5 - .000001) { "Keep at least half a source second in every clip." }
            val rotation = integer(c, "rotation", 0, 270).toInt(); require(rotation in listOf(0, 90, 180, 270))
            StudioBundleClip(id(c, "id"), mediaId, start, end, number(c, "speed", .25, 4.0), number(c, "volume", 0.0, 2.0), flag(c, "mute"), rotation,
                flag(c, "flip"), number(c, "brightness", -1.0, 1.0), number(c, "contrast", 0.0, 2.0), number(c, "saturation", 0.0, 2.0))
        }
        require(clips.map { it.id }.distinct().size == clips.size && clips.map { it.mediaId }.toSet() == media.map { it.id }.toSet()) { "Duplicate clips or unreferenced media in bundle." }
        require(clips.sumOf { (it.end - it.start) / it.speed } <= 1800.000001) { "Portable projects support up to 30 minutes." }
        val aspect = text(p, "aspect", 8); require(aspect in listOf("source", "16:9", "9:16", "1:1"))
        val height = integer(p, "height", 720, 1080).toInt(); require(height in listOf(720, 1080))
        return StudioBundleManifest(id(p, "id"), text(p, "name", 80), aspect, height, media, clips)
    }
    /** Canonical order is clockwise rotation followed by a world-space horizontal mirror. */
    fun nativeRotation(rotation: Int, flip: Boolean) = if (flip) rotation else (360 - rotation) % 360
    fun nativeEdit(c: StudioBundleClip, duration: Long): VideoEditSpec {
        require(c.volume <= 1.0) { "This clip boosts volume above 100%. Reduce it in the PWA before importing into Android." }
        val contrast = ((c.contrast - 1) / (c.contrast + 1)).toFloat()
        return VideoEditSpec((c.start * 1000).roundToLong(), (c.end * 1000).roundToLong(), nativeRotation(c.rotation, c.flip), c.mute,
            flip = c.flip, brightness = c.brightness.toFloat(), contrast = contrast, saturation = ((c.saturation - 1) * 100).toFloat(), speed = c.speed.toFloat(), volume = c.volume.toFloat()).validate(duration)
    }
    fun portableClip(clip: StudioClip, mediaId: String): StudioBundleClip {
        val e = clip.edit
        val contrast = (1 + e.contrast.toDouble()) / (1 - e.contrast.toDouble())
        require(contrast <= 2.000001) { "Clip contrast exceeds the portable range. Reduce native contrast to 0.33 or lower before exporting." }
        return StudioBundleClip(clip.id, mediaId, e.startMs / 1000.0, e.endMs / 1000.0, e.speed.toDouble(), e.volume.toDouble(), e.mute,
            nativeRotation(e.rotation, e.flip), e.flip, e.brightness.toDouble(), contrast.coerceAtMost(2.0), 1 + e.saturation / 100.0)
    }
}

internal data class StudioZipRecord(val name: String, val offset: Long, val size: Long, val crc: Long)
/** Stored-only ZIP reader checks both directory and local headers before reading any payload. */
internal object StudioStoredZip {
    private fun u16(f: RandomAccessFile): Int = f.readUnsignedByte() or (f.readUnsignedByte() shl 8)
    private fun u32(f: RandomAccessFile): Long = u16(f).toLong() or (u16(f).toLong() shl 16)
    private fun name(bytes: ByteArray): String {
        val value = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        require(value == "manifest.json" || Regex("media/source-[0-9]{3}\\.bin").matches(value)) { "Unsafe bundle entry path." }; return value
    }
    fun index(file: File): List<StudioZipRecord> = RandomAccessFile(file, "r").use { f ->
        require(f.length() in 22..STUDIO_BUNDLE_ARCHIVE_LIMIT) { "Bundle exceeds the supported archive size." }
        // Canonical bundles have no comment, no trailing bytes, no split disks and no ZIP64.
        f.seek(f.length() - 22); require(u32(f) == 0x06054b50L) { "Invalid Studio ZIP directory." }
        require(u16(f) == 0 && u16(f) == 0) { "Split ZIP bundles are unsupported." }
        val count = u16(f); require(count in 2..33 && u16(f) == count)
        val directorySize = u32(f); val directory = u32(f); require(u16(f) == 0 && directory + directorySize == f.length() - 22)
        f.seek(directory)
        data class Entry(val name: String, val crc: Long, val size: Long, val local: Long, val flags: Int)
        val entries = (0 until count).map {
            require(u32(f) == 0x02014b50L) { "Invalid bundle central header." }
            u16(f); require(u16(f) <= 20)
            val flags = u16(f); require(flags == 0 || flags == 0x800) { "Encrypted or streaming ZIP entries are unsupported." }
            require(u16(f) == 0) { "Studio bundles must use ZIP STORE (no compression)." }
            u16(f); u16(f); val crc = u32(f); val compressed = u32(f); val size = u32(f)
            val nameLength = u16(f); val extra = u16(f); val comment = u16(f)
            require(nameLength in 1..64 && extra == 0 && comment == 0 && compressed == size && size <= STUDIO_BUNDLE_MEDIA_LIMIT)
            require(u16(f) == 0); u16(f); val attributes = u32(f)
            require((attributes ushr 16 and 0xf000) != 0xa000L) { "Symlinks are not supported." }
            val local = u32(f); val raw = ByteArray(nameLength).also(f::readFully)
            Entry(name(raw), crc, size, local, flags)
        }
        require(f.filePointer == directory + directorySize && entries.map { it.name }.distinct().size == count)
        val sorted = entries.sortedBy { it.local }
        var next = 0L
        val result = sorted.map { e ->
            require(e.local == next && e.local < directory) { "Overlapping or hidden ZIP content." }; f.seek(e.local)
            require(u32(f) == 0x04034b50L && u16(f) <= 20 && u16(f) == e.flags && u16(f) == 0)
            u16(f); u16(f); require(u32(f) == e.crc && u32(f) == e.size && u32(f) == e.size)
            val nameLength = u16(f); require(nameLength in 1..64 && u16(f) == 0)
            val raw = ByteArray(nameLength).also(f::readFully); require(name(raw) == e.name)
            val offset = f.filePointer; next = offset + e.size; require(next <= directory)
            StudioZipRecord(e.name, offset, e.size, e.crc)
        }
        require(next == directory && result.count { it.name == "manifest.json" } == 1)
        result
    }
    fun copy(file: File, entry: StudioZipRecord, output: OutputStream, checkCancelled: () -> Unit = {}) {
        RandomAccessFile(file, "r").use { f ->
            f.seek(entry.offset); var remaining = entry.size; val crc = CRC32(); val buffer = ByteArray(64 * 1024)
            while (remaining > 0) { checkCancelled(); val count = f.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt()); require(count > 0) { "Incomplete bundle entry." }; crc.update(buffer, 0, count); output.write(buffer, 0, count); remaining -= count }
            require(crc.value == entry.crc) { "Bundle checksum failed for ${entry.name}." }
        }
    }
}

/** No project is registered until all media and metadata have passed validation. */
internal object StudioBundleTransfer {
    private fun mediaFolder(context: Context) = File(context.filesDir, "video_studio_media").apply { check(mkdirs() || isDirectory) }
    fun importFile(context: Context, archive: File, checkCancelled: () -> Unit = {},
        probe: (File, StudioBundleMedia) -> StudioSource = { file, m -> readStudioSource(context, Uri.fromFile(file), m.name) }): StudioSavedProject {
        val entries = StudioStoredZip.index(archive)
        val manifestEntry = entries.single { it.name == "manifest.json" }
        require(manifestEntry.size <= STUDIO_BUNDLE_MANIFEST_LIMIT)
        val manifestBytes = java.io.ByteArrayOutputStream(); StudioStoredZip.copy(archive, manifestEntry, manifestBytes, checkCancelled)
        val manifest = StudioBundleCodec.parse(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(manifestBytes.toByteArray())).toString())
        require(entries.map { it.name }.toSet() == manifest.media.map { it.path }.toSet() + "manifest.json") { "Bundle entries do not match the manifest." }
        val id = UUID.randomUUID().toString() // Imports always create a new project; existing projects are never replaced.
        val root = mediaFolder(context); val staging = File(root, ".import-$id"); val target = File(root, id)
        check(staging.mkdir() && !target.exists())
        var registered = false
        try {
            val sources = manifest.media.associate { m ->
                checkCancelled()
                val entry = entries.single { it.name == m.path }; require(entry.size == m.size)
                val sourceFile = File(staging, m.path.substringAfter('/'))
                sourceFile.outputStream().use { StudioStoredZip.copy(archive, entry, it, checkCancelled) }
                val source = probe(sourceFile, m).validate()
                require(abs(source.duration / 1000.0 - m.duration) <= .1 && source.width == m.width && source.height == m.height) { "Video metadata does not match the bundle manifest: ${m.name}." }
                m.id to source.copy(uri = Uri.fromFile(File(target, sourceFile.name)).toString())
            }
            val project = StudioProject(id, manifest.name, manifest.clips.map { c ->
                val source = sources.getValue(c.mediaId)
                StudioClip(UUID.randomUUID().toString(), source, StudioBundleCodec.nativeEdit(c, source.duration))
            }, manifest.aspect, manifest.height).validate()
            checkCancelled(); check(staging.renameTo(target)) { "Could not commit imported media." }
            checkCancelled(); StudioProjectStore.save(context, project, 0); registered = true
            return StudioSavedProject(project, 0)
        } finally {
            staging.deleteRecursively()
            if (!registered) { target.deleteRecursively(); runCatching { StudioProjectStore.delete(context, id) } }
        }
    }
    fun exportFile(context: Context, project: StudioProject, output: File, checkCancelled: () -> Unit = {}) {
        project.validate()
        require(project.clips.size <= 32 && project.duration <= 1_800_000) { "Portable projects support up to 32 clips and 30 minutes." }
        require(project.height in listOf(720, 1080)) { "Choose 720p or 1080p before exporting a portable project." }
        val unique = project.clips.map { it.source }.distinctBy { it.uri }
        val captions = context.getSharedPreferences("video_settings", Context.MODE_PRIVATE)
        require(unique.none { captions.getString("${mediaKey(it.uri)}_subtitle", null) != null }) { "This project has external subtitles. Export its SRT separately and remove source subtitle assignments before creating a portable project." }
        data class Source(val native: StudioSource, val media: StudioBundleMedia, val crc: Long)
        var total = 0L
        val sources = unique.mapIndexed { i, source ->
            checkCancelled(); val crc = CRC32(); var size = 0L
            context.contentResolver.openInputStream(Uri.parse(source.uri))?.use { input ->
                val sink = object : OutputStream() { override fun write(value: Int) { crc.update(value); size++ }; override fun write(bytes: ByteArray, offset: Int, count: Int) { crc.update(bytes, offset, count); size += count } }
                input.copyLimitedTo(sink, STUDIO_BUNDLE_MEDIA_LIMIT - total, checkCancelled)
            } ?: error("Could not read ${source.title}. Reconnect its source folder.")
            require(size > 0); total += size
            Source(source, StudioBundleMedia(UUID.randomUUID().toString(), "media/source-${i.toString().padStart(3, '0')}.bin", source.title, source.duration / 1000.0, source.width, source.height, size, context.contentResolver.getType(Uri.parse(source.uri)) ?: ""), crc.value)
        }
        val manifest = StudioBundleManifest(project.id, project.name, project.aspect, project.height, sources.map { it.media },
            project.clips.map { c -> StudioBundleCodec.portableClip(c, sources.single { it.native.uri == c.source.uri }.media.id) })
        val bytes = manifest.json().toString().toByteArray(Charsets.UTF_8)
        StudioBundleCodec.parse(bytes.toString(Charsets.UTF_8))
        fun zipEntry(name: String, size: Long, crc: Long) = ZipEntry(name).apply { method = ZipEntry.STORED; this.size = size; compressedSize = size; this.crc = crc; time = 315619200000L }
        try {
            ZipOutputStream(output.outputStream(), Charsets.UTF_8).use { zip ->
                zip.putNextEntry(zipEntry("manifest.json", bytes.size.toLong(), CRC32().apply { update(bytes) }.value)); zip.write(bytes); zip.closeEntry()
                sources.forEach { s ->
                    checkCancelled(); zip.putNextEntry(zipEntry(s.media.path, s.media.size, s.crc))
                    val copied = context.contentResolver.openInputStream(Uri.parse(s.native.uri))?.use { it.copyLimitedTo(zip, s.media.size, checkCancelled) } ?: error("Source access expired.")
                    require(copied == s.media.size) { "A source changed during export. Retry after its original file is stable." }; zip.closeEntry()
                }
            }
            StudioStoredZip.index(output)
        } catch (failure: Throwable) { output.delete(); throw failure }
    }
    fun releaseOwnedMedia(context: Context, removed: StudioProject) {
        val root = mediaFolder(context).canonicalFile
        val other = StudioProjectStore.list(context).map { runCatching { StudioProjectStore.load(context, it).project }.getOrNull() }
        if (other.any { it == null }) return // An unreadable project may still own a reference; preserve its media.
        val referenced = other.filterNotNull().flatMap { it.clips }.map { it.source.uri }.toSet()
        removed.clips.map { it.source.uri }.distinct().filterNot { it in referenced }.forEach { raw ->
            val uri = Uri.parse(raw)
            if (uri.scheme == "file") {
                val file = File(uri.path ?: return@forEach).canonicalFile
                val folder = file.parentFile
                if (folder?.parentFile == root && runCatching { UUID.fromString(folder.name).toString() == folder.name }.getOrDefault(false)) {
                    if (referenced.none { reference -> Uri.parse(reference).path?.startsWith(folder.path + File.separator) == true }) folder.deleteRecursively()
                }
            }
        }
    }
}
