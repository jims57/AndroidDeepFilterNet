package com.kaleyra.androiddeepfilternet

import android.content.Context
import android.content.res.Resources
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

interface RawResourceLoader {
    suspend fun loadRawData(context: Context, resourceId: Int): ByteArray?
}

class AndroidRawResourceLoader : RawResourceLoader {

    private val TAG = "AndroidRawResourceLoader"
    private val READ_BUFFER_SIZE = 4096 // 4 KB

    override suspend fun loadRawData(context: Context, resourceId: Int): ByteArray? = withContext(Dispatchers.IO) {
        var inputStream: InputStream? = null
        try {
            inputStream = context.resources.openRawResource(resourceId)

            val outputStream = ByteArrayOutputStream()
            val dataBuffer = ByteArray(READ_BUFFER_SIZE)
            var bytesRead: Int

            while (inputStream.read(dataBuffer).also { bytesRead = it } != -1 && coroutineContext.isActive) {
                outputStream.write(dataBuffer, 0, bytesRead)
            }

            return@withContext outputStream.toByteArray()
        } catch (e: Resources.NotFoundException) {
            Log.e(TAG, "Resource not found for ID: $resourceId. Error: ${e.message}", e)
            return@withContext null
        } catch (e: IOException) {
            Log.e(TAG, "I/O error while reading resource with ID: $resourceId. Error: ${e.message}", e)
            return@withContext null
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            Log.e(TAG, "An unexpected error occurred while loading resource with ID: $resourceId. Error: ${e.message}", e)
            return@withContext null
        } finally {
            withContext(NonCancellable) {
                inputStream?.close()
            }
        }
    }
}
