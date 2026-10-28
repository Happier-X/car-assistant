package com.carassistant.core

/**
 * 「可连接 / 可扫描广播」测试的纯逻辑（无 Android 依赖，便于单测）。
 *
 * 为什么要有第二种模式：CarLink 车机端用 `BluetoothLeAdvertiser.startAdvertisingSet(...)`，
 * `setConnectable(true)` + `setScannable(true)` + `setLegacyMode(true)` + `primaryPhy=LE_1M`
 * + `txPowerLevel=MEDIUM`。v1.4.0 的第一种模式是 `startAdvertising` + `connectable=false`，
 * 它只能证明「车机发得出非连接广播」，测不出「能不能发可连接广播」这一维度。
 * 所以两种模式并存、各自独立跑、各自留证据：第一种当对照组，第二种是可连接组。
 *
 * 判定口径沿用 BleDirection：启动回调只表示系统受理，不代表手机扫得到；
 * 缺失证据一律「未知」，不写成「BLE 正常 / 异常」。本应用不改 CarLink、不改 RSSI、不改信道。
 */
object BleConnectableAdv {
    /** 与对照模式同一个测试 Service UUID（本应用自己的中立 UUID，不是 CarLink 的）。 */
    const val SERVICE_UUID = "9cb329d0-536c-4f32-8ea4-5b9021f67a10"

    /**
     * 载荷 CA2（43 41 32）。**不得复用对照模式的 CA1（43 41 31）**：
     * 手机侧一旦分不清扫到的是哪一组广播，对照与结论就都失效。
     */
    val PAYLOAD: ByteArray = byteArrayOf(0x43, 0x41, 0x32)
    const val PAYLOAD_HEX = "43 41 32"
    const val PAYLOAD_TEXT = "CA2"

    /**
     * 扫描响应里的固定短标签（≤5 字节，实算见下方字节预算）。
     *
     * 为什么用公司 ID 而不是设备名：Android 没有设置 AD 短名（0x08）的公开 API，
     * `setIncludeDeviceName(true)` 会把整机蓝牙名写进扫描响应（长度不可控、还会把名称带出去），
     * 所以改用 SIG 保留给测试用途的公司 ID 0xFFFF 携带固定标签，不含任何厂商/车辆身份。
     */
    const val TEST_COMPANY_ID = 0xFFFF
    const val SCAN_RSP_TAG = "CA2"

    // ---- legacy 31 字节预算（实际算过，界面与报告都写出来） ----
    // 广播数据 = 系统自动附加的 Flags AD（2 字节头 + 1 字节值）+ 128 位 Service Data（2 字节头 + 16 字节 UUID + 3 字节载荷）= 24
    // 扫描响应 = AD 头（2）+ 公司 ID（2）+ 固定标签（3）= 7
    const val LEGACY_LIMIT = 31
    const val FLAGS_AD_BYTES = 3
    const val SERVICE_DATA_AD_BYTES = 21
    const val ADV_DATA_BYTES = FLAGS_AD_BYTES + SERVICE_DATA_AD_BYTES
    const val SCAN_RSP_AD_BYTES = 7
    const val COMBINED_BYTES = ADV_DATA_BYTES + SCAN_RSP_AD_BYTES

    /** 请求参数（与 CarLink 对齐；只用系统合法区间，不反射改 mInterval）。 */
    const val PARAMETERS =
        "connectable=true；scannable=true；legacy=true；primaryPhy=LE_1M；txPowerLevel=MEDIUM（TX_POWER_MEDIUM=-7）；" +
            "interval=未调用 setInterval（使用系统默认，合法区间 160~16777215）；未反射修改 mInterval"

    const val DATA_NOTE =
        "广播内容全部为本应用中立内容：不含设备名、车牌、VIN、账号、CarLink 私有内容或小米/车辆身份标识。" +
            "扫描响应不用设备名（Android 没有 AD 短名 API，整机蓝牙名长度不可控且会泄漏名称），改用 SIG 保留测试公司 ID 0xFFFF + 固定 3 字节标签。"

