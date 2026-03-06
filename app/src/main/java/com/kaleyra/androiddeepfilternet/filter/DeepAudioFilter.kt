package com.kaleyra.androiddeepfilternet.filter

import android.content.Context
import android.util.Log
import com.kaleyra.androiddeepfilternet.utils.DefaultFixedSizeAudioChunker
import com.kaleyra.androiddeepfilternet.utils.FixedSizeAudioChunker
import com.kaleyra.noise_filter.DeepFilterNet
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Defines the contract for an audio filtering component that uses a deep learning model.
 * Implementations of this interface are responsible for taking raw audio data and
 * applying a filtering process to it to reduce noise or enhance quality.
 */
interface DeepAudioFilter {

    /**
     * Filters the given raw audio data using the DeepFilterNet model.
     * The audio data is chunked into required frames, processed frame by frame,
     * and then reassembled into a single output ByteBuffer.
     *
     * @param sourceAudio The raw audio data (ByteArray) to be filtered.
     * @param attenuationLimit A Float value representing the noise attenuation (reduction)
     *        applied to the audio signal. The minimum value is 0f and the maximum value is 100f.
     * @return A [ByteBuffer] containing the filtered audio data, or `null` if an error occurs
     * during processing.
     */
    suspend fun filter(sourceAudio: ByteArray, attenuationLimit: Float): ByteBuffer?

    /**
     * 使用预加载的DeepFilterNet模型实例进行降噪处理（不会创建/释放模型，速度更快）
     *
     * @param model 预加载的DeepFilterNet模型实例
     * @param sourceAudio 原始音频数据
     * @param attenuationLimit 衰减级别 0f-100f
     * @return 降噪后的音频数据，失败返回null
     */
    suspend fun filterWithModel(model: DeepFilterNet, sourceAudio: ByteArray, attenuationLimit: Float): ByteBuffer?
}

/**
 * Default implementation of [DeepAudioFilter] that uses a native DeepFilterNet
 * model to process and filter audio data.
 */
class DefaultDeepAudioFilter(
    private val deepFilterNetLoader: DeepFilterNetLoader,
    private val audioChunker: FixedSizeAudioChunker = DefaultFixedSizeAudioChunker(),
) : DeepAudioFilter {

    /**
     * Secondary constructor providing a convenient way to instantiate the filter
     * using an Android [Context] for native model loading.
     * It delegates to the primary constructor, providing a [NativeDeepFilterNetLoader].
     *
     * @param context An Android Context, typically `applicationContext`, used for native model initialization.
     */
    constructor(context: Context) : this(NativeDeepFilterNetLoader(context))

    companion object {
        private const val TAG = "DefaultDeepAudioFilter"
    }

    override suspend fun filter(sourceAudio: ByteArray, attenuationLimit: Float): ByteBuffer? {
        var currentDeepFilterNet: DeepFilterNet? = null
        return try {
            // Load a new DeepFilterNet instance for each filtering operation.
            // This simplifies resource management by ensuring each filter call has its own isolated instance,
            // which is then released immediately after use in the 'finally' block.
            // As an improvement, you may consider reusing the same DeepFilterNet
            // instance across multiple calls to 'filter'.
            currentDeepFilterNet = deepFilterNetLoader.loadDeepFilterNet()
            currentDeepFilterNet.setAttenuationLimit(attenuationLimit)

            processAudioFrames(currentDeepFilterNet, sourceAudio)
        } catch (e: Exception) {
            coroutineContext.ensureActive() // Re-throw the CancellationException if the coroutine was cancelled.
            Log.e(TAG, "Error during audio processing with DeepFilterNet: ${e.message}", e)
            null // Return null to indicate a processing failure.
        } finally {
            // Switches to the NonCancellable context to ensure that the release operation
            // completes even if the parent coroutine is cancelled. This prevents resource leaks.
            withContext(NonCancellable) {
                currentDeepFilterNet?.release()
            }
        }
    }

    override suspend fun filterWithModel(model: DeepFilterNet, sourceAudio: ByteArray, attenuationLimit: Float): ByteBuffer? {
        return try {
            model.setAttenuationLimit(attenuationLimit)
            processAudioFrames(model, sourceAudio)
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            Log.e(TAG, "Error during audio processing with pre-loaded model: ${e.message}", e)
            null
        }
        // 注意：不释放model，由调用方管理生命周期
    }

    /**
     * 核心音频帧处理逻辑（公共方法，被filter和filterWithModel共用）
     */
    private suspend fun processAudioFrames(deepFilterNet: DeepFilterNet, sourceAudio: ByteArray): ByteBuffer {
        // The required frame length for processing with DeepFilterNet.
        val frameLength = deepFilterNet.frameLength.toInt()
        // Chunk the input audio data into segments required by DeepFilterNet.
        val rawAudioChunks = audioChunker.chunk(sourceAudio, frameLength)

        // Calculate the total size needed for the output audio buffer.
        val outputBufferSize = rawAudioChunks.size * frameLength
        // Allocate a ByteBuffer to store the combined filtered audio data.
        val outputAudioBuffer = ByteBuffer.allocate(outputBufferSize)
        outputAudioBuffer.order(ByteOrder.LITTLE_ENDIAN) // Set byte order

        // Allocates a new direct ByteBuffer for each call. This ensures thread-safety
        // for `frameProcessingBuffer` as each concurrent filter operation gets its own isolated buffer.
        val frameProcessingBuffer = ByteBuffer.allocateDirect(frameLength).apply {
            order(ByteOrder.LITTLE_ENDIAN) // Set byte order to match DeepFilterNet's expectation
        }

        // Iterate over each chunk of raw audio data.
        for ((i, chunk) in rawAudioChunks.withIndex()) {
            frameProcessingBuffer.clear() // Reset buffer's position and limit for new data.
            frameProcessingBuffer.put(chunk) // Put the current audio chunk into the buffer.
            frameProcessingBuffer.flip() // Prepare the buffer for reading by native code (DeepFilterNet).

            // Skip processing the initial chunk, as it likely contains file header information (e.g., WAV header).
            // This is done for simplicity.
            if (i != 0) {
                // Process the audio frame using the DeepFilterNet model.
                // The 'processFrame' method modifies the buffer in-place.
                deepFilterNet.processFrame(frameProcessingBuffer)
            }

            frameProcessingBuffer.rewind() // Reset buffer's position to the beginning to read processed data.

            outputAudioBuffer.put(frameProcessingBuffer) // Append the processed frame to the output buffer.

            coroutineContext.ensureActive() // Check the coroutines is still active
        }

        outputAudioBuffer.flip() // Prepare the output buffer for reading (e.g., by an audio player).
        Log.d(TAG, "Audio loaded and processed successfully. Ready for playback.")
        return outputAudioBuffer
    }
}