package com.kaleyra.androiddeepfilternet

import java.nio.ByteBuffer
import java.nio.ByteOrder

object ByteBufferConverter {
    fun convert(rawAudioData: ByteArray): ByteBuffer {
        val byteBuffer = ByteBuffer.allocate(rawAudioData.size)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        byteBuffer.put(rawAudioData)
        byteBuffer.flip()
        return byteBuffer
    }
}