    const val LIMITS =
        "手动触发、最长 30 秒、不自启、开机不跑；离页/后台只停止本应用这一个 callback/广播集，不影响其他应用，也不改 CarLink。" +
            "启动回调成功 ≠ 手机能收到：回调只表示系统受理。手机侧能否扫到必须在另一台设备上实扫后由用户登记，本应用不自动核实。" +
            "DATA_TOO_LARGE 是本应用测试数据问题，TOO_MANY_ADVERTISERS 多半是 CarLink 等应用仍占用广播实例，" +
            "两者都不能写成「车机不支持可连接广播」；只有 FEATURE_UNSUPPORTED 才是能力结论。" +
            "默认导出只写结构与长度，不写载荷/标签字节；不含 MAC、VIN、账号。"

    /** 31 字节预算是否仍然成立（改长度常量后单测会先失败）。 */
    fun fitsLegacy(): Boolean =
        ADV_DATA_BYTES <= LEGACY_LIMIT && SCAN_RSP_AD_BYTES <= LEGACY_LIMIT && COMBINED_BYTES <= LEGACY_LIMIT

    fun budgetLine(): String =
        "legacy 单包上限 $LEGACY_LIMIT 字节：广播数据 $ADV_DATA_BYTES/$LEGACY_LIMIT" +
            "（系统 Flags $FLAGS_AD_BYTES + Service Data $SERVICE_DATA_AD_BYTES）；" +
            "扫描响应 $SCAN_RSP_AD_BYTES/$LEGACY_LIMIT（AD 头 2 + 公司 ID 2 + 固定标签 3）；" +
            "按「两包合计」的保守口径也是 $COMBINED_BYTES/$LEGACY_LIMIT。"

    /** 界面/正文用的完整内容说明（含 UUID、载荷、标签）。 */
    fun detailLine(): String =
        "广播数据：Service Data UUID $SERVICE_UUID，载荷 $PAYLOAD_HEX（$PAYLOAD_TEXT，3 字节）；" +
            "扫描响应：公司 ID 0xFFFF + 固定标签 $SCAN_RSP_TAG"

    /** 默认导出：只给结构与长度，不给载荷/标签字节。 */
    fun exportLine(): String =
        "广播数据：128 位 Service Data（本应用测试 UUID）+ 3 字节固定载荷（字节内容默认导出隐藏）；" +
            "扫描响应：SIG 保留测试公司 ID 0xFFFF + 3 字节固定标签（内容默认导出隐藏）；" +
            "不含设备名、车牌、VIN、账号或厂商身份"

    /** 本模式自身的结论状态；只由真实回调/状态码推出，不做猜测。 */
    enum class ModeState(val label: String) {
        NOT_TESTED("本次未做可连接广播测试"),
        PENDING("已提交启动请求，等待系统回调（不算成功）"),
        STARTED("可连接广播启动回调成功（仅表示系统受理）"),
        CALLBACK_TIMEOUT("启动回调超时（8 秒内无回调，不算成功）"),
        FAILED_DATA_TOO_LARGE("失败：测试数据超长（问题在测试数据，不代表车机不支持）"),
        FAILED_TOO_MANY_ADVERTISERS("失败：可用广播实例不足（多半是实例被占用，不代表车机不支持）"),
        FAILED_ALREADY_STARTED("失败：该回调对应的广播已启动过（状态冲突）"),
        FAILED_FEATURE_UNSUPPORTED("失败：系统报告不支持此广播功能（能力结论）"),
        FAILED_INTERNAL_ERROR("失败：系统蓝牙内部错误（原因未知）"),
        FAILED_UNKNOWN("失败：状态码未知或未取得状态（原因未知）"),
    }

    /** 归因：把「测试数据 / 环境冲突 / 能力 / 系统内部 / 未知」分开，避免混成一句「不支持」。 */
    enum class Blame(val label: String) {
        NONE("无失败"),
        TEST_DATA("测试数据问题，不代表车机不支持"),
        ENVIRONMENT("测试环境或实例占用，不代表车机不支持"),
        CAPABILITY("系统报告的能力结论"),
        SYSTEM("系统内部原因，未知"),
        UNKNOWN("原因未知"),
    }

