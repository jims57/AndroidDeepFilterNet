package com.kaleyra.androiddeepfilternet.utils

/**
 * Interface defining the contract for chunking a ByteArray into fixed-size smaller ByteArrays.
 * This is useful for processing large audio files in manageable segments.
 */
interface FixedSizeAudioChunker {
    /**
     * Chunks an original ByteArray into a list of ByteArrays, each of a specified chunk size.
     *
     * @param originalByteArray The ByteArray to be chunked.
     * @param chunkSize The desired size of each chunk. Must be greater than 0.
     * @return A [List] of [ByteArray]s, where each ByteArray is a chunk of the original.
     */
    fun chunk(originalByteArray: ByteArray, chunkSize: Int): List<ByteArray>
}

/**
 * Default implementation of [FixedSizeAudioChunker].
 * It divides the input ByteArray into segments of the specified chunk size.
 */
class DefaultFixedSizeAudioChunker : FixedSizeAudioChunker {
    override fun chunk(originalByteArray: ByteArray, chunkSize: Int): List<ByteArray> {
        require(chunkSize > 0) { "Chunk size must be greater than 0" }

        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        val originalSize = originalByteArray.size

        while (offset < originalSize) {
            val endIndex = (offset + chunkSize).coerceAtMost(originalSize)
            val chunk = originalByteArray.copyOfRange(offset, endIndex)
            chunks.add(chunk)
            offset = endIndex
        }
        return chunks
    }
}