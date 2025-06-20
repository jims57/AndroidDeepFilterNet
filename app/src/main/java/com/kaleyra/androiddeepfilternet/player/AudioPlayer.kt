package com.kaleyra.androiddeepfilternet.player

interface AudioProgressCallback {
    fun onProgressUpdate(currentPosition: Long, totalDuration: Long)

    fun onAudioCompleted()
}

interface AudioPlayer {
    fun setAudioProgressCallback(callback: AudioProgressCallback?)

    fun addMediaSource(id: String, buffer: ByteArray)

    fun start(id: String, resetPosition: Boolean = true)

    fun pause()

    fun stop()

    fun seekTo(position: Long)

    fun isPlaying(): Boolean

    fun getCurrentTrackPosition(): Long

    fun getCurrentTrackDuration(): Long

    fun clearMediaSources()

    fun release()
}