    data class Explanation(val state: ModeState, val blame: Blame, val hint: String, val retriable: Boolean)

    /**
     * 状态码归类。数值与 android.bluetooth.le.AdvertiseCallback / AdvertisingSetCallback 一致：
     * 1 DATA_TOO_LARGE、2 TOO_MANY_ADVERTISERS、3 ALREADY_STARTED、4 INTERNAL_ERROR、5 FEATURE_UNSUPPORTED。
     */
    fun classify(status: Int): ModeState = when (status) {
        0 -> ModeState.STARTED
        1 -> ModeState.FAILED_DATA_TOO_LARGE
        2 -> ModeState.FAILED_TOO_MANY_ADVERTISERS
        3 -> ModeState.FAILED_ALREADY_STARTED
        4 -> ModeState.FAILED_INTERNAL_ERROR
        5 -> ModeState.FAILED_FEATURE_UNSUPPORTED
        else -> ModeState.FAILED_UNKNOWN
    }

    fun statusName(status: Int): String = when (status) {
        0 -> "ADVERTISE_SUCCESS"
        1 -> "ADVERTISE_FAILED_DATA_TOO_LARGE"
        2 -> "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS"
        3 -> "ADVERTISE_FAILED_ALREADY_STARTED"
        4 -> "ADVERTISE_FAILED_INTERNAL_ERROR"
        5 -> "ADVERTISE_FAILED_FEATURE_UNSUPPORTED"
        else -> "未知状态码"
    }

    /**
     * `onAdvertisingSetStarted` 的纯分发：**status == 0 才算系统受理成功**，非 0 一律按状态码归类失败。
     *
     * 与旧的 `AdvertiseCallback.onStartSuccess` 不同，这个回调**成功与失败共用**：
     * FEATURE_UNSUPPORTED / DATA_TOO_LARGE / TOO_MANY_ADVERTISERS 都会带着非 0 status 调用它，
     * 因此必须先判 status 再决定是否推进会话，否则失败会被当成启动成功。
     */
    fun startedOutcome(status: Int): ModeState =
        if (status == 0) ModeState.STARTED else classify(status)

    /**
     * 由会话阶段 + 启动回调状态码 + 是否超时推出本模式结论。
     *
     * 防御优先级最高：只要带回非 0 状态码，**任何阶段**都按失败归类，绝不允许返回 [ModeState.STARTED]，
     * 避免将来接线漏判时把 FEATURE_UNSUPPORTED 这类能力结论报成启动成功。
     * 超时永远不算成功；失败阶段即使带回 status=0 也以失败为准。
     */
    fun modeState(
        phase: BleTestSession.Phase,
        startedStatus: Int? = null,
        timedOut: Boolean = false,
    ): ModeState = when {
        // 1) 非 0 状态码 = 失败，与阶段无关（ADVERTISING/STOPPED 也一样）。
        startedStatus != null && startedStatus != 0 -> classify(startedStatus)
        // 2) 没有失败状态码时再看阶段。
        phase == BleTestSession.Phase.IDLE -> ModeState.NOT_TESTED
        phase == BleTestSession.Phase.STARTING -> if (timedOut) ModeState.CALLBACK_TIMEOUT else ModeState.PENDING
        phase == BleTestSession.Phase.FAILED -> if (timedOut) ModeState.CALLBACK_TIMEOUT else ModeState.FAILED_UNKNOWN
        // 3) 只剩 ADVERTISING / STOPPED / STOP_FAILED，且状态码为 null 或 0 → 才算启动成功。
        else -> ModeState.STARTED
    }

