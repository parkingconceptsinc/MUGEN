package com.parkingconcepts.mortimer

import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects

@UnstableApi
internal fun videoEditEffects(spec: VideoEditSpec, preview: Boolean = false): List<Effect> = buildList {
    if (spec.rotation != 0 || spec.flip) add(ScaleAndRotateTransformation.Builder()
        .setScale(if (spec.flip) -1f else 1f, 1f).setRotationDegrees(spec.rotation.toFloat()).build())
    if (spec.brightness != 0f) add(Brightness(spec.brightness))
    if (spec.contrast != 0f) add(Contrast(spec.contrast))
    if (spec.saturation != 0f) add(HslAdjustment.Builder().adjustSaturation(spec.saturation).build())
    if (spec.aspect != "source") add(Presentation.createForAspectRatio(aspectRatio(spec.aspect), Presentation.LAYOUT_SCALE_TO_FIT))
    if (spec.height != 0) add(Presentation.createForHeight(spec.height))
    // ExoPlayer preview uses speed with pitch preservation, without a second timestamp transformation.
    if (!preview && spec.speed != 1f) add(SpeedChangeEffect(spec.speed))
}
internal fun aspectRatio(aspect: String): Float = when (aspect) { "9:16" -> 9f / 16; "1:1" -> 1f; else -> 16f / 9 }

@UnstableApi
internal fun editAudioProcessors(spec: VideoEditSpec): List<AudioProcessor> = buildList {
    if (spec.speed != 1f) add(SonicAudioProcessor().apply { setSpeed(spec.speed); setPitch(1f) })
    if (spec.volume != 1f) add(ChannelMixingAudioProcessor().apply {
        for (channels in 1..8) putChannelMixingMatrix(ChannelMixingMatrix.create(channels, channels).scaleBy(spec.volume))
    })
}

@UnstableApi
internal fun editedVideoItem(uri: Uri, spec: VideoEditSpec, duration: Long): EditedMediaItem {
    spec.validate(duration)
    val media = MediaItem.Builder().setUri(uri).setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder().setStartPositionMs(spec.startMs).setEndPositionMs(spec.endMs).build()).build()
    return EditedMediaItem.Builder(media).setRemoveAudio(spec.mute || spec.volume == 0f)
        .setEffects(Effects(editAudioProcessors(spec), videoEditEffects(spec))).build()
}

@UnstableApi
internal fun studioVideoEffects(project: StudioProject, clip: StudioClip, preview: Boolean = false): List<Effect> = buildList {
    addAll(videoEditEffects(clip.edit.copy(height = 0, aspect = "source"), preview))
    val first = project.clips.first()
    val swap = first.edit.rotation % 180 != 0
    val width = (if (swap) first.source.height else first.source.width).coerceAtLeast(1)
    val height = (if (swap) first.source.width else first.source.height).coerceAtLeast(1)
    val ratio = if (project.aspect == "source" && first.source.width > 0 && first.source.height > 0) width.toFloat() / height else aspectRatio(project.aspect)
    add(Presentation.createForAspectRatio(ratio, Presentation.LAYOUT_SCALE_TO_FIT))
    add(Presentation.createForHeight(if (project.height == 0) height.takeIf { it > 1 } ?: 720 else project.height))
}

@UnstableApi
internal fun studioEditedItem(project: StudioProject, clip: StudioClip): EditedMediaItem {
    val item = editedVideoItem(Uri.parse(clip.source.uri), clip.edit, clip.source.duration)
    return EditedMediaItem.Builder(item.mediaItem).setRemoveAudio(item.removeAudio)
        .setEffects(Effects(editAudioProcessors(clip.edit), studioVideoEffects(project, clip))).build()
}

@UnstableApi
internal fun studioComposition(project: StudioProject): Composition {
    project.validate()
    return Composition.Builder(EditedMediaItemSequence.Builder(project.clips.map { studioEditedItem(project, it) }).build())
        .experimentalSetForceAudioTrack(project.clips.any { !it.edit.mute && it.edit.volume > 0f }).build()
}
