package com.carassistant.core

import org.junit.Assert.*
import org.junit.Test

/**
 * 「可连接 / 可扫描广播」纯逻辑测试：只用真实逻辑，不空跑。
 *
 * 覆盖口径：回调超时不算成功、非 0 状态码归类并保留数字码、DATA_TOO_LARGE 与
 * FEATURE_UNSUPPORTED 归类不同、两种模式互不污染、默认导出不泄漏载荷字节。
 */
class BleConnectableAdvTest {
    private val started = BleDirection.AdvertiseState.STARTED
    private val notStarted = BleDirection.AdvertiseState.NOT_STARTED
    private val notTested = BleDirection.AdvertiseState.NOT_TESTED

    // ---- 广播内容 ----

    @Test
    fun payloadIsDistinctFromControlModeAndNoPrivateContent() {
        // 对照组是 CA1（43 41 31）；本模式必须是 CA2，否则手机侧分不清哪一组。
        assertArrayEquals(byteArrayOf(0x43, 0x41, 0x32), BleConnectableAdv.PAYLOAD)
        assertFalse(BleConnectableAdv.PAYLOAD.contentEquals(byteArrayOf(0x43, 0x41, 0x31)))
        assertEquals("43 41 32", BleConnectableAdv.PAYLOAD_HEX)
        assertEquals("CA2", BleConnectableAdv.PAYLOAD_TEXT)
        assertEquals(3, BleConnectableAdv.PAYLOAD.size)
        // 固定标签仍需 ≤5 字节（31 字节预算里只留了 3 字节位置）。
        assertTrue(BleConnectableAdv.SCAN_RSP_TAG.length <= 5)
        assertEquals(BleConnectableAdv.SCAN_RSP_TAG.length, BleConnectableAdv.SCAN_RSP_TAG.toByteArray(Charsets.US_ASCII).size)
    }

    @Test
    fun legacyByteBudgetIsActuallyCalculatedAndFits() {
        assertEquals(21, BleConnectableAdv.SERVICE_DATA_AD_BYTES) // 2 + 16 + 3
        assertEquals(3, BleConnectableAdv.FLAGS_AD_BYTES)
        assertEquals(24, BleConnectableAdv.ADV_DATA_BYTES)
        assertEquals(2 + 2 + 3, BleConnectableAdv.SCAN_RSP_AD_BYTES) // AD 头 + 公司 ID + 固定标签
        assertEquals(7, BleConnectableAdv.SCAN_RSP_AD_BYTES)
        assertEquals(31, BleConnectableAdv.COMBINED_BYTES)
        assertTrue(BleConnectableAdv.fitsLegacy())
        assertTrue(BleConnectableAdv.ADV_DATA_BYTES <= BleConnectableAdv.LEGACY_LIMIT)
        assertTrue(BleConnectableAdv.SCAN_RSP_AD_BYTES <= BleConnectableAdv.LEGACY_LIMIT)
        val budget = BleConnectableAdv.budgetLine()
        assertTrue(budget.contains("24/31"))
        assertTrue(budget.contains("7/31"))
        assertTrue(budget.contains("31/31"))
    }

    // ---- 回调超时 ----

    @Test
    fun startupTimeoutIsNeverSuccess() {
        // 8 秒内没有 onAdvertisingSetStarted：必须记「回调超时」，不能算成功。
        val timedOut = BleConnectableAdv.modeState(BleTestSession.Phase.FAILED, startedStatus = null, timedOut = true)
        assertEquals(BleConnectableAdv.ModeState.CALLBACK_TIMEOUT, timedOut)
        assertNotEquals(BleConnectableAdv.ModeState.STARTED, timedOut)
        assertTrue(timedOut.label.contains("超时"))
        assertTrue(timedOut.label.contains("不算成功"))
        // 提交请求后、回调到达前也不能算成功。
        assertEquals(BleConnectableAdv.ModeState.PENDING, BleConnectableAdv.modeState(BleTestSession.Phase.STARTING))
        assertTrue(BleConnectableAdv.ModeState.PENDING.label.contains("不算成功"))
        // 超时同样不能写成「不支持」。
        val combination = BleConnectableAdv.combination(started, timedOut)
        assertTrue(combination.separation.contains("不可判定"))
        assertTrue(combination.conclusion.contains("也不能算车机不支持"))
        assertNotEquals(BleConnectableAdv.Blame.CAPABILITY, BleConnectableAdv.explain(timedOut).blame)
    }

