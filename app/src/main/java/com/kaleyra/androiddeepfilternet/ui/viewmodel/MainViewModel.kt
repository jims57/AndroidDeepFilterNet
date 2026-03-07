package com.kaleyra.androiddeepfilternet.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kaleyra.androiddeepfilternet.player.AudioPlayer
import com.kaleyra.androiddeepfilternet.player.AudioProgressCallback
import com.kaleyra.androiddeepfilternet.utils.AudioVariantGenerator
import com.kaleyra.androiddeepfilternet.player.DefaultAudioPlayer
import com.kaleyra.androiddeepfilternet.filter.DefaultDeepAudioFilter
import com.kaleyra.androiddeepfilternet.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class NoisyAudioSource {
    Airplane,
    Crowd,
    Restaurant,
    ClientAudio
}

data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val totalDurationMs: Long = 0L,
) {
    val progress: Float
        get() = if (totalDurationMs > 0) currentPositionMs.toFloat() / totalDurationMs.toFloat() else 0f
}

data class NoiseFilterUiState(
    val isAudioReady: Boolean = true,
    val selectedAudioSource: NoisyAudioSource = NoisyAudioSource.Airplane,
    val isNoiseFilterEnabled: Boolean = false,
    val initialAttenuationLevel: Float = 50f,
    val playbackState: PlaybackState = PlaybackState(),
)

sealed class UserIntent {
    data class PlayAudioSource(val source: NoisyAudioSource) : UserIntent()

    data object TogglePlayback : UserIntent()

    data class ToggleNoiseFilter(val enabled: Boolean) : UserIntent()

    data class SeekPlayback(val progress: Float) : UserIntent()

    data object SeekForward : UserIntent()

    data object SeekBackward : UserIntent()

    data class AttenuationLevel(val value: Float) : UserIntent()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val TAG = "NoiseFilterViewModel"

