package dev.pocketagent.agent

import okhttp3.RequestBody
import okio.BufferedSink

/** Prevent OkHttp's HTTP follow-ups from silently replaying a potentially billed POST.
 * Application-level retry policy remains the caller's responsibility.
 */
internal fun RequestBody.withoutAutomaticReplay(): RequestBody {
    val delegate = this
    return object : RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
    }
}

/** Service errors can echo credentials or signed URLs; sanitize before UI or audit logging. */
internal fun safeServiceError(raw: String, apiKey: String): String = raw
    .let { if (apiKey.isNotEmpty()) it.replace(apiKey, "[REDACTED]") else it }
    .replace(Regex("(?i)https?://[^\\s<>\\\"]+"), "[URL]")
    .replace(Regex("(?i)Bearer\\s+[^\\s,;]+"), "Bearer [REDACTED]")
    .replace(Regex("(?i)\\bsk-[a-z0-9_-]+"), "[REDACTED]")
    .replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim().take(1000)
