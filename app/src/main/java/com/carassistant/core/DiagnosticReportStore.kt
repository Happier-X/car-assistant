package com.carassistant.core

/** In-memory only. A monotonic deadline prevents clock changes extending exposure. */
class DiagnosticReportStore(private val elapsedMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    private var report: String? = null
    private var deadline = 0L

    @Synchronized fun publish(text: String) {
        report = text
        deadline = elapsedMillis() + 10L * 60 * 1000
    }

    @Synchronized fun clear() { report = null }

    @Synchronized fun current(): String? {
        if (elapsedMillis() >= deadline) report = null
        return report
    }
}
