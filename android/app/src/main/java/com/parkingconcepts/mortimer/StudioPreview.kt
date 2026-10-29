package com.parkingconcepts.mortimer

import android.content.Context
import androidx.compose.runtime.*
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/** All transport uses PROJECT milliseconds. ExoPlayer's clipped SOURCE clock stays inside this adapter. */
@UnstableApi
internal class StudioPreview(context: Context, private val scope: CoroutineScope) {
    val player = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
        setHandleAudioBecomingNoisy(true); setSeekParameters(SeekParameters.EXACT)
    }
    var position by mutableLongStateOf(0); private set
    var wantedPlaying by mutableStateOf(false); private set
    var updating by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var loop by mutableStateOf(false)
    private var project: StudioProject? = null
    private var appliedClip: StudioClip? = null
    private var appliedCanvas: List<Any>? = null
    private var job: Job? = null
    private var requestedRevision = 0
    private var appliedRevision = -1
    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && appliedRevision == requestedRevision) { updating = false; if (wantedPlaying) player.play() }
                if (state == Player.STATE_ENDED && !updating) {
                    val p = project ?: return
                    val index = p.clips.indexOfFirst { it.id == appliedClip?.id }
                    if (wantedPlaying && index in 0 until p.clips.lastIndex) seek(p.offset(index + 1))
                    else if (wantedPlaying && loop) seek(0)
                    else { position = if (index >= 0) p.offset(index) + p.clips[index].duration else p.duration; wantedPlaying = false }
                }
            }
            override fun onPlayerError(failure: PlaybackException) {
                updating = false; wantedPlaying = false
                error = "No se pudo previsualizar: ${failure.errorCodeName}. Reabre el archivo o reduce la resolución."
            }
        })
    }
    fun load(value: StudioProject, at: Long = 0) { pause(); project = value; appliedClip = null; seek(at) }
    fun update(value: StudioProject) {
        val old = project
        if (old == value) return
        tick()
        val location = old?.locate(position)
        val id = location?.let { old.clips[it.index].id }
        val preserved = if (id != null && value.clips.any { it.id == id }) value.projectTime(id, location.sourceMs) else position.coerceAtMost(value.duration)
        project = value; seek(preserved)
    }
    fun tick() {
        val p = project ?: return
        val clip = appliedClip ?: return
        if (!updating && player.playbackState == Player.STATE_READY && p.clips.any { it.id == clip.id }) {
            position = p.projectTime(clip.id, clip.edit.startMs + player.currentPosition.coerceAtLeast(0))
        }
    }
    fun seek(at: Long) {
        val p = project ?: return
        position = at.coerceIn(0, p.duration)
        val loc = p.locate(position)
        val clip = p.clips[loc.index]
        val first = p.clips.first()
        val canvas: List<Any> = listOf(p.aspect, p.height, first.source.width, first.source.height, first.edit.rotation)
        if (!updating && clip == appliedClip && canvas == appliedCanvas && player.playerError == null) {
            player.seekTo((loc.sourceMs - clip.edit.startMs).coerceAtLeast(0)); return
        }
        job?.cancel(); player.pause(); updating = true; error = null
        val request = ++requestedRevision
        job = scope.launch {
            delay(100) // Coalesce fast scrubs and inspector changes, without losing the last requested position.
            try {
                player.setVideoEffects(studioVideoEffects(p, clip, preview = true))
                player.volume = if (clip.edit.mute) 0f else clip.edit.volume
                player.playbackParameters = PlaybackParameters(clip.edit.speed, 1f)
                player.setMediaItem(studioEditedItem(p, clip).mediaItem, loc.sourceMs - clip.edit.startMs)
                appliedClip = clip; appliedCanvas = canvas
                appliedRevision = request
                player.prepare()
            } catch (failure: Exception) { updating = false; wantedPlaying = false; error = failure.message ?: "Vista previa no disponible." }
        }
    }
    fun play() {
        val p = project ?: return
        if (position >= p.duration) seek(0)
        else if (player.playbackState == Player.STATE_ENDED) seek(position)
        wantedPlaying = true
        if (!updating && player.playerError == null) player.play()
    }
    fun pause() { wantedPlaying = false; player.pause() }
    fun step(frames: Int) {
        val p = project ?: return
        pause()
        val loc = p.locate(position); val clip = p.clips[loc.index]
        val delta = (frames * 1000 / clip.source.fps / clip.edit.speed).roundToLong()
        seek(position + delta)
    }
    fun release() { job?.cancel(); player.release() }
}
