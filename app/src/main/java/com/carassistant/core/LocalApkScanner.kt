package com.carassistant.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地 APK 扫描器 —— 找出车机内部存储和 U 盘上的 .apk 文件。
 *
 * ## 为什么需要它
 *
 * 车机上装应用最土但最可靠的办法是：把 APK 拷到 U 盘 → 插上车机 → 装。
 * 但车机自带的文件管理器经常不认 .apk，或者干脆没有文件管理器。
 * 所以这个页面直接把可安装的 APK 列出来，点一下就走安装链。
 *
 * ## 存储权限在不同 Android 版本上完全不一样（实测差异很大）
 *
 * | 系统 | 情况 |
 * |------|------|
 * | Android 8/9 | READ_EXTERNAL_STORAGE 授予后基本可以随意遍历存储 |
 * | Android 10/11/12 | 分区存储登场，但本应用 targetSdk=28，走旧版模型，仍可遍历 |
 * | Android 13+ | 不再有独立的存储读取权限；同上，targetSdk=28 仍按旧版模型处理 |
 *
 * U 盘挂载点更是每个 ROM 都不一样（/mnt/usb、/mnt/usbhost、/mnt/media_rw/XXXX-XXXX、
 * /storage/XXXX-XXXX …），所以这里逐个容错探测：**读不到的目录只记一条 trace 就跳过，
 * 绝不因为一个挂载点不可读而中断整个扫描**。
 */
object LocalApkScanner {

    private const val TAG = "ApkScan"

    /** 目录递归深度上限：车机存储里目录层级不深，但嵌套遍历很容易卡死 */
    private const val MAX_DEPTH = 4

    /** 单次扫描最多返回多少个 APK */
    private const val MAX_FILES = 300

    /** 单次扫描最多访问多少个目录（防止 U 盘上文件特别多时卡住 UI） */
    private const val MAX_DIRS = 2_000

    /**
     * 跳过的目录名。
     *
     * Android 目录下全是应用私有数据（系统不允许别人读），进去只会白白耗时；
     * lost+found / proc / sys 之类则是访问必失败的伪目录。
     */
    private val SKIP_DIR_NAMES = setOf(
        "Android", "obb", "lost+found", "proc", "sys", "dev", "cache", ".thumbnails",
    )

    /** 一个扫描到的 APK 文件 */
    data class Entry(
        val path: String,
        val fileName: String,
        val sizeBytes: Long,
        val modifiedAt: Long,
        val packageName: String,
        val label: String,
        val versionName: String,
        val versionCode: Long,
        /** 解析失败时的原因；非空表示这个文件装不了 */
        val parseNote: String? = null,
        /** 来自 U 盘/外接卡（用于 UI 上区分扫描来源） */
        val fromUsb: Boolean = false,
    ) {
        val displayName: String get() = label.ifBlank { fileName }
        val isParsed: Boolean get() = packageName.isNotBlank()
    }

    data class Result(val entries: List<Entry>, val trace: List<String>)

    private data class Root(val dir: File, val fromUsb: Boolean, val label: String)

    suspend fun scan(context: Context): Result = withContext(Dispatchers.IO) {
        val trace = mutableListOf<String>()
        val pm = context.packageManager

        val readGranted = Permissions.hasReadStorage(context)
        trace += "存储读取权限：" + if (readGranted) "已授予" else "未授予"
        when {
            !readGranted && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ->
                trace += "未授予 READ_EXTERNAL_STORAGE：内部存储大概率扫不到，建议先在页面里授权"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                trace += "Android 13+ 已无独立存储读取权限；本应用 targetSdk=28，按旧版存储模型处理，" +
                    "多数车机上仍可直接读 /sdcard 与 U 盘"
        }

        val roots = collectRoots(context, trace)
        trace += "扫描根目录 " + roots.size + " 个：" + roots.joinToString("、") { it.label }

        val found = LinkedHashMap<String, Entry>()
        val queue = ArrayDeque<Triple<File, Int, Boolean>>()
        roots.forEach { queue.add(Triple(it.dir, 0, it.fromUsb)) }

        var dirCount = 0
        while (queue.isNotEmpty() && found.size < MAX_FILES && dirCount < MAX_DIRS) {
            val (dir, depth, fromUsb) = queue.removeFirst()
            dirCount++

            val children = runCatching { dir.listFiles() }.getOrNull()
            if (children == null) {
                trace += "目录不可读，跳过：" + dir.absolutePath
                continue
            }

            for (child in children) {
                if (found.size >= MAX_FILES) break
                val name = runCatching { child.name }.getOrNull() ?: continue
                if (name.startsWith(".")) continue

                val isDir = runCatching { child.isDirectory }.getOrDefault(false)
                if (isDir) {
                    if (depth >= MAX_DEPTH) continue
                    if (SKIP_DIR_NAMES.contains(name)) continue
                    if (!runCatching { child.canRead() }.getOrDefault(false)) {
                        trace += "子目录不可读，跳过：" + child.absolutePath
                        continue
                    }
                    queue.add(Triple(child, depth + 1, fromUsb))
                    continue
                }

                if (!name.endsWith(".apk", ignoreCase = true)) continue
                val length = runCatching { child.length() }.getOrDefault(0L)
                if (length <= 0L) continue
                val path = child.absolutePath
                if (found.containsKey(path)) continue
                found[path] = describe(pm, child, fromUsb)
            }
        }

        if (found.size >= MAX_FILES) {
            trace += "已达到单次扫描上限 " + MAX_FILES + " 个，可能还有遗漏"
        }

        // 能解析出包名的排前面（那些才是真能装的），其次按修改时间新的在前
        val entries = found.values.sortedWith(
            compareByDescending<Entry> { it.isParsed }
                .thenByDescending { it.modifiedAt },
        )
        trace += "共找到 " + entries.size + " 个 .apk（可解析 " +
            entries.count { it.isParsed } + " 个）"

        Result(entries, trace)
    }