        const val DEFAULT_SEEK_OFFSET_MS = 5_000L
    }

    private val _uiState: MutableStateFlow<NoiseFilterUiState> = MutableStateFlow(NoiseFilterUiState())
    val uiState: StateFlow<NoiseFilterUiState> = _uiState.asStateFlow()

    private var audioPlayer: AudioPlayer = DefaultAudioPlayer(application).apply {
        setAudioProgressCallback(
            object: AudioProgressCallback {
                override fun onProgressUpdate(currentPosition: Long, totalDuration: Long) {
                    _uiState.update {
                        it.copy(playbackState = it.playbackState.copy(isPlaying = true, currentPositionMs = currentPosition, totalDurationMs = totalDuration))
                    }
                }

                override fun onAudioCompleted() {
                    stop()
                    _uiState.update {
                        it.copy(playbackState = it.playbackState.copy(isPlaying = false, currentPositionMs = 0L, totalDurationMs = 0L))
                    }
                }
            }
        )
    }

    private val deepFilterNet = DefaultDeepAudioFilter(application)
    private val audioVariantGenerator = AudioVariantGenerator(deepFilterNet)

    var processAudioSourcesJob: Job? = null

    init {
        processAudioSources(_uiState.value.initialAttenuationLevel)
    }

    fun processIntent(intent: UserIntent) {
        viewModelScope.launch {
            when (intent) {
                is UserIntent.PlayAudioSource -> playAudio(intent.source, _uiState.value.isNoiseFilterEnabled)

                UserIntent.TogglePlayback -> togglePlayback()

                is UserIntent.ToggleNoiseFilter -> toggleNoiseFilter(intent.enabled)

                is UserIntent.SeekPlayback -> seekPlayback(intent.progress)

                UserIntent.SeekBackward -> seekForward()

                UserIntent.SeekForward -> seekBackward()

                is UserIntent.AttenuationLevel -> processAudioSources(intent.value)
            }
        }
    }

    private fun playAudio(source: NoisyAudioSource, isNoiseFilterEnabled: Boolean) {
        audioPlayer.start(
            id = getMediaSourceId(source, isNoiseFilterEnabled),
            resetPosition = false
        )
        Log.d(TAG, "Playing audio.")
        _uiState.update { it.copy(selectedAudioSource = source) }
    }

    private fun togglePlayback() {
        val uiState = _uiState.value
        if (uiState.playbackState.isPlaying) pauseAudio()
        else playAudio(uiState.selectedAudioSource, uiState.isNoiseFilterEnabled)
    }

    private fun seekPlayback(progress: Float) {
        val trackDuration = audioPlayer.getCurrentTrackDuration()
        audioPlayer.seekTo((progress * trackDuration).toLong())
        _uiState.update {
            val newPosition = (progress * it.playbackState.totalDurationMs).toLong()
            it.copy(playbackState = it.playbackState.copy(currentPositionMs = newPosition))
        }
    }

    private fun seekForward() {
        val trackPosition = audioPlayer.getCurrentTrackPosition() + DEFAULT_SEEK_OFFSET_MS
        audioPlayer.seekTo(trackPosition)
    }

    private fun seekBackward() {
        val trackPosition = audioPlayer.getCurrentTrackPosition() - DEFAULT_SEEK_OFFSET_MS
        audioPlayer.seekTo(trackPosition)
    }

    private fun toggleNoiseFilter(enable: Boolean) = _uiState.update { uiState ->
        if (audioPlayer.isPlaying()) {
            audioPlayer.start(getMediaSourceId(uiState.selectedAudioSource, enable), resetPosition = false)
        }

        Log.d(TAG, "Noise Canceling toggled to: $enable")

        uiState.copy(isNoiseFilterEnabled = enable)
    }

    private fun processAudioSources(attenuationLevel: Float) {
        processAudioSourcesJob?.cancel()
        processAudioSourcesJob = viewModelScope.launch {
            // 停止当前播放，但不隐藏音频列表
            if (_uiState.value.playbackState.isPlaying) {
                audioPlayer.pause()
            }
            _uiState.update { it.copy(
                playbackState = it.playbackState.copy(isPlaying = false))
            }
            audioPlayer.clearMediaSources()
            val application = getApplication<Application>()
            val deferredAirplaneBuffer =
                async { audioVariantGenerator.generateVariants(application,
                    R.raw.airplane, attenuationLevel) }
            val deferredCrowdBuffer =
                async { audioVariantGenerator.generateVariants(application,
                    R.raw.crowd, attenuationLevel) }
            val deferredRestaurantBuffer =
                async { audioVariantGenerator.generateVariants(application,
                    R.raw.restaurant, attenuationLevel) }
            val deferredClientAudioBuffer =
                async { audioVariantGenerator.generateVariants(application,
                    R.raw.client_audio_2, attenuationLevel) }
            val deferredClientAudioBuffer2 =
                async { audioVariantGenerator.generateVariants(application,
                    R.raw.jb_20260303152443_with_bug_16000hz_16bit_1ch, attenuationLevel) }
                    
            val (airplaneAudioBuffer, crowdAudioBuffer, restaurantAudioBuffer, clientAudioBuffer, clientAudioBuffer2) = awaitAll(
                deferredAirplaneBuffer,
                deferredCrowdBuffer,
                deferredRestaurantBuffer,
                deferredClientAudioBuffer
            )
            airplaneAudioBuffer?.let { dualBuffer ->
                audioPlayer.addMediaSource(getMediaSourceId(NoisyAudioSource.Airplane, false), dualBuffer.noisyBuffer.array())
                audioPlayer.addMediaSource(getMediaSourceId(NoisyAudioSource.Airplane, true), dualBuffer.filteredBuffer.array())
            }
            crowdAudioBuffer?.let { dualBuffer ->
                audioPlayer.addMediaSource(getMediaSourceId(NoisyAudioSource.Crowd, false), dualBuffer.noisyBuffer.array())
                audioPlayer.addMediaSource(getMediaSourceId(NoisyAudioSource.Crowd, true), dualBuffer.filteredBuffer.array())
            }
            restaurantAudioBuffer?.let { dualBuffer ->
                audioPlayer.addMediaSource(getMediaSourceId(NoisyAudioSource.Restaurant, false), dualBuffer.noisyBuffer.array())
                audioPlayer.addMediaSource(getMediaSourceId(NoisyAudioSource.Restaurant, true), dualBuffer.filteredBuffer.array())
            }
            clientAudioBuffer?.let { dualBuffer ->
                audioPlayer.addMediaSource(getMediaSourceId(NoisyAudioSource.ClientAudio, false), dualBuffer.noisyBuffer.array())
                audioPlayer.addMediaSource(getMediaSourceId(NoisyAudioSource.ClientAudio, true), dualBuffer.filteredBuffer.array())
            }
            _uiState.update { it.copy(isAudioReady = true) }
        }
    }

    private fun pauseAudio() {
        audioPlayer.pause()
        _uiState.update { it.copy(playbackState = it.playbackState.copy(isPlaying = false)) }
        Log.d(TAG, "Paused audio.")
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
        audioVariantGenerator.clearCache()
        Log.d(TAG, "AudioViewModel onCleared: AudioPlayer resources released.")
    }

    private fun getMediaSourceId(source: NoisyAudioSource, isNoiseFilterEnabled: Boolean): String {
        return "${source.name}_${if (isNoiseFilterEnabled) "filtered" else "noisy"}"
    }
}