    @Test
    fun failedPhaseWithZeroStatusIsStillFailure() {
        // 失败阶段即使带回 status=0，也不能被写成成功。
        val state = BleConnectableAdv.modeState(BleTestSession.Phase.FAILED, startedStatus = 0)
        assertNotEquals(BleConnectableAdv.ModeState.STARTED, state)
        assertEquals(BleConnectableAdv.ModeState.FAILED_UNKNOWN, state)
        // 只有真正到达 ADVERTISING 才算启动成功。
        assertEquals(BleConnectableAdv.ModeState.STARTED, BleConnectableAdv.modeState(BleTestSession.Phase.ADVERTISING, 0))
        assertEquals(BleConnectableAdv.ModeState.STARTED, BleConnectableAdv.modeState(BleTestSession.Phase.STOP_FAILED, 0))
        assertEquals(BleConnectableAdv.ModeState.NOT_TESTED, BleConnectableAdv.modeState(BleTestSession.Phase.IDLE))
    }

    // ---- 状态码归类 ----

    @Test
    fun nonZeroStatusIsClassifiedAsFailureAndKeepsNumericCode() {
        assertEquals(BleConnectableAdv.ModeState.FAILED_DATA_TOO_LARGE, BleConnectableAdv.classify(1))
        assertEquals(BleConnectableAdv.ModeState.FAILED_TOO_MANY_ADVERTISERS, BleConnectableAdv.classify(2))
        assertEquals(BleConnectableAdv.ModeState.FAILED_ALREADY_STARTED, BleConnectableAdv.classify(3))
        assertEquals(BleConnectableAdv.ModeState.FAILED_INTERNAL_ERROR, BleConnectableAdv.classify(4))
        assertEquals(BleConnectableAdv.ModeState.FAILED_FEATURE_UNSUPPORTED, BleConnectableAdv.classify(5))
        assertEquals(BleConnectableAdv.ModeState.FAILED_UNKNOWN, BleConnectableAdv.classify(99))
        for (code in 1..5) {
            val state = BleConnectableAdv.classify(code)
            assertTrue("code=$code 的标签必须是失败", state.label.startsWith("失败"))
            assertEquals("code=$code 必须归类为失败阶段", state, BleConnectableAdv.modeState(BleTestSession.Phase.FAILED, code))
        }
        // 数字码到名称的映射要能写进报告，便于对日志。
        assertTrue(BleConnectableAdv.statusName(1).contains("DATA_TOO_LARGE"))
        assertTrue(BleConnectableAdv.statusName(2).contains("TOO_MANY_ADVERTISERS"))
        assertTrue(BleConnectableAdv.statusName(5).contains("FEATURE_UNSUPPORTED"))
        assertTrue(BleConnectableAdv.statusName(99).contains("未知"))
    }

    @Test
    fun dataTooLargeAndFeatureUnsupportedAreClassifiedDifferently() {
        val tooLarge = BleConnectableAdv.classify(1)
        val unsupported = BleConnectableAdv.classify(5)
        assertNotEquals(tooLarge, unsupported)
        // 一个是测试数据问题（可重试、不算能力结论），一个是能力结论。
        assertEquals(BleConnectableAdv.Blame.TEST_DATA, BleConnectableAdv.explain(tooLarge).blame)
        assertTrue(BleConnectableAdv.explain(tooLarge).retriable)
        assertEquals(BleConnectableAdv.Blame.CAPABILITY, BleConnectableAdv.explain(unsupported).blame)
        assertFalse(BleConnectableAdv.explain(unsupported).retriable)
        // 组合结论也不同：数据超长不可判定能力，能力不支持才是分离结论。
        val tooLargeCombo = BleConnectableAdv.combination(started, tooLarge)
        assertTrue(tooLargeCombo.separation.contains("测试数据"))
        assertFalse(tooLargeCombo.conclusion.contains("不支持可连接广播"))
        val unsupportedCombo = BleConnectableAdv.combination(started, unsupported)
        assertTrue(unsupportedCombo.separation.contains("分离成立"))
        assertNotEquals(tooLargeCombo.separation, unsupportedCombo.separation)
    }

