/* Copyright 2026 Tusky Contributors. GPL-3.0-or-later. */
package com.keylesspalace.tusky.components.compose.alttext

import android.content.Context
import android.content.SharedPreferences
import com.keylesspalace.tusky.settings.ALT_TEXT_DEFAULT_MAX_TOKENS
import com.keylesspalace.tusky.settings.PrefKeys
import com.squareup.moshi.Moshi
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class AltTextGeneratorTest {
    private lateinit var server: MockWebServer
    private lateinit var prefs: SharedPreferences
    private lateinit var generator: AltTextGenerator
    private val moshi = Moshi.Builder().build()
    private val client = OkHttpClient.Builder().build()
    private val sampleJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        prefs = mock()
        whenever(prefs.getString(PrefKeys.ALT_TEXT_API_KEY, "")).thenReturn("test-key")
        whenever(prefs.getString(PrefKeys.ALT_TEXT_BASE_URL, ""))
            .thenReturn(server.url("/v1").toString())
        whenever(prefs.getString(PrefKeys.ALT_TEXT_MODEL, "")).thenReturn("test/model")
        whenever(prefs.getString(PrefKeys.ALT_TEXT_PROMPT, "")).thenReturn("Test prompt")
        whenever(prefs.getString(PrefKeys.ALT_TEXT_MAX_TOKENS, ""))
            .thenReturn(ALT_TEXT_DEFAULT_MAX_TOKENS.toString())
        generator = AltTextGenerator(mock<Context>(), prefs, client, moshi)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `request includes auth header, model, prompt, and base64 data uri`() = runTest {
        server.enqueue(MockResponse(body = """{"choices":[{"message":{"content":"a cat"}}]}"""))

        val result = generator.generateFromBytes(sampleJpeg)
        assertTrue(result.isSuccess)
        assertEquals("a cat", result.getOrNull())

        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.url.encodedPath)
        assertEquals("Bearer test-key", req.headers["Authorization"])
        val body = req.body!!.utf8()
        assertTrue(body.contains("\"model\":\"test/model\""))
        assertTrue(body.contains("\"max_tokens\":$ALT_TEXT_DEFAULT_MAX_TOKENS"))
        assertTrue(body.contains("Test prompt"))
        assertTrue(body.contains("data:image/jpeg;base64,"))
    }

    @Test
    fun `parses content when returned as array of parts`() = runTest {
        server.enqueue(
            MockResponse(
                body = """{"choices":[{"message":{"content":[
                    {"type":"text","text":"hello "},
                    {"type":"text","text":"world"}
                ]}}]}"""
            )
        )

        val result = generator.generateFromBytes(sampleJpeg)
        assertEquals("hello world", result.getOrNull())
    }

    @Test
    fun `surfaces error message from openrouter style error body`() = runTest {
        server.enqueue(
            MockResponse(code = 402, body = """{"error":{"message":"Out of credits"}}""")
        )

        val result = generator.generateFromBytes(sampleJpeg)
        assertTrue(result.isFailure)
        assertEquals("Out of credits", result.exceptionOrNull()?.message)
    }

    @Test
    fun `falls back to generic server error when no parseable body`() = runTest {
        server.enqueue(MockResponse(code = 500, body = "internal explosion"))

        val result = generator.generateFromBytes(sampleJpeg)
        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
        assertTrue(result.exceptionOrNull() is AltTextGenerator.ServerHttpException)
        assertEquals(500, (result.exceptionOrNull() as AltTextGenerator.ServerHttpException).code)
    }

    @Test
    fun `empty content produces failure`() = runTest {
        server.enqueue(MockResponse(body = """{"choices":[{"message":{"content":""}}]}"""))

        val result = generator.generateFromBytes(sampleJpeg)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AltTextGenerator.EmptyResponseException)
    }

    @Test
    fun `base url with trailing slash does not produce double slash`() = runTest {
        whenever(prefs.getString(PrefKeys.ALT_TEXT_BASE_URL, ""))
            .thenReturn(server.url("/v1/").toString())
        server.enqueue(MockResponse(body = """{"choices":[{"message":{"content":"ok"}}]}"""))

        generator.generateFromBytes(sampleJpeg)
        assertEquals("/v1/chat/completions", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `isConfigured is false when api key blank`() {
        whenever(prefs.getString(PrefKeys.ALT_TEXT_API_KEY, "")).thenReturn("")
        assertEquals(false, generator.isConfigured())
    }

    @Test
    fun `isConfigured is false when model blank`() {
        whenever(prefs.getString(PrefKeys.ALT_TEXT_MODEL, "")).thenReturn("")
        assertEquals(false, generator.isConfigured())
    }

    @Test
    fun `isConfigured is true when both present`() {
        assertEquals(true, generator.isConfigured())
    }
}
