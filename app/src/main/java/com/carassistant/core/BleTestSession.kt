package com.carassistant.core

/** Platform-independent gate used by the Android callback controller. */
class BleTestSession {
    enum class Phase { IDLE, STARTING, ADVERTISING, STOPPED, FAILED, STOP_FAILED }
    var phase = Phase.IDLE
        private set
    private var generation = 0L
    private var current: Long? = null
    private var pendingStop: Long? = null

    fun begin(): Long? {
        if (current != null || pendingStop != null) return null
        return (++generation).also { current = it; phase = Phase.STARTING }
    }

    fun success(token: Long): Boolean {
        if (current != token || phase != Phase.STARTING) return false
        phase = Phase.ADVERTISING
        return true
    }

    fun failure(token: Long): Boolean {
        if (current != token || phase != Phase.STARTING) return false
        current = null
        phase = Phase.FAILED
        return true
    }

    fun cancel(token: Long): Boolean {
        if (current != token && pendingStop != token) return false
        current = null
        pendingStop = token
        phase = Phase.STOPPED
        return true
    }

    fun stopped(token: Long, success: Boolean) {
        if (pendingStop != token) return
        if (success) pendingStop = null
        phase = if (success) Phase.STOPPED else Phase.STOP_FAILED
    }
}