    fun explain(state: ModeState): Explanation = when (state) {
        ModeState.NOT_TESTED -> Explanation(state, Blame.NONE, "尚未测试：先跑对照模式（不可连接），再跑可连接/可扫描模式", retriable = false)
        ModeState.PENDING -> Explanation(state, Blame.NONE, "等待系统回调；8 秒内没有 onAdvertisingSetStarted 会记为回调超时，不会写成成功", retriable = false)
        ModeState.STARTED -> Explanation(state, Blame.NONE, "启动回调只代表系统受理；下一步用另一台设备的扫描工具实扫，确认能否扫到", retriable = false)
        ModeState.CALLBACK_TIMEOUT -> Explanation(
            state, Blame.UNKNOWN,
            "8 秒内未收到 onAdvertisingSetStarted：既不能算成功，也不能算车机不支持。重跑一次；仍超时就记录系统蓝牙状态，不下能力结论",
            retriable = true,
        )
        ModeState.FAILED_DATA_TOO_LARGE -> Explanation(
            state, Blame.TEST_DATA,
            "测试数据超长：这是本应用构造的广播内容问题，不是车机不支持可连接广播。按界面字节占用说明修数据后重跑",
            retriable = true,
        )
        ModeState.FAILED_TOO_MANY_ADVERTISERS -> Explanation(
            state, Blame.ENVIRONMENT,
            "可用广播实例不足：很可能 CarLink 自己的广播还占着实例。先让 CarLink 停止广播或 Force stop CarLink 再重测；仍失败才考虑能力结论",
            retriable = true,
        )
        ModeState.FAILED_ALREADY_STARTED -> Explanation(state, Blame.ENVIRONMENT, "该回调对应的广播已启动：先停止或等 30 秒时限结束，再重跑", retriable = true)
        ModeState.FAILED_FEATURE_UNSUPPORTED -> Explanation(
            state, Blame.CAPABILITY,
            "系统明确报告不支持该广播功能：这才是能力结论。与 DATA_TOO_LARGE / TOO_MANY_ADVERTISERS 严格区分",
            retriable = false,
        )
        ModeState.FAILED_INTERNAL_ERROR -> Explanation(state, Blame.SYSTEM, "系统蓝牙内部错误：原因未知，可重跑一次；不要据此判定支持或不支持", retriable = true)
        ModeState.FAILED_UNKNOWN -> Explanation(state, Blame.UNKNOWN, "未取得状态码或状态码未知：原因未知，可重跑一次并保留原始状态码", retriable = true)
    }

    const val CAVEAT =
        "启动回调成功 ≠ 手机能收到：回调只表示系统受理了这个广播请求。手机侧能否扫到必须在另一台设备上实扫后由用户登记，本应用不自动核实。"

    /** 两种模式并排的组合结论（表格化字段，一眼判读）。 */
    data class Combination(
        val plain: String,
        val connectable: String,
        val separation: String,
        val conclusion: String,
        val nextStep: String,
    ) {
        fun lines(): List<String> = listOf(
            "对照模式（不可连接广播）：$plain",
            "可连接/可扫描广播：$connectable",
            "两者是否分离：$separation",
            "结论：$conclusion",
            "下一步：$nextStep",
            "口径：$CAVEAT",
        )

        fun text(): String = lines().joinToString("\n")
    }

