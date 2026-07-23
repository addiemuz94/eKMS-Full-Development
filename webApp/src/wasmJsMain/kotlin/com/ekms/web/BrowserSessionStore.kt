package com.ekms.web

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.js.ExperimentalWasmJsInterop

private const val SESSION_KEY = "ekms.web.session"

@Serializable
internal data class PersistedSession(
    val accessToken: String,
    val refreshToken: String,
    val displayName: String,
)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(key) => localStorage.getItem(key)")
private external fun localStorageGetItem(key: String): String?

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(key, value) => { localStorage.setItem(key, value); }")
private external fun localStorageSetItem(key: String, value: String)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(key) => { localStorage.removeItem(key); }")
private external fun localStorageRemoveItem(key: String)

/** Persists auth tokens in the browser so refresh keeps the Super Admin signed in. */
internal object BrowserSessionStore {
    private val json = Json { ignoreUnknownKeys = true }

    fun save(session: PersistedSession) {
        localStorageSetItem(SESSION_KEY, json.encodeToString(session))
    }

    fun load(): PersistedSession? {
        val raw = localStorageGetItem(SESSION_KEY) ?: return null
        return runCatching { json.decodeFromString<PersistedSession>(raw) }.getOrNull()
    }

    fun clear() {
        localStorageRemoveItem(SESSION_KEY)
    }
}
