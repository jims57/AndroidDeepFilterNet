package com.kaleyra.androiddeepfilternet

import android.content.Context
import android.util.Log
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
        // The required sample size for processing individual frames with DeepFilterNet.
        // This is a fixed size dictated by the DeepFilterNet model's architecture.
        private const val DEEP_FILTER_NET_REQUIRED_SAMPLE_SIZE = 960

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

            // Chunk the input audio data into segments required by DeepFilterNet.
            val rawAudioChunks = audioChunker.chunk(sourceAudio, DEEP_FILTER_NET_REQUIRED_SAMPLE_SIZE)

            // Calculate the total size needed for the output audio buffer.
            val outputBufferSize = rawAudioChunks.size * DEEP_FILTER_NET_REQUIRED_SAMPLE_SIZE
            // Allocate a ByteBuffer to store the combined filtered audio data.
            val outputAudioBuffer = ByteBuffer.allocate(outputBufferSize)
            outputAudioBuffer.order(ByteOrder.LITTLE_ENDIAN) // Set byte order

            // Allocates a new direct ByteBuffer for each call. This ensures thread-safety
            // for `frameProcessingBuffer` as each concurrent filter operation gets its own isolated buffer.
            //
            // If `filter` is called extremely frequently, repeatedly allocating direct ByteBuffers
            // can lead to performance overhead from allocation/deallocation and may exhaust the
            // limited direct memory pool. In such high-throughput scenarios, consider optimizing by:
            // 1. Reusing a single `frameProcessingBuffer` at the class level and externally
            //    serializing calls to `filter` on this instance (e.g., using a Mutex).
            // 2. Implementing a custom pool of direct ByteBuffers for reuse.
            val frameProcessingBuffer = ByteBuffer.allocateDirect(DEEP_FILTER_NET_REQUIRED_SAMPLE_SIZE).apply {
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
                    currentDeepFilterNet.processFrame(frameProcessingBuffer)
                }

                frameProcessingBuffer.rewind() // Reset buffer's position to the beginning to read processed data.

                outputAudioBuffer.put(frameProcessingBuffer) // Append the processed frame to the output buffer.

                coroutineContext.ensureActive() // Check the coroutines is still active
            }

            outputAudioBuffer.flip() // Prepare the output buffer for reading (e.g., by an audio player).
            Log.d(TAG, "Audio loaded and processed successfully. Ready for playback.")
            outputAudioBuffer
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
}