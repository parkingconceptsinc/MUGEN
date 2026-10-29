package com.parkingconcepts.mortimer

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

internal data class StudioSavedProject(val project: StudioProject, val playhead: Long)
internal object StudioProjectStore {
    private fun folder(context: Context) = File(context.filesDir, "video_studio").apply { mkdirs() }
    private fun file(context: Context, id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(folder(context), "$id.json")
    }
    private fun atomic(file: File, bytes: ByteArray) {
        val target = AtomicFile(file); val out = target.startWrite()
        try { out.write(bytes); target.finishWrite(out) } catch (error: Throwable) { target.failWrite(out); throw error }
    }
    @Synchronized fun load(context: Context, id: String): StudioSavedProject {
        val source = file(context, id)
        for (candidate in listOf(source, File(source.path + ".recovery"))) {
            try {
                val json = AtomicFile(candidate).openRead().use { JSONObject(it.readBytesLimited(MAX_METADATA_BYTES).toString(Charsets.UTF_8)) }
                val project = StudioProject.from(json); require(project.id == id)
                return StudioSavedProject(project, json.optLong("playhead", 0).coerceIn(0, project.duration))
            } catch (_: Exception) { /* Preserve damaged copies for recovery. */ }
        }
        throw IOException("No se puede abrir el proyecto ni su respaldo. Se han conservado ambos archivos.")
    }
    @Synchronized fun save(context: Context, project: StudioProject, playhead: Long) {
        project.validate()
        val target = file(context, project.id)
        if (listOf(target, File(target.path + ".bak"), File(target.path + ".recovery"), File(target.path + ".recovery.bak")).any { it.exists() }) load(context, project.id)
        val bytes = project.json().put("playhead", playhead.coerceIn(0, project.duration)).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_METADATA_BYTES) { "Proyecto demasiado grande." }
        val known = list(context)
        if (!target.exists() && project.id !in known) require(known.size < 50) { "Máximo 50 proyectos. Elimina uno para crear otro." }
        atomic(target, bytes); runCatching { atomic(File(target.path + ".recovery"), bytes) }
        context.getSharedPreferences("video_studio", Context.MODE_PRIVATE).edit().putString("last", project.id).apply()
    }
    @Synchronized fun list(context: Context): List<String> = folder(context).listFiles().orEmpty()
        .filter { it.name.matches(Regex("[0-9a-f-]{36}\\.json(?:\\.recovery|\\.bak)?")) }
        .sortedByDescending { it.lastModified() }.map { it.name.substringBefore('.') }.distinct().take(50)
    @Synchronized fun last(context: Context): StudioSavedProject? {
        val id = context.getSharedPreferences("video_studio", Context.MODE_PRIVATE).getString("last", null) ?: return null
        return load(context, id)
    }
    @Synchronized fun delete(context: Context, id: String) {
        val target = file(context, id)
        listOf(target, File(target.path + ".bak"), File(target.path + ".new"), File(target.path + ".recovery"), File(target.path + ".recovery.bak"), File(target.path + ".recovery.new"))
            .forEach { if (it.exists() && !it.delete()) throw IOException("No se pudo eliminar el proyecto.") }
        val prefs = context.getSharedPreferences("video_studio", Context.MODE_PRIVATE)
        if (prefs.getString("last", null) == id) prefs.edit().remove("last").apply()
    }
}
