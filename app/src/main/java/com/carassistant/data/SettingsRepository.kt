package com.carassistant.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.carassistant.core.LaunchTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "car_assistant_settings"
)

/**
 * 配置仓库（DataStore + kotlinx.serialization）。
 *
 * 相比 SharedPreferences：
 *  - 类型安全，没有 `getString` 拿到 null 的意外
 *  - 天然暴露 Flow，Compose 侧 collectAsState 即可，不用手动注册监听器
 *  - 写操作是事务性的，不会出现半写入状态
 *
 * 启动项列表用 JSON 序列化后存成单个字符串 —— 因为它是有序列表，
 * 而 Preferences 的键值模型表达不了顺序。
 */
class SettingsRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ==================== 键定义 ====================

    private object Keys {
        val triggerOnBoot = booleanPreferencesKey("trigger_on_boot")
        val triggerOnScreenOn = booleanPreferencesKey("trigger_on_screen_on")
        val triggerOnPowerConnect = booleanPreferencesKey("trigger_on_power_connect")

        val bootDelayMs = intPreferencesKey("boot_delay_ms")

        val wifiEnabled = booleanPreferencesKey("wifi_enabled")
        val wifiUiFallback = booleanPreferencesKey("wifi_ui_fallback")
        val wifiVerifyTimeoutMs = intPreferencesKey("wifi_verify_timeout_ms")

        val launchEnabled = booleanPreferencesKey("launch_enabled")
        val targetsJson = stringPreferencesKey("launch_targets_json")

        /** 开机流程开始时是否拉起本应用自己的界面 */
        val openSelfOnBoot = booleanPreferencesKey("open_self_on_boot")

        val allowRoot = booleanPreferencesKey("allow_root")
        val verifyLaunch = booleanPreferencesKey("verify_launch")
        val showOngoingNotification = booleanPreferencesKey("show_ongoing_notification")
    }

    // ==================== 设置快照 ====================

    data class Settings(
        val triggerOnBoot: Boolean = true,
        val triggerOnScreenOn: Boolean = false,
        val triggerOnPowerConnect: Boolean = true,
        val bootDelayMs: Int = 8_000,
        val wifiEnabled: Boolean = true,
        val wifiUiFallback: Boolean = true,
        val wifiVerifyTimeoutMs: Int = 2_500,
        val launchEnabled: Boolean = true,
        val targets: List<LaunchTarget> = emptyList(),

        /** 开机时先拉起本应用界面（用户能实时看到流程进度），默认开 */
        val openSelfOnBoot: Boolean = true,

        val allowRoot: Boolean = true,
        val verifyLaunch: Boolean = true,
        val showOngoingNotification: Boolean = true,
    )

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            triggerOnBoot = prefs[Keys.triggerOnBoot] ?: true,
            triggerOnScreenOn = prefs[Keys.triggerOnScreenOn] ?: false,
            triggerOnPowerConnect = prefs[Keys.triggerOnPowerConnect] ?: true,
            bootDelayMs = prefs[Keys.bootDelayMs] ?: 8_000,
            wifiEnabled = prefs[Keys.wifiEnabled] ?: true,
            wifiUiFallback = prefs[Keys.wifiUiFallback] ?: true,
            wifiVerifyTimeoutMs = prefs[Keys.wifiVerifyTimeoutMs] ?: 2_500,
            launchEnabled = prefs[Keys.launchEnabled] ?: true,
            targets = decodeTargets(prefs[Keys.targetsJson]),
            openSelfOnBoot = prefs[Keys.openSelfOnBoot] ?: true,
            allowRoot = prefs[Keys.allowRoot] ?: true,
            verifyLaunch = prefs[Keys.verifyLaunch] ?: true,
            showOngoingNotification = prefs[Keys.showOngoingNotification] ?: true,
        )
    }

    /** 同步读取当前值（供后台流程使用，避免在 Service 里管理 Flow 生命周期） */
    suspend fun current(): Settings = settings.first()

    private fun decodeTargets(raw: String?): List<LaunchTarget> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<LaunchTarget>>(raw) }
            .getOrDefault(emptyList())
    }

    private fun encodeTargets(targets: List<LaunchTarget>): String =
        json.encodeToString(targets)

    // ==================== 写操作 ====================

    suspend fun setTriggerOnBoot(value: Boolean) = edit { it[Keys.triggerOnBoot] = value }

    suspend fun setTriggerOnScreenOn(value: Boolean) = edit { it[Keys.triggerOnScreenOn] = value }

    suspend fun setTriggerOnPowerConnect(value: Boolean) =
        edit { it[Keys.triggerOnPowerConnect] = value }

    suspend fun setBootDelayMs(value: Int) =
        edit { it[Keys.bootDelayMs] = value.coerceIn(0, 60_000) }

    suspend fun setWifiEnabled(value: Boolean) = edit { it[Keys.wifiEnabled] = value }

    suspend fun setWifiUiFallback(value: Boolean) = edit { it[Keys.wifiUiFallback] = value }

    suspend fun setWifiVerifyTimeoutMs(value: Int) =
        edit { it[Keys.wifiVerifyTimeoutMs] = value.coerceIn(500, 15_000) }

    suspend fun setLaunchEnabled(value: Boolean) = edit { it[Keys.launchEnabled] = value }

    suspend fun setOpenSelfOnBoot(value: Boolean) = edit { it[Keys.openSelfOnBoot] = value }

    suspend fun setAllowRoot(value: Boolean) = edit { it[Keys.allowRoot] = value }
    suspend fun setVerifyLaunch(value: Boolean) = edit { it[Keys.verifyLaunch] = value }

    suspend fun setShowOngoingNotification(value: Boolean) =
        edit { it[Keys.showOngoingNotification] = value }

    // ==================== 启动项管理 ====================

    suspend fun addTarget(target: LaunchTarget) = edit { prefs ->
        val current = decodeTargets(prefs[Keys.targetsJson]).toMutableList()
        val existingIndex = current.indexOfFirst { it.packageName == target.packageName }
        if (existingIndex >= 0) {
            // 已存在则更新并重新启用，不重复添加
            current[existingIndex] = current[existingIndex].copy(
                label = target.label,
                enabled = true,
            )
        } else {
            current.add(target)
        }
        prefs[Keys.targetsJson] = encodeTargets(current)
    }

    suspend fun removeTarget(packageName: String) = edit { prefs ->
        val updated = decodeTargets(prefs[Keys.targetsJson])
            .filterNot { it.packageName == packageName }
        prefs[Keys.targetsJson] = encodeTargets(updated)
    }

    suspend fun setTargetEnabled(packageName: String, enabled: Boolean) = edit { prefs ->
        val updated = decodeTargets(prefs[Keys.targetsJson]).map {
            if (it.packageName == packageName) it.copy(enabled = enabled) else it
        }
        prefs[Keys.targetsJson] = encodeTargets(updated)
    }

    suspend fun setTargetWait(packageName: String, waitMs: Int) = edit { prefs ->
        val updated = decodeTargets(prefs[Keys.targetsJson]).map {
            if (it.packageName == packageName) {
                it.copy(waitBeforeMs = waitMs.coerceIn(0, 120_000))
            } else {
                it
            }
        }
        prefs[Keys.targetsJson] = encodeTargets(updated)
    }

    /**
     * 把某个启动项移到最前。
     *
     * 顺序很关键：如果第一个启动的是亿连，它会占据前台，
     * 后面启动的 App 就抢不到焦点了 —— 所以「谁最重要谁排第一」。
     */
    suspend fun moveTargetToTop(packageName: String) = edit { prefs ->
        val current = decodeTargets(prefs[Keys.targetsJson])
        val target = current.firstOrNull { it.packageName == packageName } ?: return@edit
        val reordered = listOf(target) + current.filterNot { it.packageName == packageName }
        prefs[Keys.targetsJson] = encodeTargets(reordered)
    }

    suspend fun moveTargetUp(packageName: String) = edit { prefs ->
        val current = decodeTargets(prefs[Keys.targetsJson]).toMutableList()
        val index = current.indexOfFirst { it.packageName == packageName }
        if (index > 0) {
            val item = current.removeAt(index)
            current.add(index - 1, item)
        }
        prefs[Keys.targetsJson] = encodeTargets(current)
    }

    suspend fun moveTargetDown(packageName: String) = edit { prefs ->
        val current = decodeTargets(prefs[Keys.targetsJson]).toMutableList()
        val index = current.indexOfFirst { it.packageName == packageName }
        if (index >= 0 && index < current.size - 1) {
            val item = current.removeAt(index)
            current.add(index + 1, item)
        }
        prefs[Keys.targetsJson] = encodeTargets(current)
    }

    suspend fun replaceTargets(targets: List<LaunchTarget>) = edit { prefs ->
        prefs[Keys.targetsJson] = encodeTargets(targets)
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}