package com.vtubephone.core.llm

/** Blocking transport. Callers own scheduling; bodies may be JSON, form data or binary audio. */
interface HttpTransport {
    fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResult
    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResult
}

data class HttpResult(val status: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap()) {
    val successful get() = status in 200..299
    fun text() = body.toString(Charsets.UTF_8)
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
