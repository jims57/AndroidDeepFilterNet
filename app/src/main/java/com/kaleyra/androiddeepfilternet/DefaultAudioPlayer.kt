package com.kaleyra.androiddeepfilternet

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource

@OptIn(UnstableApi::class)
class DefaultAudioPlayer(context: Context) : AudioPlayer {

    private companion object {
        const val PROGRESS_UPDATE_DELAY_MS = 100L
    }

    private var player: ExoPlayer = ExoPlayer.Builder(context).build()
    private var mediaSources: MutableMap<String, MediaSource> = mutableMapOf()

    private var currentMediaSourceId: String? = null
    private var audioProgressCallback: AudioProgressCallback? = null

    private val handler = Handler(Looper.getMainLooper())

    private val progressUpdater = Runnable {
        updateProgress()
    }

    init {
        player.playWhenReady = false

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    audioProgressCallback?.onAudioCompleted()
                    stopProgressUpdater()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                error.printStackTrace()
            }
        })
    }

    private fun createMediaSource(id: String, buffer: ByteArray): MediaSource {
        val mediaItem = MediaItem.Builder()
            .setUri("buffer://content-$id")
            .build()

        return ProgressiveMediaSource.Factory(
            ByteBufferDataSourceFactory(buffer)
        ).createMediaSource(mediaItem)
    }

    override fun setAudioProgressCallback(callback: AudioProgressCallback?) {
        audioProgressCallback = callback
    }

    override fun addMediaSource(id: String, buffer: ByteArray) {
        mediaSources[id] = createMediaSource(id, buffer)
    }

    override fun start(id: String, resetPosition: Boolean) {
        if (currentMediaSourceId == id && player.playbackState != Player.STATE_IDLE) {
            if (!player.isPlaying) {
                player.play()
                startProgressUpdater()
            }
            return
        }

        val playbackPosition = if (resetPosition) 0L else player.currentPosition

        val mediaSource = mediaSources[id] ?: return
        currentMediaSourceId = id

        player.setMediaSource(mediaSource)
        player.prepare()

        player.seekTo(playbackPosition)

        player.play()
        startProgressUpdater()
    }

    override fun pause() {
        player.pause()
        stopProgressUpdater()
    }

    override fun stop() {
        player.stop()
        player.seekTo(0)
        stopProgressUpdater()
    }

    override fun seekTo(position: Long) {
        val trackDuration = getCurrentTrackDuration()
        if (trackDuration == C.TIME_UNSET) return
        player.seekTo(position.coerceIn(0L, trackDuration))
    }

    override fun isPlaying(): Boolean = player.isPlaying

    override fun getCurrentTrackPosition(): Long = player.currentPosition

    override fun getCurrentTrackDuration(): Long = player.duration

    override fun clearMediaSources() {
        stop()
        mediaSources.clear()
    }

    override fun release() {
        stopProgressUpdater()
        player.release()
        mediaSources.clear()
        audioProgressCallback = null
    }

    private fun startProgressUpdater() {
        stopProgressUpdater()
        handler.post(progressUpdater)
    }

    private fun stopProgressUpdater() {
        handler.removeCallbacks(progressUpdater)
    }

    private fun updateProgress() {
        val currentPosition = getCurrentTrackPosition()
        val trackDuration = getCurrentTrackDuration()

        if (trackDuration != C.TIME_UNSET) {
            audioProgressCallback?.onProgressUpdate(currentPosition, trackDuration)
        }
        handler.postDelayed(progressUpdater, PROGRESS_UPDATE_DELAY_MS)
    }
}