    /** 收集所有可能的扫描根目录，任何一个不存在/不可读都只记 trace 不报错 */
    private fun collectRoots(context: Context, trace: MutableList<String>): List<Root> {
        val roots = mutableListOf<Root>()
        val seen = mutableSetOf<String>()

        fun add(file: File?, fromUsb: Boolean, label: String) {
            if (file == null) return
            val path = runCatching { file.absolutePath }.getOrNull() ?: return
            if (!seen.add(path)) return
            if (!runCatching { file.exists() }.getOrDefault(false)) return
            if (!runCatching { file.canRead() }.getOrDefault(false)) {
                trace += label + " 不可读，跳过：" + path
                return
            }
            roots += Root(file, fromUsb, label)
        }

        // 本应用自己的下载目录（永远可读）
        val externalBase = context.getExternalFilesDir(null) ?: context.filesDir
        add(File(externalBase, "apk"), false, "本应用下载目录")

        // 内部存储：三种写法都试，不同 ROM 返回的不一样
        @Suppress("DEPRECATION")
        val externalStorage = Environment.getExternalStorageDirectory()
        add(externalStorage, false, "内部存储")
        add(File("/sdcard"), false, "内部存储(/sdcard)")
        add(File("/storage/emulated/0"), false, "内部存储(/storage/emulated/0)")

        // U 盘 / 外接卡：车机挂载点没有标准，逐个容错探测
        listOf(
            "/mnt/usb",
            "/mnt/usbhost",
            "/mnt/usb_storage",
            "/mnt/usbdrive",
            "/mnt/media_rw",
            "/mnt/sdcard",
        ).forEach { add(File(it), true, "外接存储(" + it + ")") }

        listOf("/storage", "/mnt").forEach { parent ->
            val children = runCatching { File(parent).listFiles() }.getOrNull() ?: return@forEach
            children.forEach { child ->
                val name = runCatching { child.name }.getOrNull() ?: return@forEach
                if (name == "emulated" || name == "self" || name == "enc_emulated") return@forEach
                if (!runCatching { child.isDirectory }.getOrDefault(false)) return@forEach
                add(child, true, "外接存储(" + child.absolutePath + ")")
            }
        }

        return roots
    }

    /** 读取 APK 的应用名/包名/版本；解析不了也保留条目，只是标记 parseNote */
    private fun describe(pm: PackageManager, file: File, fromUsb: Boolean): Entry {
        val info = runCatching { archiveInfo(pm, file.absolutePath) }.getOrNull()
        val app = info?.applicationInfo
        var label = ""
        if (app != null) {
            // 未安装的 APK 必须补上这两个路径，否则 loadLabel 拿不到资源
            app.sourceDir = file.absolutePath
            app.publicSourceDir = file.absolutePath
            label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault("")
        }
        return Entry(
            path = file.absolutePath,
            fileName = file.name,
            sizeBytes = file.length(),
            modifiedAt = file.lastModified(),
            packageName = info?.packageName.orEmpty(),
            label = label,
            versionName = info?.versionName.orEmpty(),
            versionCode = info?.let { ApkInstaller.versionCodeOf(it) } ?: 0L,
            parseNote = if (info == null) "无法解析（可能不是有效 APK，或需要更高的系统版本）" else null,
            fromUsb = fromUsb,
        )
    }

    private fun archiveInfo(pm: PackageManager, path: String) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, 0)
        }
}
