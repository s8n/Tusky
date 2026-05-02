# VLM-Generated Alt Text — Design

## Goal

Add a "Generate alt text" button to the alt-text input modal in Tusky's compose screen. The button calls a configurable OpenAI-compatible chat-completions endpoint (default OpenRouter) with the local image, resized to 1024 px on the longest side and re-encoded as JPEG, and replaces the description field with the model's reply.

The feature is configured via a new sub-screen in Preferences. The defaults match the recommendations in [the ave.zone blog post](https://ave.zone/blog/playing-with-vlm-generated-alt-text).

## User flow

1. User attaches an image in compose, taps the description pencil. `CaptionDialog` opens.
2. If the attachment is an image and the API key + model are both set, the AlertDialog gains a neutral button "Generate alt text" sitting to the left of Cancel/OK.
3. User taps the neutral button. The neutral button is hidden, an indeterminate spinner overlays the image preview, the OK button is disabled, and the dialog's Cancel button switches role to "Cancel generation".
4. The image is loaded, resized, JPEG-encoded, base64-encoded, and POSTed to the configured endpoint. On success, the description field's text is replaced with the model's reply (no confirmation, no append). On failure, a Snackbar surfaces the error and the dialog state is restored. On cancellation, the dialog state is restored silently.

When the API key or model is unset, or the attachment is not an image, the neutral button is hidden — the feature is invisible until configured. Discovery happens via the new Settings entry.

## Project conventions to know

These are non-obvious facts about the Tusky codebase that the implementation depends on. They are derivable from `app/build.gradle` and friends but are easy to miss when porting to a future version:

- **Default product flavor is `green`** (`app/build.gradle` declares `green { isDefault true }`). Build/test/install with `:app:compileGreenDebugKotlin`, `:app:testGreenDebugUnitTest`, `:app:installGreenDebug`. `blue` is the signed Play release flavor.
- **JDK 21 is required** (`build.gradle` toolchain `JavaLanguageVersion.of(21)`). Toolchain auto-download is not configured — `JAVA_HOME` must point at a JDK 21 install before any gradle invocation.
- **Moshi runs in KSP-codegen-only mode.** `app/build.gradle` has `ksp libs.moshi.kotlin.codegen` and `NetworkModule.providesMoshi` does NOT register `KotlinJsonAdapterFactory`. Every `data class` that crosses Moshi MUST be annotated `@JsonClass(generateAdapter = true)`, otherwise `Cannot serialize Kotlin type ...` at runtime.
- **Tests use `mockwebserver3` (v3.x), not the legacy package.** Notable API differences: `RecordedRequest.url.encodedPath` (no `path` field), `RecordedRequest.body` is `ByteString?` (nullable).
- **Singleton OkHttpClient is safe to reuse across hosts.** `apiForAccount` adds the Mastodon `Authorization` header per-call only when the request URL host matches the active account's domain — there is no cross-host header bleed.
- **Singleton OkHttpClient has 30 s read/write timeouts.** Too short for VLM inference, which can run 30–60 s. Derive a longer-timeout client per call via `httpClient.newBuilder().readTimeout(...).writeTimeout(...).build()` (precedent: `MediaUploadApi`).

## Settings

A new top-level Preferences entry "Alt text generation" opens `AltTextPreferencesFragment`. Layout mirrors `ProxyPreferencesFragment`.

| Pref key (in `PrefKeys`) | Type | Default | Notes |
|---|---|---|---|
| `ALT_TEXT_API_KEY` | string | `""` | `EditTextPreference` with `inputType=textPassword` |
| `ALT_TEXT_BASE_URL` | string | `"https://openrouter.ai/api/v1"` | Trailing slash tolerated |
| `ALT_TEXT_MODEL` | string | `"qwen/qwen3.5-122b-a10b"` | OpenRouter / OpenAI-compatible model id |
| `ALT_TEXT_PROMPT` | string | (see below) | Multi-line input |
| `ALT_TEXT_MAX_TOKENS` | int (stored as string) | `1024` | Validated on commit, falls back to `1024` if blank/invalid |

The defaults live as top-level `const val ALT_TEXT_DEFAULT_*` constants in `SettingsConstants.kt` so that both `AltTextGenerator` and `AltTextPreferencesFragment` reference a single source of truth.

Default prompt:

> Write alt text for this image. Be concise — 1-2 sentences for simple images. If the image contains readable text, transcribe it rather than describing it. Only describe what you can clearly see; do not guess at names or details.

Settings are stored globally (not per-account) — they are device-level configuration, not Mastodon identity. The schema version constant does not need bumping (purely additive).

The top-level Preferences entry's `SummaryProvider` shows the configured model name, or "Not configured" when API key or model is empty.

A simple `editTextPreference { ... }` DSL helper is added to `SettingsDSL.kt` (the existing `validatedEditTextPreference` requires a validator; the new one is a plain pass-through builder).

## `AltTextGenerator` — service

New file: `app/src/main/java/com/keylesspalace/tusky/components/compose/alttext/AltTextGenerator.kt`.

```kotlin
@Singleton
class AltTextGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: SharedPreferences,
    private val httpClient: OkHttpClient,
    private val moshi: Moshi,
) {
    suspend fun generate(imageUri: Uri): Result<GenerationResult>
    fun isConfigured(): Boolean

    @VisibleForTesting
    internal suspend fun generateFromBytes(jpegBytes: ByteArray): Result<GenerationResult>

    data class GenerationResult(
        val text: String,
        val cost: Double? = null,
        val provider: String? = null,
    )

    class NotConfiguredException : Exception()
    class ImageLoadException(cause: Throwable) : Exception(cause)
    class NetworkException(cause: Throwable) : Exception(cause)
    class ServerHttpException(val code: Int) : Exception("HTTP $code")
    class UpstreamErrorException(message: String) : Exception(message)
    class EmptyResponseException : Exception()
}
```

`isConfigured()` returns true when both `ALT_TEXT_API_KEY` and `ALT_TEXT_MODEL` are non-blank.

The companion DTO file `AltTextDtos.kt` declares `internal data class` types for the OpenAI-compatible chat-completions request and response shapes. Every DTO is annotated `@JsonClass(generateAdapter = true)` (see project conventions).

### Image pipeline (`Dispatchers.IO`)

1. Load via Glide, asking the decoder to subsample during decode rather than allocating the full-resolution bitmap:
   ```kotlin
   Glide.with(context)
       .asBitmap()
       .load(uri)
       .downsample(DownsampleStrategy.AT_MOST)
       .submit(MAX_SIDE, MAX_SIDE)
       .get()
   ```
   This reuses Tusky's Glide configuration and handles content URIs and EXIF rotation. Blocking is fine inside `withContext(Dispatchers.IO)`. `AT_MOST` uses `inSampleSize` (powers of 2), preserves aspect ratio, and never upscales — so a 4032×3024 phone photo decodes as ~1008×756 instead of allocating ~50 MB.
2. If `max(width, height) > 1024` (rare, since Glide already did most of the work), refine with `Bitmap.createScaledBitmap` preserving aspect ratio. Smaller images are sent at native size (no upscaling).
3. Re-encode JPEG at quality 85 to a `ByteArrayOutputStream`; base64-encode (`Base64.NO_WRAP`).
4. **Bitmap lifecycle:** the bitmap returned by `Glide.submit().get()` is **not** recycled — it may still live in Glide's memory cache, and recycling it makes subsequent loads of the same `Uri` return a recycled bitmap (surfaced as the misleading `ImageLoadException` → "Could not load image" snackbar). Only the resized bitmap, when distinct from the Glide-owned one, is recycled — in a `finally` that wraps `compress()` so it runs even if compression throws.

### Request

`POST <baseUrl>/chat/completions` with `Authorization: Bearer <key>`. The request body is built as a `RequestBody` with media type `application/json; charset=utf-8`; do NOT also call `.header("Content-Type", "application/json")` because that masks the body's media type and drops the charset. Base URL joining strips a single trailing slash so user-entered values like `https://openrouter.ai/api/v1/` do not produce `//chat/completions`.

```json
{
  "model": "<from prefs>",
  "max_tokens": <from prefs>,
  "messages": [{
    "role": "user",
    "content": [
      { "type": "text", "text": "<prompt from prefs>" },
      { "type": "image_url", "image_url": { "url": "data:image/jpeg;base64,..." } }
    ]
  }]
}
```

Per-call client derived for the longer timeout:
```kotlin
val client = httpClient.newBuilder()
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(120, TimeUnit.SECONDS)
    .build()
```

### Response handling

Take `choices[0].message.content`. The DTO declares `content: Any?` because some providers return a plain string and others return an array of typed parts (`[{"type":"text","text":"..."}]`). The runtime extractor switches on the deserialized type — `String` is used directly; `List<*>` is parsed via Moshi's `Map<String, Any>` adapter and the `text` parts are concatenated.

Trim whitespace, then strip a wrapping pair of double quotes (only when both leading and trailing quotes are present, to avoid mangling content like `"Hello", she said`). Empty content produces a `EmptyResponseException` failure.

For non-2xx responses, parse the body as a `ChatCompletionResponse` and prefer `error.message` (OpenRouter / OpenAI style). If the body is unparseable, fall back to `ServerHttpException(response.code)`.

### Cost / provider reporting

`generate()` returns `Result<GenerationResult>`, where `GenerationResult` is `(text: String, cost: Double?, provider: String?)`. Both metadata fields are nullable because not every OpenAI-compatible server returns them — OpenRouter populates `usage.cost` (USD) and a top-level `provider` string; vanilla OpenAI does not. The DTO has `Usage(cost: Double? = null)` and `provider: String? = null` on `ChatCompletionResponse`, both with defaults so missing fields don't fail Moshi. We do not add `usage: { include: true }` to the request — it isn't needed for OpenRouter in non-streaming mode and would be a foreign field for stricter servers.

`CaptionDialog` shows a Snackbar after a successful generation if either `provider` or `cost` is non-null:

- Format: `Provider: <name> · Cost: $0.0042` (either half can be omitted; if both null, no Snackbar).
- Cost formatting matches the reference web client: `$0` when zero; otherwise `$` followed by `Locale.ROOT`-formatted decimals — 4 digits when ≥ 0.01, 5 digits when ≥ 0.001, 6 digits below. This avoids locale-dependent decimal separators (no `0,0042` in DE locale) and keeps OpenRouter's USD pricing visually unambiguous.
- The Snackbar is in addition to setting the description text — the text replacement still happens silently first.
- **Position and duration:** anchored to the **top** of the dialog (default Snackbar gravity is bottom; we cast `snackbar.view.layoutParams as? FrameLayout.LayoutParams` and set `gravity = Gravity.TOP`). Duration is a fixed 5000 ms (`SUCCESS_META_DURATION_MS`) — longer than `LENGTH_LONG` (~3.5 s) so the user has time to read the cost, but still self-dismissing.

Strings: `label_alt_text_provider` ("Provider: %1$s") and `label_alt_text_cost` ("Cost: %1$s").

### Cancellation

The HTTP call is wrapped with `suspendCancellableCoroutine`; `cont.invokeOnCancellation { runCatching { cancel() } }` calls `okhttp3.Call.cancel()`.

`generate(uri)` has a `try / catch (CancellationException) { throw e } / catch (Exception) { Result.failure(ImageLoadException(e)) }` structure: rethrow `CancellationException` so the caller's coroutine cancels cleanly; only wrap genuine load/decode failures in `ImageLoadException`. Without the explicit re-throw, `CancellationException` would be swallowed and the dialog would surface a misleading "Could not load image" snackbar on user-initiated cancel.

## UI changes

### `dialog_image_description.xml`

The image preview is wrapped in a `FrameLayout` so the spinner can overlay it without stealing layout space. The "Generate alt text" button is NOT in this layout — it lives on the AlertDialog's neutral button.

```xml
<FrameLayout android:layout_weight="1" ...>
    <com.ortiz.touchview.TouchImageView
        android:id="@+id/imageDescriptionView"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        ... />
    <ProgressBar
        android:id="@+id/generateAltTextProgress"
        style="?android:attr/progressBarStyleLarge"
        android:layout_gravity="center"
        android:visibility="gone" ... />
</FrameLayout>
```

### `CaptionDialog`

- Annotated `@AndroidEntryPoint`; `@Inject lateinit var altTextGenerator: AltTextGenerator`.
- New bundle arg `IS_IMAGE_ARG` (boolean). `newInstance(...)` gains a 5th parameter `isImage: Boolean`. (Boolean rather than MIME string — the type information already exists at the call site as `QueuedMedia.Type.IMAGE`.)
- `onCreateDialog` calls `.setNeutralButton(R.string.action_generate_alt_text, null)` on the builder. The actual click listener is attached in `onStart` so it can override the default dismiss behavior of AlertDialog buttons.
- `onStart` retrieves the neutral button. If `isImage && altTextGenerator.isConfigured()`, attach a click listener that calls `startGeneration(uri)`. Otherwise set the neutral button's visibility to `GONE`. Configured-state is checked once at dialog open; settings changes are not observed mid-compose.
- `startGeneration(uri)`:
  1. Cache references to the three buttons (positive/negative/neutral).
  2. `neutralButton.visibility = View.GONE` (hide, don't disable — keeps the button row uncluttered).
  3. Show the overlay progress bar.
  4. Disable OK.
  5. Replace Cancel button text with `@string/action_cancel_generation`; replace its click listener with a handler that calls `generationJob?.cancel()` **and** invokes `restoreUi(...)` synchronously. Doing the UI restore on the click — not relying on the coroutine's `finally` — is necessary because `finally` can be slow to run (or fail to run promptly) while the OkHttp call and Glide load wind down, leaving the dialog stuck in spinner state. `restoreUi` is idempotent, so the eventual `finally` running again is harmless.
  6. `lifecycleScope.launch { try { ... } catch (CancellationException) { /* silent */ } finally { if (isAdded) restoreUi(...) } }`.
  7. The success/failure result handling is also guarded by `isAdded` to avoid touching a detached fragment's binding.
- `restoreUi` re-shows the neutral button, hides the spinner, re-enables OK based on description length, restores the cancel button text and re-attaches the default cancel handler.
- `onDestroyView` cancels the job.
- Uses a null-safe `(dialog as? AlertDialog) ?: return` pattern when accessing the AlertDialog from the click listener path.

### `AltTextPreferencesFragment`

New file mirroring `ProxyPreferencesFragment`. Built with the existing `makePreferenceScreen { ... }` DSL. Five `editTextPreference` entries (key, base URL, model, prompt, max tokens):

- API key uses `inputType=textPassword`. Summary shows "Set" / "Not set" rather than the value itself.
- Base URL uses `inputType=textUri`. Summary shows current value.
- Model uses default text input. Summary shows current value.
- Prompt uses `inputType=textMultiLine|textCapSentences`, 3–10 lines. Summary shows the first 80 chars (with `…`) or the full value if shorter; falls back to the default prompt when blank.
- Max tokens uses `inputType=number`. On `setOnPreferenceChangeListener`, blank or non-positive values are rejected (returning `false` from the listener).

The fragment exposes a public `object SummaryProvider : Preference.SummaryProvider<Preference>` used by the parent `PreferencesFragment` to render the entry's summary.

### `PreferencesFragment` link

Add a `preferenceCategory(R.string.pref_title_alt_text_generation) { preference { ... fragment = AltTextPreferencesFragment::class.qualifiedName } }` block to the main Preferences screen, near the existing Proxy entry. The summary uses `AltTextPreferencesFragment.SummaryProvider`, which shows the model name when configured and "Not configured" otherwise.

No icon is set on the entry — matches the existing Proxy entry's style.

## Strings

All English; translatable via Weblate.

| Key | Value |
|---|---|
| `pref_title_alt_text_generation` | Alt text generation |
| `pref_summary_alt_text_not_configured` | Not configured |
| `pref_title_alt_text_api_key` | API key |
| `pref_summary_alt_text_api_key_set` | Set |
| `pref_summary_alt_text_api_key_not_set` | Not set |
| `pref_title_alt_text_base_url` | Base URL |
| `pref_title_alt_text_model` | Model |
| `pref_title_alt_text_prompt` | Prompt |
| `pref_title_alt_text_max_tokens` | Max tokens |
| `action_generate_alt_text` | Generate alt text |
| `action_cancel_generation` | Cancel generation |
| `error_alt_text_load_image` | Could not load image |
| `error_alt_text_no_text` | Model returned no text |
| `error_alt_text_server` | Server error (HTTP %1$d) |
| `error_alt_text_network` | Could not connect |
| `error_alt_text_not_configured` | Configure alt text generation in Settings |
| `label_alt_text_provider` | Provider: %1$s |
| `label_alt_text_cost` | Cost: %1$s |

## Error handling

| Failure | Snackbar |
|---|---|
| API key/model empty at click time | `error_alt_text_not_configured` |
| Image load/decode failure | `error_alt_text_load_image` |
| HTTP non-2xx with parseable `error.message` | that message verbatim |
| HTTP non-2xx without parseable body | `error_alt_text_server` |
| Network/IO error | `error_alt_text_network` |
| User cancellation | (silent) |
| Empty response content | `error_alt_text_no_text` |

The existing description text is never modified on failure or cancellation.

## Testing

`AltTextGeneratorTest` — JVM unit test (no Robolectric, no Android `Bitmap`), using `mockwebserver3.MockWebServer` and `mockito-kotlin`:

- Request shape: model name, prompt content, `max_tokens`, base64 data-URI prefix, `Authorization: Bearer ...` header.
- Response parsing: string `content`, list-of-parts `content` with multiple text fragments, OpenRouter-style `error.message` extraction.
- Server error fallback: HTTP 500 with non-JSON body produces `ServerHttpException(500)`.
- Empty response content produces `EmptyResponseException`.
- Base-URL joining with and without trailing slash both produce `/v1/chat/completions`.
- `isConfigured()` returns false when api key is blank, false when model is blank, true when both set.
- `usage.cost` and top-level `provider` are surfaced on `GenerationResult` when present in the response, and left null when absent.

Tests target `internal suspend fun generateFromBytes(jpegBytes: ByteArray)` directly, bypassing the Glide/`Bitmap` pipeline. The `resizeIfNeeded` math is not unit-tested (would need Robolectric); it is verified during manual smoke testing.

No instrumented UI tests for `CaptionDialog` — matches the project's existing posture.

## Dependencies

No new libraries. Uses existing Glide, OkHttp, Moshi (with KSP codegen), Hilt, Kotlin coroutines, Material Components, `mockwebserver3`, `mockito-kotlin`.

## Out of scope (YAGNI)

- Streaming responses.
- Per-account or per-instance overrides.
- Automatic retries.
- Prompt localization (English default; user can edit).
- Video/audio support; no first-frame extraction.
- Request history / undo for generated text.
- On-device models — bring-your-own server via base URL covers local hosting.