    @Test
    fun tooManyAdvertisersPointsAtCarLinkNotCapability() {
        val state = BleConnectableAdv.classify(2)
        val explanation = BleConnectableAdv.explain(state)
        assertEquals(BleConnectableAdv.Blame.ENVIRONMENT, explanation.blame)
        assertTrue(explanation.hint.contains("CarLink"))
        assertTrue(explanation.hint.contains("Force stop"))
        assertTrue(explanation.retriable)
        val combination = BleConnectableAdv.combination(started, state)
        assertTrue(combination.separation.contains("环境冲突"))
        assertTrue(combination.nextStep.contains("CarLink"))
        assertTrue(combination.conclusion.contains("不能判定为车机不支持可连接广播"))
        // 内部错误只记未知，不写成能力结论。
        assertEquals(BleConnectableAdv.Blame.SYSTEM, BleConnectableAdv.explain(BleConnectableAdv.classify(4)).blame)
        assertEquals(BleConnectableAdv.Blame.UNKNOWN, BleConnectableAdv.explain(BleConnectableAdv.classify(99)).blame)
    }

    // ---- 两种模式互不污染 ----

    @Test
    fun twoModesKeepIndependentStateAndPayload() {
        // 两个独立门禁 = 两套独立会话，任何一方的阶段都不会带动另一方。
        val plainGate = BleTestSession()
        val connectableGate = BleTestSession()
        val plainToken = plainGate.begin()!!
        val connectableToken = connectableGate.begin()!!
        assertEquals(BleTestSession.Phase.STARTING, plainGate.phase)

        // 可连接模式失败，不应影响已经在广播的对照模式。
        assertTrue(connectableGate.failure(connectableToken))
        assertEquals(BleTestSession.Phase.FAILED, connectableGate.phase)
        assertEquals(BleTestSession.Phase.STARTING, plainGate.phase)
        assertTrue(plainGate.success(plainToken))
        assertEquals(BleTestSession.Phase.ADVERTISING, plainGate.phase)

        val plainEvidence = BleDirection.advertiseState(plainGate.phase)
        val connectableEvidence = BleConnectableAdv.modeState(connectableGate.phase, 1)
        assertEquals(started, plainEvidence)
        assertEquals(BleConnectableAdv.ModeState.FAILED_DATA_TOO_LARGE, connectableEvidence)

        val combination = BleConnectableAdv.combination(plainEvidence, connectableEvidence)
        assertTrue(combination.plain.contains("启动成功"))
        assertTrue(combination.connectable.startsWith("失败"))
        assertTrue(combination.separation.contains("测试数据"))

        // 反向验证：可连接模式成功不会把对照模式的「未测试」变成成功。
        val fresh = BleTestSession()
        val freshToken = fresh.begin()!!
        assertTrue(fresh.success(freshToken))
        val reverse = BleConnectableAdv.combination(notTested, BleConnectableAdv.modeState(fresh.phase))
        assertEquals(notTested.label, reverse.plain)
        assertTrue(reverse.separation.contains("只有可连接模式有启动成功回调"))

        // 载荷也不共用：对照 CA1、可连接 CA2。
        assertFalse(BleConnectableAdv.PAYLOAD.contentEquals(byteArrayOf(0x43, 0x41, 0x31)))
        assertTrue(BleConnectableAdv.PARAMETERS.contains("connectable=true"))
        assertTrue(BleConnectableAdv.PARAMETERS.contains("scannable=true"))
        assertTrue(BleConnectableAdv.PARAMETERS.contains("legacy=true"))
    }

    // ---- 默认导出不泄漏载荷 ----

    @Test
    fun defaultExportHidesPayloadBytesButDetailLineShowsThem() {
        // 界面说明可以写具体字节（用户要在扫描工具里比对）。
        assertTrue(BleConnectableAdv.detailLine().contains("43 41 32"))
        assertTrue(BleConnectableAdv.detailLine().contains("CA2"))
        // 默认导出只给结构与长度。
        val export = BleConnectableAdv.exportLine()
        assertFalse(export.contains("43 41 32"))
        assertFalse(export.contains("CA2"))
        assertFalse(export.contains("41 31"))
        assertTrue(export.contains("0xFFFF"))
        assertFalse(BleConnectableAdv.LIMITS.contains("43 41 32"))
    }

    @Test
    fun reportSectionNeverClaimsPhoneReceivedAndStaysMasked() {
        val section = BleConnectableAdv.exportSection(
            plain = started,
            mode = BleConnectableAdv.classify(5),
            callbacks = listOf("2026-10-29 10:00:00.000 +0800 (+312ms) onAdvertisingSetStarted：status=5（ADVERTISE_FAILED_FEATURE_UNSUPPORTED），txPower=-7dBm，set=已取得"),
            actualParams = "txPower=-7dBm；AdvertisingSetParameters.toString()=interval 400",
            result = "onAdvertisingSetStarted 失败：status=5",
            error = "status=5（ADVERTISE_FAILED_FEATURE_UNSUPPORTED）",
            cleanup = "已提交停止请求，等待 onAdvertisingSetStopped",
        )
        assertTrue(section.contains("可连接 / 可扫描广播测试"))
        assertTrue(section.contains("status=5"))
        assertTrue(section.contains("分离成立"))
        assertTrue(section.contains("31/31"))
        // 导出正文不写载荷/标签字节。
        assertFalse(section.contains("43 41 32"))
        assertFalse(section.contains("CA2"))
        // 口径固定：启动回调 ≠ 手机收到。
        assertTrue(section.contains("≠ 手机能收到"))
        assertFalse(section.contains("手机已收到"))
        assertFalse(section.contains("手机可见"))
        assertFalse(section.contains("BLE 正常"))
        assertFalse(section.contains("BLE 异常"))
    }

    @Test
    fun capabilityFailureNeedsControlGroupToClaimSeparation() {
        // 没有对照组成功记录时，不能直接写「能发非连接、发不了可连接」。
        val noControl = BleConnectableAdv.combination(notStarted, BleConnectableAdv.classify(5))
        assertTrue(noControl.separation.contains("缺对照组"))
        assertFalse(noControl.separation.contains("分离成立"))

        val good = BleConnectableAdv.combination(started, BleConnectableAdv.classify(5))
        assertTrue(good.separation.contains("分离成立"))
        assertTrue(good.nextStep.contains("RSSI")) // 明确不再调 RSSI

        // 两种模式都成功 → 未分离。
        val both = BleConnectableAdv.combination(started, BleConnectableAdv.ModeState.STARTED)
        assertTrue(both.separation.contains("未分离"))

        // 组合结论永远带着「启动回调 ≠ 手机收到」的口径。
        listOf(noControl, good, both).forEach { combination ->
            assertTrue(combination.lines().any { it.startsWith("口径：") && it.contains("≠ 手机能收到") })
        }
    }

    // ---- 启动回调的分发顺序（回归：FEATURE_UNSUPPORTED 不能被报成成功） ----

