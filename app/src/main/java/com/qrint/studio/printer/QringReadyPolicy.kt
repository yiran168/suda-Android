package com.qrint.studio.printer

internal enum class QringReadyMode {
    PREFLIGHT,
    BETWEEN_COPIES,
}

internal enum class QringReadyDecision {
    WAIT,
    READY,
    FAULT,
}

internal data class QringReadyObservation(
    val decision: QringReadyDecision,
    val message: String? = null,
)

/**
 * Debounces the Qring status byte around motor transitions.
 *
 * Some firmware briefly raises cover/paper bits as one label clears the head. Treating one sample
 * as final aborts an otherwise valid multi-copy job. Printing starts only after two stable idle
 * samples, while a media fault must remain identical for several samples before it is accepted.
 */
internal class QringReadyGate(
    private val mode: QringReadyMode,
    private val idleConfirmations: Int = 2,
    private val silentPollLimit: Int = 2,
) {
    private var idleSamples = 0
    private var silentSamples = 0
    private var faultSamples = 0
    private var lastFault: String? = null
    fun observe(status: HardwareStatus?): QringReadyObservation {
        if (status == null) {
            idleSamples = 0
            silentSamples += 1
            return if (silentSamples >= silentPollLimit) {
                QringReadyObservation(QringReadyDecision.READY)
            } else {
                QringReadyObservation(QringReadyDecision.WAIT)
            }
        }
        silentSamples = 0

        // Heat is a motor-state condition, not a media-placement error. Keep waiting for it to
        // clear instead of starting another label while the firmware protects the print head.
        if (status.overheat) {
            idleSamples = 0
            resetFault()
            return QringReadyObservation(QringReadyDecision.WAIT)
        }

        val fault = QringProtocol.blockingFault(status)
        if (fault != null) {
            idleSamples = 0
            if (fault == lastFault) faultSamples += 1 else {
                lastFault = fault
                faultSamples = 1
            }
            val confirmations = when (mode) {
                QringReadyMode.PREFLIGHT -> 2
                QringReadyMode.BETWEEN_COPIES -> 4
            }
            return if (faultSamples >= confirmations) {
                QringReadyObservation(QringReadyDecision.FAULT, fault)
            } else {
                QringReadyObservation(QringReadyDecision.WAIT)
            }
        }
        resetFault()

        if (status.printing) {
            idleSamples = 0
            return QringReadyObservation(QringReadyDecision.WAIT)
        }

        idleSamples += 1
        return if (idleSamples >= idleConfirmations) {
            QringReadyObservation(QringReadyDecision.READY)
        } else {
            QringReadyObservation(QringReadyDecision.WAIT)
        }
    }

    private fun resetFault() {
        lastFault = null
        faultSamples = 0
    }

}
