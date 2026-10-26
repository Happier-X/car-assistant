package com.carassistant.core

/** Actual operation gate: late callbacks cannot submit work after a page/channel has closed. */
class P2pOperationGate {
    private var generation = 0L
    private var active: Long? = null
    val busy get() = active != null
    fun begin(): Long? = if (busy) null else (++generation).also { active = it }
    fun matches(token: Long) = active == token
    fun finish(token: Long): Boolean {
        if (!matches(token)) return false
        active = null
        return true
    }
    fun invalidate() { ++generation; active = null }

    companion object {
        fun refusal(groupPresent: Boolean?, negotiating: Boolean?, enabled: Boolean?): String? = when {
            groupPresent == null || negotiating == null || enabled == null -> "共享 P2P 状态未知，未发起操作；请刷新或使用系统设置"
            !enabled -> "系统 P2P 未启用，请先到 WiFi 设置查看"
            groupPresent || negotiating -> "已有系统直连组或正在协商，归属无法确认；为保护 CarLink 等连接，本应用不再发起操作"
            else -> null
        }
    }
}
