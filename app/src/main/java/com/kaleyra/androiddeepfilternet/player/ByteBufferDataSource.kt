package com.kaleyra.androiddeepfilternet.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

@UnstableApi
class ByteBufferDataSourceFactory(private val data: ByteArray) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return ByteBufferDataSource(data.copyOf())
    }
}

@UnstableApi
class ByteBufferDataSource(private val data: ByteArray) : DataSource {
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0
    private var opened = false
    private var currentReadPosition: Int = 0

    override fun open(dataSpec: DataSpec): Long {
        if (opened) {
            // It's possible ExoPlayer calls open twice on the same DataSource in very specific scenarios
            // or if it internally manages a pool. A robust DataSource should handle this or log it.
            Log.w("ByteBufferDataSource", "DataSource already opened, closing and re-opening.")
            close() // Close gracefully before re-opening
        }

        try {
            uri = dataSpec.uri
            // Ensure dataSpec.position is within bounds of the actual data array
            val requestedStartPosition = dataSpec.position.toInt()
            currentReadPosition = requestedStartPosition.coerceIn(0, data.size)

            bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                (data.size - currentReadPosition).toLong()
            } else {
                // Ensure requested length doesn't go beyond actual data size
                minOf(dataSpec.length, (data.size - currentReadPosition).toLong())
            }

            if (bytesRemaining < 0) { // Should not happen if coercIn and minOf are correct, but defensive
                bytesRemaining = 0
            }

            opened = true
            return bytesRemaining
        } catch (e: Exception) {
            Log.e("ByteBufferDataSource", "Error opening ByteBufferDataSource: ${e.message}", e)
            throw IOException(e) // Re-throw as IOException for ExoPlayer to handle
        }
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (!opened) {
            throw IOException("DataSource not opened for reading.")
        }

        if (readLength == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        try {
            // Calculate actual bytes to read, clamping by available data, requested length, and dest buffer space
            val bytesToRead = minOf(
                readLength.toLong(),
                bytesRemaining,
                (buffer.size - offset).toLong()
            ).toInt()

            // Essential bounds check *before* System.arraycopy
            if (currentReadPosition < 0 ||
                currentReadPosition >= data.size ||
                (currentReadPosition + bytesToRead) > data.size ||
                (offset + bytesToRead) > buffer.size
            ) {
                Log.e("ByteBufferDataSource",
                    "Read out of bounds attempt: " +
                            "currentReadPosition=$currentReadPosition, " +
                            "bytesToRead=$bytesToRead, " +
                            "data.size=${data.size}, " +
                            "offset=$offset, " +
                            "buffer.size=${buffer.size}"
                )
                // This means there's a logic error, or remaining bytes count is wrong.
                // Indicate end of input rather than crashing.
                return C.RESULT_END_OF_INPUT
            }

            System.arraycopy(data, currentReadPosition, buffer, offset, bytesToRead)
            currentReadPosition += bytesToRead
            bytesRemaining -= bytesToRead
            return bytesToRead
        } catch (e: IndexOutOfBoundsException) { // Catch more specific arraycopy errors
            Log.e("ByteBufferDataSource", "ArrayIndexOutOfBoundsException in read: ${e.message}", e)
            throw IOException("Read failed due to array bounds: ${e.message}", e)
        } catch (e: Exception) {
            Log.e("ByteBufferDataSource", "Generic error in read: ${e.message}", e)
            throw IOException("Read failed: ${e.message}", e)
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        opened = false
        currentReadPosition = 0
        bytesRemaining = 0
    }

    override fun addTransferListener(transferListener: TransferListener) = Unit
}