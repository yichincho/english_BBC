package com.yichincho.englishbbc.player

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.yichincho.englishbbc.MainActivity
import com.yichincho.englishbbc.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Keeps the audio playing with the screen off. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var loudness: LoudnessEnhancer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @OptIn(UnstableApi::class) // ExoPlayer.setAudioSessionId
    override fun onCreate() {
        super.onCreate()
        // A fixed audio session lets the loudness effect attach before playback starts.
        val audioSessionId = getSystemService(AudioManager::class.java).generateAudioSessionId()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.audioSessionId = audioSessionId
        loudness = try {
            LoudnessEnhancer(audioSessionId)
        } catch (e: RuntimeException) {
            null // Some phones do not offer the effect; playback still works at normal volume.
        }
        scope.launch {
            Repo.settings.map { it.volumeBoostDb }.distinctUntilChanged().collect { db ->
                loudness?.run {
                    setTargetGain(db * 100) // millibels
                    setEnabled(db > 0)
                }
            }
        }
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) player.currentMediaItem?.let { Repo.recordListened(it.mediaId) }
            }

            // With single-episode loop on, the episode never reaches STATE_ENDED; each wrap-around counts as one listen.
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) mediaItem?.let { Repo.recordListened(it.mediaId) }
            }
        })
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(openApp).setCallback(Callback()).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        loudness?.release()
        loudness = null
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private class Callback : MediaSession.Callback {
        /** The controller only needs to send the episode id; the file location is resolved here. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { it.buildUpon().setUri(Uri.fromFile(Repo.audioFile(it.mediaId))).build() }.toMutableList()
        )
    }
}
