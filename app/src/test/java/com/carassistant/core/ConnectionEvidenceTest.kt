package com.carassistant.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class ConnectionEvidenceTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.use { ConnectionEvidence.parse(ConnectionEvidence.read(it)) }
    @Test fun realTabletRetainsSuccessThenDisconnectAndNewBroadcast() {
        val report = fixture("tablet-evidence.txt")
        val stages = report.events.map { it.stage }
        assertTrue(stages.containsAll(ConnectionEvidence.Stage.entries))
        val cast = report.events.first { it.stage == ConnectionEvidence.Stage.CAST }
        assertTrue(cast.time.contains("19:57:53"))
        val disconnect = report.events.first { it.stage == ConnectionEvidence.Stage.DISCONNECT }
        assertTrue(disconnect.time.contains("19:58:09"))
        assertTrue(cast.line < disconnect.line)
        assertEquals(ConnectionEvidence.Stage.BROADCAST, stages.last())
        assertFalse(report.capped)
    }
    @Test fun realCarOnlyShowsBroadcastAndFirstDivergenceIsRequest() {
        val car = fixture("car-evidence.txt")
        assertEquals(3, car.events.size)
        assertTrue(car.events.all { it.stage == ConnectionEvidence.Stage.BROADCAST })
        val comparison = ConnectionEvidence.compare(fixture("tablet-evidence.txt"), car)
        assertTrue(comparison.contains("首次证据覆盖分歧：手机请求"))
        assertTrue(comparison.contains("startAdvertising 栈片段"))
        // The supplied older car report has no mInterval line: do not invent a shared finding.
        assertFalse(car.anomalies.containsKey("mInterval 被拒"))
    }
    @Test fun headersGenericSuccessAndFailedPhrasesNeverBecomeCast() {
        val report = ConnectionEvidence.parse("状态: 未连接\nonSuccess: 1\ncast connected, set uiState=4\n10-26 20:00:00.000 I/Other(0): cast connected, set uiState=4\n10-26 20:00:01.000 I/UCar_WifiP2pService(0): Create group onFailure.")
        assertTrue(report.events.isEmpty())
    }
    @Test fun duplicatesAndFileOrderSurviveMidnightAndAbsentYear() {
        val a = "12-31 23:59:59.000 D/UCar_ShareLink-BleObserver(0): onSuccess: 1"
        val b = "01-01 00:00:01.000 I/CarlinkHome(0): cast connected, set uiState=4"
        val r = ConnectionEvidence.parse("$a\n$a\n$b")
        assertEquals(listOf(1,2,3), r.events.map { it.line })
        assertEquals(ConnectionEvidence.Stage.CAST, r.events.last().stage)
    }
    @Test fun sharedAnomaliesAreNotFatal() {
        val r = fixture("tablet-evidence.txt")
        assertTrue(ConnectionEvidence.compare(r,r).contains("共有不等于致命"))
        assertTrue(ConnectionEvidence.compare(r,r).contains("mInterval 被拒"))
    }
    @Test fun defaultSummaryNeverContainsRawIdentityOrMarkup() {
        val secret = "10-26 19:57:53.309 I/CarlinkHome(0): cast connected, set uiState=4, account=secret@example.com VIN=SECRET <script>alert(1)</script>"
        val r = ConnectionEvidence.parse(secret)
        assertEquals(secret, r.text)
        assertEquals(1, r.events.size)
        val exported = r.summary("A")
        assertFalse(exported.contains("secret")); assertFalse(exported.contains("<script>")); assertFalse(exported.contains("VIN"))
    }
    @Test fun eventCapIsExplicit() {
        val line = "10-26 19:57:53.309 I/CarlinkHome(0): cast connected, set uiState=4\n"
        val report = ConnectionEvidence.parse(line.repeat(501))
        assertEquals(500, report.events.size)
        assertTrue(report.capped)
    }
    @Test fun utf8AndBomUtf16AreDecodedStrictly() {
        val text = "未知文本：广播"
        fun read(b: ByteArray) = ConnectionEvidence.read(ByteArrayInputStream(b))
        assertEquals(text, read(text.toByteArray()))
        assertEquals(text, read(byteArrayOf(0xff.toByte(),0xfe.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
        assertEquals(text, read(byteArrayOf(0xfe.toByte(),0xff.toByte()) + text.toByteArray(Charsets.UTF_16BE)))
        assertTrue(ConnectionEvidence.parse(text).events.isEmpty())
        assertThrows(Exception::class.java) { read(byteArrayOf(0xc3.toByte(),0x28)) }
        assertThrows(IllegalArgumentException::class.java) { read(byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { read(ByteArray(ConnectionEvidence.MAX_BYTES+1) { 65 }) }
        assertThrows(IllegalArgumentException::class.java) { read("a\n".repeat(12001).toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { read("a".repeat(8193).toByteArray()) }
    }
    @Test fun scanTokensRejectReentryLateCallbacksAndFailedCleanup() {
        val gate = BleScanSession()
        val old = gate.begin()!!
        assertNull(gate.begin()); assertTrue(gate.accepts(old))
        gate.cancel(); assertFalse(gate.accepts(old))
        gate.stopped(false); assertNull(gate.begin())
        gate.stopped(true)
        val next = gate.begin()!!
        assertTrue(next > old); assertFalse(gate.accepts(old)); assertTrue(gate.accepts(next))
    }
    @Test fun previousAdvertiserAndP2pGatesStillRejectUnsafeReentry() {
        val gate = BleTestSession()
        val token = gate.begin()!!
        assertNull(gate.begin())
        assertTrue(gate.cancel(token)); assertFalse(gate.success(token))
        gate.stopped(token, false); assertNull(gate.begin())
        gate.stopped(token, true); assertNotNull(gate.begin())
        val p2p = P2pOperationGate()
        val request = p2p.begin()!!
        assertNull(p2p.begin()); p2p.invalidate(); assertFalse(p2p.finish(request))
        assertNotNull(P2pOperationGate.refusal(null, false, true))
        assertNotNull(P2pOperationGate.refusal(true, false, true))
        assertNotNull(P2pOperationGate.refusal(false, true, true))
        assertNotNull(P2pOperationGate.refusal(false, false, false))
        assertNull(P2pOperationGate.refusal(false, false, true))
    }
    @Test fun optionalPrivateFullReportsAgreeWithSanitizedFixtures() {
        val tabletPath = System.getenv("CAR_ASSISTANT_TABLET_REPORT")
        val carPath = System.getenv("CAR_ASSISTANT_CAR_REPORT")
        org.junit.Assume.assumeTrue(tabletPath != null && carPath != null)
        fun load(path: String) = java.io.File(path).inputStream().use { ConnectionEvidence.parse(ConnectionEvidence.read(it)) }
        val tablet = load(tabletPath!!)
        val car = load(carPath!!)
        assertEquals(fixture("tablet-evidence.txt").events.map { it.stage }, tablet.events.map { it.stage })
        assertEquals(fixture("car-evidence.txt").events.map { it.stage }, car.events.map { it.stage })
        assertTrue(ConnectionEvidence.compare(tablet,car).contains("首次证据覆盖分歧：手机请求"))
        tablet.events.forEach { assertEquals(it.evidence, tablet.text.lines()[it.line - 1]) }
        car.events.forEach { assertEquals(it.evidence, car.text.lines()[it.line - 1]) }
    }
}
