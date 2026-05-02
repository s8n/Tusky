/* Copyright 2026 Tusky Contributors. GPL-3.0-or-later. */
package com.keylesspalace.tusky.components.compose.alttext

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.keylesspalace.tusky.settings.ALT_TEXT_DEFAULT_BASE_URL
import com.keylesspalace.tusky.settings.ALT_TEXT_DEFAULT_MAX_TOKENS
import com.keylesspalace.tusky.settings.ALT_TEXT_DEFAULT_MODEL
import com.keylesspalace.tusky.settings.ALT_TEXT_DEFAULT_PROMPT
import com.keylesspalace.tusky.settings.PrefKeys
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

@Singleton
class AltTextGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: SharedPreferences,
    private val httpClient: OkHttpClient,
    private val moshi: Moshi,
) {
    fun isConfigured(): Boolean {
        val key = prefs.getString(PrefKeys.ALT_TEXT_API_KEY, "").orEmpty()
        val model = prefs.getString(PrefKeys.ALT_TEXT_MODEL, "").orEmpty()
        return key.isNotBlank() && model.isNotBlank()
    }

    suspend fun generate(imageUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val bytes = loadAndEncode(imageUri)
            generateFromBytes(bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(ImageLoadException(e))
        }
    }

    @androidx.annotation.VisibleForTesting
    internal suspend fun generateFromBytes(jpegBytes: ByteArray): Result<String> {
        val key = prefs.getString(PrefKeys.ALT_TEXT_API_KEY, "").orEmpty()
        val model = prefs.getString(PrefKeys.ALT_TEXT_MODEL, "").orEmpty()
        val baseUrl = prefs.getString(PrefKeys.ALT_TEXT_BASE_URL, "")
            .orEmpty().ifBlank { ALT_TEXT_DEFAULT_BASE_URL }
        val prompt = prefs.getString(PrefKeys.ALT_TEXT_PROMPT, "")
            .orEmpty().ifBlank { ALT_TEXT_DEFAULT_PROMPT }
        val maxTokens = prefs.getString(PrefKeys.ALT_TEXT_MAX_TOKENS, "")
            ?.toIntOrNull() ?: ALT_TEXT_DEFAULT_MAX_TOKENS

        if (key.isBlank() || model.isBlank()) {
            return Result.failure(NotConfiguredException())
        }

        val dataUri = "data:image/jpeg;base64," +
            Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val request = ChatCompletionRequest(
            model = model,
            maxTokens = maxTokens,
            messages = listOf(
                RequestMessage(
                    role = "user",
                    content = listOf(
                        ContentPart(type = "text", text = prompt),
                        ContentPart(type = "image_url", imageUrl = ImageUrl(dataUri))
                    )
                )
            )
        )
        val requestJson = moshi.adapter(ChatCompletionRequest::class.java).toJson(request)
        val client = httpClient.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
        val httpRequest = Request.Builder()
            .url(joinUrl(baseUrl, "chat/completions"))
            .header("Authorization", "Bearer $key")
            .post(requestJson.toRequestBody(JSON))
            .build()

        return try {
            val response = client.newCall(httpRequest).await()
            response.use { handleResponse(it) }
        } catch (e: IOException) {
            Result.failure(NetworkException(e))
        }
    }

    private fun handleResponse(response: Response): Result<String> {
        val bodyString = response.body?.string().orEmpty()
        val parsed = runCatching {
            moshi.adapter(ChatCompletionResponse::class.java).fromJson(bodyString)
        }.getOrNull()
        if (!response.isSuccessful) {
            val errorMessage = parsed?.error?.message
            return if (!errorMessage.isNullOrBlank()) {
                Result.failure(UpstreamErrorException(errorMessage))
            } else {
                Result.failure(ServerHttpException(response.code))
            }
        }
        val message = parsed?.choices?.firstOrNull()?.message
            ?: return Result.failure(EmptyResponseException())
        val text = extractText(message.content).trim().stripWrappingQuotes()
        return if (text.isEmpty()) {
            Result.failure(EmptyResponseException())
        } else {
            Result.success(text)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractText(content: Any?): String = when (content) {
        is String -> content
        is List<*> -> {
            // `[{"type":"text","text":"..."}, ...]` shape
            val listAdapter = moshi.adapter<List<Map<String, Any>>>(
                Types.newParameterizedType(
                    List::class.java,
                    Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
                )
            )
            val asList = listAdapter.fromJsonValue(content) ?: emptyList()
            asList.mapNotNull { it["text"] as? String }.joinToString("")
        }
        else -> ""
    }

    private fun loadAndEncode(uri: Uri): ByteArray {
        // Don't recycle the bitmap returned by Glide - it may still live in Glide's memory cache
        // and recycling it makes subsequent loads of the same Uri return a recycled bitmap.
        val bitmap: Bitmap = Glide.with(context)
            .asBitmap()
            .load(uri)
            .downsample(DownsampleStrategy.AT_MOST)
            .submit(MAX_SIDE, MAX_SIDE)
            .get()
        val resized = resizeIfNeeded(bitmap)
        return try {
            val output = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            output.toByteArray()
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
    }

    private fun resizeIfNeeded(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_SIDE) return bitmap
        val scale = MAX_SIDE.toFloat() / longest.toFloat()
        val newW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    class NotConfiguredException : Exception()
    class ImageLoadException(cause: Throwable) : Exception(cause)
    class NetworkException(cause: Throwable) : Exception(cause)
    class ServerHttpException(val code: Int) : Exception("HTTP $code")
    class UpstreamErrorException(message: String) : Exception(message)
    class EmptyResponseException : Exception()

    companion object {
        private const val MAX_SIDE = 1024
        private const val JPEG_QUALITY = 85
        private val JSON = "application/json; charset=utf-8".toMediaType()

        internal fun joinUrl(base: String, path: String): String {
            val trimmed = base.trimEnd('/')
            val cleanPath = path.trimStart('/')
            return "$trimmed/$cleanPath"
        }
    }
}

private fun String.stripWrappingQuotes(): String =
    if (length >= 2 && startsWith('"') && endsWith('"')) substring(1, length - 1) else this

private suspend fun Call.await(): Response =
    suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }
