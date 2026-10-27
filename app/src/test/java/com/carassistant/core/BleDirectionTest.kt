package com.carassistant.core

import org.junit.Assert.*
import org.junit.Test

class BleDirectionTest {
    private val started = BleDirection.AdvertiseState.STARTED
    private val notStarted = BleDirection.AdvertiseState.NOT_STARTED
    private val notTested = BleDirection.AdvertiseState.NOT_TESTED

    private fun obs(
        outcome: BleDirection.PhoneOutcome,
        model: String = "",
        tool: String = "",
        observedAt: String = "",
        note: String = "",
    ) = BleDirection.PhoneObservation.of(outcome, model, tool, observedAt, note)

    private fun verdict(
        advertise: BleDirection.AdvertiseState = notTested,
        observation: BleDirection.PhoneObservation = obs(BleDirection.PhoneOutcome.UNTESTED),
        scan: BleDirection.OwnScan = BleDirection.OwnScan(),
    ) = BleDirection.verdict(advertise, observation, scan)

    // ---- 方向结论 ----

    @Test fun nothingRegisteredIsUnknownAndNeverNormalOrAbnormal() {
        val v = verdict()
        assertEquals(BleDirection.SendState.UNKNOWN, v.send)
        assertEquals(BleDirection.ReceiveState.UNKNOWN, v.receive)
        val text = v.lines().joinToString("\n")
        assertTrue(text.contains("未知"))
        // 结论标签绝不能变成「BLE 正常 / 异常」这种笼统结论。
        assertFalse(v.send.label.contains("正常"))
        assertFalse(v.receive.label.contains("正常"))
        assertFalse(v.send.label.contains("异常"))
        assertFalse(v.receive.label.contains("异常"))
    }

    @Test fun onlyBroadcastCallbackWithoutPhoneRegistrationStaysUnknown() {
        // 本机启动回调成功，但用户从没登记手机侧结果 —— 发送方向仍然只能是未知。
        val v = verdict(advertise = started)
        assertEquals(BleDirection.SendState.UNKNOWN, v.send)
        assertTrue(v.sendReason.missing.contains("没有登记"))
        assertTrue(v.sendReason.basis.contains("启动成功回调"))
        assertEquals(BleDirection.ReceiveState.UNKNOWN, v.receive)
    }

    @Test fun phoneSideReportedSeenIsSeenButKeepsSelfReportWording() {
        val v = verdict(advertise = started, observation = obs(BleDirection.PhoneOutcome.SEEN))
        assertEquals(BleDirection.SendState.SEEN, v.send)
        assertTrue(v.send.label.contains("自述"))
        assertTrue(v.sendReason.basis.contains("扫到"))
        assertTrue(v.sendReason.notes.isEmpty())
    }

    @Test fun phoneSideReportedNotSeenIsOnlyConclusiveWithSuccessfulCallback() {
        val v = verdict(advertise = started, observation = obs(BleDirection.PhoneOutcome.NOT_SEEN))
        assertEquals(BleDirection.SendState.NOT_SEEN, v.send)
        assertTrue(v.sendReason.basis.contains("没扫到"))
        // 启动回调不等于无线信号真的发出，必须留这句话。
        assertTrue(v.sendReason.notes.any { it.contains("不保证") })
    }

    @Test fun notSeenWithoutBroadcastRecordIsUnknownNotAFailureClaim() {
        val v = verdict(advertise = notStarted, observation = obs(BleDirection.PhoneOutcome.NOT_SEEN))
        assertEquals(BleDirection.SendState.UNKNOWN, v.send)
        assertTrue(v.sendReason.missing.contains("启动成功"))
        assertFalse(v.send.label.contains("未扫到"))
    }

    @Test fun payloadMismatchIsItsOwnConclusionNotSeen() {
        val v = verdict(advertise = started, observation = obs(BleDirection.PhoneOutcome.WRONG_PAYLOAD))
        assertEquals(BleDirection.SendState.PAYLOAD_MISMATCH, v.send)
        assertNotEquals(BleDirection.SendState.SEEN, v.send)
        assertTrue(v.sendReason.notes.any { it.contains("CA1") })
    }

    @Test fun seenWithoutLocalCallbackKeepsSelfReportAndFlagsConflict() {
        val v = verdict(advertise = notStarted, observation = obs(BleDirection.PhoneOutcome.SEEN))
        assertEquals(BleDirection.SendState.SEEN, v.send)
        assertTrue(v.sendReason.notes.any { it.contains("对不上") })
    }

    @Test fun receiveDirectionDependsOnlyOnOwnScan() {
        val observed = verdict(scan = BleDirection.OwnScan(attempted = true, rows = 3))
        assertEquals(BleDirection.ReceiveState.OBSERVED, observed.receive)
        assertTrue(observed.receiveReason.basis.contains("3 条"))
        // 手机侧没登记时，接收方向的证据不能填补发送方向。
        assertEquals(BleDirection.SendState.UNKNOWN, observed.send)

        val empty = verdict(scan = BleDirection.OwnScan(attempted = true, rows = 0, message = "15 秒时限结束"))
        assertEquals(BleDirection.ReceiveState.NOT_OBSERVED, empty.receive)
        assertTrue(empty.receiveReason.notes.any { it.contains("不能据此判定") })
        // 扫描状态文本要进依据，避免把「没能开始」说成「结果为空」。
        assertTrue(empty.receiveReason.basis.contains("15 秒时限结束"))

        val notScanned = verdict(scan = BleDirection.OwnScan(attempted = false, rows = 0))
        assertEquals(BleDirection.ReceiveState.UNKNOWN, notScanned.receive)
    }

    @Test fun verdictTextAlwaysKeepsBothDirectionsSeparate() {
        val text = verdict(started, obs(BleDirection.PhoneOutcome.SEEN), BleDirection.OwnScan(attempted = true, rows = 2))
            .lines().joinToString("\n")
        assertTrue(text.contains("发送方向（车机→手机）"))
        assertTrue(text.contains("接收方向（设备→车机）"))
        assertTrue(text.contains("不合并"))
        // 车机自扫捕获自家广播不作为发送方向证据，这句必须存在。
        assertTrue(BleDirection.report(started, obs(BleDirection.PhoneOutcome.SEEN), BleDirection.OwnScan(true, 2))
            .contains("偶发捕获不可靠"))
    }

    @Test fun reportFieldsSplitDirectionsAndCarrySelfReportSource() {
        val fields = BleDirection.reportFields(started, obs(BleDirection.PhoneOutcome.NOT_SEEN, note = "在车里试的"),
            BleDirection.OwnScan(attempted = true, rows = 1))
        val send = fields.first { it.label.startsWith("BLE 发送方向") }
        val receive = fields.first { it.label.startsWith("BLE 接收方向") }
        assertEquals(BleDirection.SendState.NOT_SEEN.label, send.value)
        assertEquals(BleDirection.ReceiveState.OBSERVED.label, receive.value)
        val registry = fields.first { it.label.contains("手机侧观察登记") }
        assertTrue(registry.source.contains("自述"))
        assertTrue(registry.value.contains("在车里试的"))
    }

    @Test fun advertiseStateNeverUpgradesPendingRequestToStarted() {
        assertEquals(BleDirection.AdvertiseState.STARTED, BleDirection.advertiseState(BleTestSession.Phase.ADVERTISING))
        assertEquals(BleDirection.AdvertiseState.STARTED, BleDirection.advertiseState(BleTestSession.Phase.STOPPED))
        assertEquals(BleDirection.AdvertiseState.NOT_STARTED, BleDirection.advertiseState(BleTestSession.Phase.STARTING))
        assertEquals(BleDirection.AdvertiseState.NOT_STARTED, BleDirection.advertiseState(BleTestSession.Phase.FAILED))
        assertEquals(BleDirection.AdvertiseState.NOT_TESTED, BleDirection.advertiseState(BleTestSession.Phase.IDLE))
    }

    // ---- 登记字段校验 ----

    @Test fun registrationTextIsSingleLineStrippedAndCapped() {
        val dirty = "Redmi\nK70\t\u0000\u0007" + "长".repeat(500)
        val model = BleDirection.PhoneObservation.text(dirty, BleDirection.PhoneObservation.MAX_MODEL)
        assertEquals(BleDirection.PhoneObservation.MAX_MODEL, model.length)
        assertFalse(model.contains("\n"))
        assertFalse(model.contains("\t"))
        assertFalse(model.contains("\u0000"))
        assertEquals("Redmi K70", BleDirection.PhoneObservation.text("Redmi K70", 40))
        assertEquals("", BleDirection.PhoneObservation.text("   ", 40))

        val o = obs(BleDirection.PhoneOutcome.SEEN, model = "a".repeat(100), tool = "b".repeat(100),
            observedAt = "c".repeat(100), note = "d".repeat(400))
        assertEquals(BleDirection.PhoneObservation.MAX_MODEL, o.model.length)
        assertEquals(BleDirection.PhoneObservation.MAX_TOOL, o.tool.length)
        assertEquals(BleDirection.PhoneObservation.MAX_TIME, o.observedAt.length)
        assertEquals(BleDirection.PhoneObservation.MAX_NOTE, o.note.length)
    }

    @Test fun defaultRegistrationStartsUntestedAndEmpty() {
        val o = BleDirection.PhoneObservation()
        assertEquals(BleDirection.PhoneOutcome.UNTESTED, o.outcome)
        assertFalse(o.recorded)
        assertTrue(BleDirection.exportLines(o).all { it.contains("未填写") || it.contains("未测试") })
        assertTrue(obs(BleDirection.PhoneOutcome.SEEN).recorded)
    }

    @Test fun defaultExportHidesMacEmailAndVinLikeTokensButKeepsTheRest() {
        val note = "手机 MAC AA:BB:CC:DD:EE:01，账号 demo@example.com，VIN LSGKB54E1PA123456，其余正常"
        val lines = BleDirection.exportLines(obs(BleDirection.PhoneOutcome.SEEN, note = note)).joinToString("\n")
        assertFalse(lines.contains("AA:BB:CC:DD:EE:01"))
        assertFalse(lines.contains("demo@example.com"))
        assertFalse(lines.contains("LSGKB54E1PA123456"))
        assertTrue(lines.contains("已隐藏 MAC"))
        assertTrue(lines.contains("其余正常"))
    }

    @Test fun exportKeepsUntrustedNoteAsPlainTextWithoutExecution() {
        val payload = "<script>alert(1)</script>; rm -rf /"
        val lines = BleDirection.exportLines(obs(BleDirection.PhoneOutcome.WRONG_PAYLOAD, note = payload))
        // 原样当文本记录（不改写用户内容），但绝不解释成指令/HTML 结构。
        assertTrue(lines.any { it.contains("<script>") })
        assertTrue(BleDirection.report(notStarted, obs(BleDirection.PhoneOutcome.WRONG_PAYLOAD, note = payload), BleDirection.OwnScan())
            .contains("用户自述"))
    }
}