    fun combination(plain: BleDirection.AdvertiseState, mode: ModeState): Combination {
        val plainLabel = plain.label
        val modeLabel = mode.label
        val (separation, conclusion, nextStep) = when (mode) {
            ModeState.NOT_TESTED -> Triple(
                "尚未测试",
                if (plain == BleDirection.AdvertiseState.NOT_TESTED) "两种模式都还没有记录，不能对「可连接广播」下任何结论"
                else "本次只做了对照模式（$plainLabel），可连接广播这一维度仍是未知",
                "先跑对照模式（不可连接），再跑可连接/可扫描模式，两个结果都留下后回来看组合结论",
            )
            ModeState.PENDING -> Triple("等待系统回调", "还没有结论", "等 8 秒启动回调窗口结束；超时会记成「启动回调超时」，不会写成成功")
            ModeState.STARTED -> if (plain == BleDirection.AdvertiseState.STARTED) Triple(
                "两种模式都有启动成功回调（未分离）",
                "系统对两种广播请求都返回了启动成功：车机既发得出非连接广播，也受理了可连接/可扫描广播",
                "若手机仍只扫到对照模式、扫不到可连接模式，问题不在「能不能发可连接广播」，改查广播内容与手机侧扫描设置",
            ) else Triple(
                "只有可连接模式有启动成功回调",
                "可连接/可扫描广播启动成功；对照模式本次没有成功记录（不代表非连接广播不支持）",
                "补跑一次对照模式拿到对照组，才能说两种模式是否分离",
            )
            ModeState.CALLBACK_TIMEOUT -> Triple(
                "不可判定",
                "8 秒内没有启动回调：既不能算成功，也不能算车机不支持可连接广播",
                "重跑一次；再次超时就先记录系统蓝牙状态（是否被其他应用占用、是否刚切换开关），不要下能力结论",
            )
            ModeState.FAILED_FEATURE_UNSUPPORTED -> if (plain == BleDirection.AdvertiseState.STARTED) Triple(
                "分离成立：非连接可启动，可连接被系统拒绝",
                "可连接/可扫描广播被系统以 FEATURE_UNSUPPORTED 拒绝，而对照模式有启动成功回调：这是能力层面的分离结论",
                "保留这次结果作为证据；不要再调 RSSI、不要改信道、不要换硬件，先按能力限制处理",
            ) else Triple(
                "能力结论但缺对照组",
                "可连接/可扫描广播被系统以 FEATURE_UNSUPPORTED 拒绝；但对照模式本次没有成功记录，分离未证实",
                "补跑一次对照模式；两次结果都在，才能说车机「能发非连接、发不了可连接」",
            )
            ModeState.FAILED_TOO_MANY_ADVERTISERS -> Triple(
                "不可判定（环境冲突）",
                "广播实例不足：很可能是 CarLink 自己的广播仍占着实例，这不能判定为车机不支持可连接广播",
                "先让 CarLink 停止广播或 Force stop CarLink，再重测一次",
            )
            ModeState.FAILED_DATA_TOO_LARGE -> Triple(
                "不可判定（测试数据）",
                "系统报告广播数据超长：这是本应用构造的内容问题，不代表车机不支持",
                "按界面上的 31 字节占用说明修数据后重跑；不要据此下能力结论",
            )
            ModeState.FAILED_ALREADY_STARTED -> Triple("不可判定（状态冲突）", "该回调对应的广播已启动：本次没有新的启动证据", "先停止或等 30 秒时限结束，再重跑")
            ModeState.FAILED_INTERNAL_ERROR -> Triple("不可判定（系统内部）", "系统蓝牙内部错误：原因未知，不构成支持或不支持的结论", "重跑一次；重复出现再记录系统蓝牙状态")
            ModeState.FAILED_UNKNOWN -> Triple("不可判定（未知）", "没有拿到状态码或状态码未知：原因未知", "重跑一次；仍异常就保留原始状态码")
        }
        return Combination(
            plain = plainLabel,
            connectable = modeLabel,
            separation = separation,
            conclusion = conclusion,
            nextStep = nextStep,
        )
    }

    /** 报告正文里的本模式章节（复用既有 TXT / 局域网页导出通道，不新造体系）。 */
    fun exportSection(
        plain: BleDirection.AdvertiseState,
        mode: ModeState,
        callbacks: List<String> = emptyList(),
        actualParams: String = "未取得（尚未收到 onAdvertisingSetStarted）",
        result: String = "未测试",
        error: String = "无",
        cleanup: String = "无",
    ): String = buildString {
        appendLine("BLE 可连接 / 可扫描广播测试（第二种模式，独立于对照模式）")
        appendLine("参数（请求值）：$PARAMETERS")
        appendLine("参数（系统 toString / 实际回调）：$actualParams")
        appendLine(exportLine())
        appendLine(budgetLine())
        appendLine("最近结果：$result")
        appendLine("错误：$error")
        appendLine("停止记录：$cleanup")
        appendLine("回调记录（全部带时间戳，启动后 8 秒无 onAdvertisingSetStarted 记为回调超时）：")
        if (callbacks.isEmpty()) appendLine("  （无回调记录）") else callbacks.forEach { appendLine("  $it") }
        appendLine("两种模式对照：")
        combination(plain, mode).lines().forEach { appendLine("  $it") }
        appendLine(explain(mode).hint)
        appendLine(DATA_NOTE)
        append(LIMITS)
    }
}
