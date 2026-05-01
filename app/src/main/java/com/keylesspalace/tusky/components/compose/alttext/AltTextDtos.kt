/* Copyright 2026 Tusky Contributors
 *
 * This file is a part of Tusky.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * Tusky is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Tusky; if not,
 * see <http://www.gnu.org/licenses>. */

package com.keylesspalace.tusky.components.compose.alttext

import com.squareup.moshi.Json

internal data class ChatCompletionRequest(
    val model: String,
    @Json(name = "max_tokens") val maxTokens: Int,
    val messages: List<RequestMessage>
)

internal data class RequestMessage(
    val role: String,
    val content: List<ContentPart>
)

internal data class ContentPart(
    val type: String,
    val text: String? = null,
    @Json(name = "image_url") val imageUrl: ImageUrl? = null
)

internal data class ImageUrl(val url: String)

internal data class ChatCompletionResponse(
    val choices: List<Choice>?,
    val error: ApiError?
)

internal data class Choice(val message: ResponseMessage?)

internal data class ResponseMessage(
    /**
     * Some providers return a plain string; some return a JSON array of typed parts. We accept
     * either by parsing as `Any?` and reading both shapes in `AltTextGenerator`.
     */
    val content: Any?
)

internal data class ApiError(val message: String?)