    @Test
    fun startedCallbackDispatchChecksStatusBeforeAdvancingSession() {
        // 纯分发：只有 status==0 才算系统受理成功。
        assertEquals(BleConnectableAdv.ModeState.STARTED, BleConnectableAdv.startedOutcome(0))
        assertEquals(BleConnectableAdv.ModeState.FAILED_DATA_TOO_LARGE, BleConnectableAdv.startedOutcome(1))
        assertEquals(BleConnectableAdv.ModeState.FAILED_TOO_MANY_ADVERTISERS, BleConnectableAdv.startedOutcome(2))
        assertEquals(BleConnectableAdv.ModeState.FAILED_ALREADY_STARTED, BleConnectableAdv.startedOutcome(3))
        assertEquals(BleConnectableAdv.ModeState.FAILED_INTERNAL_ERROR, BleConnectableAdv.startedOutcome(4))
        assertEquals(BleConnectableAdv.ModeState.FAILED_FEATURE_UNSUPPORTED, BleConnectableAdv.startedOutcome(5))
        assertEquals(BleConnectableAdv.ModeState.FAILED_UNKNOWN, BleConnectableAdv.startedOutcome(99))

        // 按控制器的真实顺序演一遍：先分发，非 STARTED 只走 failure，绝不调 success。
        val failedGate = BleTestSession()
        val failedToken = failedGate.begin()!!
        val failedOutcome = BleConnectableAdv.startedOutcome(5)
        assertNotEquals(BleConnectableAdv.ModeState.STARTED, failedOutcome)
        if (failedOutcome == BleConnectableAdv.ModeState.STARTED) failedGate.success(failedToken) else failedGate.failure(failedToken)
        assertEquals(BleTestSession.Phase.FAILED, failedGate.phase)
        assertEquals(BleConnectableAdv.ModeState.FAILED_FEATURE_UNSUPPORTED, BleConnectableAdv.modeState(failedGate.phase, 5))
        // 结论必须变成「分离成立」，而不是「两种模式都有启动成功回调」。
        val failedCombination = BleConnectableAdv.combination(started, BleConnectableAdv.modeState(failedGate.phase, 5))
        assertTrue(failedCombination.separation.contains("分离成立"))
        assertFalse(failedCombination.separation.contains("未分离"))
        val section = BleConnectableAdv.exportSection(
            plain = started,
            mode = BleConnectableAdv.modeState(failedGate.phase, 5),
            result = "onAdvertisingSetStarted 失败：status=5",
        )
        assertTrue(section.contains("失败：系统报告不支持此广播功能（能力结论）"))

        // status==0 才推进到 ADVERTISING。
        val okGate = BleTestSession()
        val okToken = okGate.begin()!!
        assertEquals(BleConnectableAdv.ModeState.STARTED, BleConnectableAdv.startedOutcome(0))
        assertTrue(okGate.success(okToken))
        assertEquals(BleTestSession.Phase.ADVERTISING, okGate.phase)
        assertEquals(BleConnectableAdv.ModeState.STARTED, BleConnectableAdv.modeState(okGate.phase, 0))
    }

    @Test
    fun nonZeroStatusIsNeverStartedInAnyPhase() {
        // 防御层：只要带回非 0 状态码，无论会话处于哪个阶段都不得返回 STARTED。
        for (phase in BleTestSession.Phase.entries) {
            for (status in intArrayOf(1, 2, 3, 4, 5, 99)) {
                val state = BleConnectableAdv.modeState(phase, status)
                assertNotEquals("phase=$phase status=$status 不得为 STARTED", BleConnectableAdv.ModeState.STARTED, state)
                assertEquals("phase=$phase status=$status 应按状态码归类", BleConnectableAdv.classify(status), state)
            }
        }
        // 上一版漏洞的三个具体点：回调已把会话推进到 ADVERTISING/STOPPED 时残留的非 0 状态码。
        assertEquals(
            BleConnectableAdv.ModeState.FAILED_FEATURE_UNSUPPORTED,
            BleConnectableAdv.modeState(BleTestSession.Phase.ADVERTISING, 5),
        )
        assertEquals(
            BleConnectableAdv.ModeState.FAILED_DATA_TOO_LARGE,
            BleConnectableAdv.modeState(BleTestSession.Phase.ADVERTISING, 1),
        )
        assertEquals(
            BleConnectableAdv.ModeState.FAILED_TOO_MANY_ADVERTISERS,
            BleConnectableAdv.modeState(BleTestSession.Phase.STOPPED, 2),
        )
        // 没有非 0 状态码时，成功阶段仍然算成功。
        assertEquals(BleConnectableAdv.ModeState.STARTED, BleConnectableAdv.modeState(BleTestSession.Phase.ADVERTISING, 0))
        assertEquals(BleConnectableAdv.ModeState.STARTED, BleConnectableAdv.modeState(BleTestSession.Phase.STOPPED, null))
    }

    @Test
    fun untestedModeIsReportedAsUnknownNotAsSupport() {
        val combination = BleConnectableAdv.combination(notTested, BleConnectableAdv.ModeState.NOT_TESTED)
        assertEquals(BleConnectableAdv.ModeState.NOT_TESTED.label, combination.connectable)
        assertTrue(combination.conclusion.contains("不能对「可连接广播」下任何结论"))
        assertFalse(combination.conclusion.contains("不支持"))
        assertTrue(combination.separation.contains("尚未测试"))
        assertEquals(BleConnectableAdv.Blame.NONE, BleConnectableAdv.explain(BleConnectableAdv.ModeState.NOT_TESTED).blame)
        assertEquals(
            BleConnectableAdv.Blame.NONE,
            BleConnectableAdv.explain(BleConnectableAdv.ModeState.STARTED).blame,
        )
    }
}
