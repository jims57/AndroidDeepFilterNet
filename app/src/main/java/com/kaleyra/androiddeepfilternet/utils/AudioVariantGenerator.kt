package com.kaleyra.androiddeepfilternet.utils

import android.content.Context
import android.util.Log
import com.kaleyra.androiddeepfilternet.filter.DeepAudioFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

data class DualAudioBuffer(
    val noisyBuffer: ByteBuffer,
    val filteredBuffer: ByteBuffer
)

class AudioVariantGenerator(
    private val deepAudioFilter: DeepAudioFilter,
    private val rawResourceLoader: RawResourceLoader = AndroidRawResourceLoader()
) {
    companion object {
        private const val TAG = "AudioVariantGenerator"
    }

    private val rawAudioDataCache = ConcurrentHashMap<Int, ByteArray>()

    private val loadingMutex = Mutex()

    suspend fun generateVariants(
        context: Context,
        audioResId: Int,
        noiseAttenuationLevel: Float
    ): DualAudioBuffer? = withContext(Dispatchers.IO) {
        var rawAudioData: ByteArray? = null

        rawAudioDataCache[audioResId]?.let { cachedData ->
            Log.d(TAG, "Returning cached raw audio data for resource ID: $audioResId")
            rawAudioData = cachedData
        }

        if (rawAudioData == null) {
            loadingMutex.withLock {
                rawAudioDataCache[audioResId]?.let { cachedData ->
                    Log.d(TAG, "Returning cached raw audio data (double check) for resource ID: $audioResId")
                    rawAudioData = cachedData
                } ?: run {
                    Log.d(TAG, "Loading raw audio data for resource ID: $audioResId...")
                    rawAudioData = rawResourceLoader.loadRawData(context, audioResId)
                    if (rawAudioData == null) {
                        Log.e(TAG, "Failed to load raw audio data for resource ID: $audioResId")
                        return@withContext null
                    }
                    rawAudioDataCache[audioResId] = rawAudioData!!
                    Log.d(TAG, "Raw audio data loaded and cached for resource ID: $audioResId")
                }
            }
        }

        Log.d(TAG, "Processing audio data for resource ID: $audioResId...")

        val noisyAudioBuffer = ByteBufferConverter.convert(rawAudioData!!)
        val filteredAudioBuffer = deepAudioFilter.filter(rawAudioData!!, noiseAttenuationLevel)

        if (filteredAudioBuffer == null) {
            Log.e(TAG, "Denoising audio processing failed for resource ID: $audioResId")
            return@withContext null
        }

        Log.d(TAG, "Audio successfully processed into noisy and filtered buffers for ID: $audioResId.")
        return@withContext DualAudioBuffer(noisyAudioBuffer, filteredAudioBuffer)
    }


    fun clearCache() {
        rawAudioDataCache.clear()
        Log.d(TAG, "Cleared all cached raw audio data.")
    }
}