package com.yichincho.englishbbc.player

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.yichincho.englishbbc.data.LibraryItem
import com.yichincho.englishbbc.data.Repo

/** The UI's handle on [PlaybackService]; its fields are Compose state. */
class PlayerHolder(private val context: Context) {
    private var future: ListenableFuture<MediaController>? = null

    var controller by mutableStateOf<MediaController?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var mediaId by mutableStateOf<String?>(null)
        private set
    var positionMs by mutableLongStateOf(0)
        private set
    var durationMs by mutableLongStateOf(0)
        private set

    /** null = follow the day/night rule. */
    var speedOverride by mutableStateOf<Float?>(null)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)
    }

    private fun sync(p: Player) {
        isPlaying = p.isPlaying
        mediaId = p.currentMediaItem?.mediaId
        positionMs = p.currentPosition
        durationMs = p.duration.coerceAtLeast(0)
    }

    fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            try {
                val c = f.get()
                c.addListener(listener)
                sync(c)
                controller = c
            } catch (e: Exception) {
                controller = null
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        savePosition()
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
    }

    /** Refreshes the position; called on a timer while the UI is alive. */
    fun tick() {
        controller?.let { positionMs = it.currentPosition }
    }

    fun savePosition() {
        val c = controller ?: return
        val id = c.currentMediaItem?.mediaId ?: return
        if (c.playbackState != Player.STATE_ENDED) Repo.savePosition(id, c.currentPosition)
    }

    /** Loads the episode (unless it is already loaded) and starts playing from [startMs] or where it was left. */
    fun open(item: LibraryItem, startMs: Long? = null) {
        val c = controller ?: return
        val id = item.episode.id
        if (c.currentMediaItem?.mediaId != id) {
            savePosition()
            val mediaItem = MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(item.episode.title).setArtist(item.episode.feedTitle).build()
                )
                .build()
            c.setMediaItem(mediaItem, startMs ?: item.lastPositionMs)
            c.prepare()
        } else if (startMs != null) {
            c.seekTo(startMs)
        }
        c.play()
    }

    fun toggle() {
        val c = controller ?: return
        when {
            c.isPlaying -> c.pause()
            c.playbackState == Player.STATE_ENDED -> { c.seekTo(0); c.play() }
            else -> c.play()
        }
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms.coerceAtLeast(0))
        positionMs = ms.coerceAtLeast(0)
    }

    fun stopIf(id: String) {
        val c = controller ?: return
        if (c.currentMediaItem?.mediaId == id) {
            c.stop()
            c.clearMediaItems()
        }
    }
}
