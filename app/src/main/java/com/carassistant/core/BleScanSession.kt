package com.carassistant.core

/** Tokens reject queued/late callbacks, even when stop throws. Failed cleanup blocks restart. */
class BleScanSession {
    private var generation = 0L
    var token: Long? = null
        private set
    var active = false
        private set
    fun begin(): Long? {
        if (token != null) return null
        active = true
        return (++generation).also { token = it }
    }
    fun accepts(candidate: Long) = active && token == candidate
    fun cancel() { active = false }
    fun stopped(success: Boolean) { if (success) token = null }
}
