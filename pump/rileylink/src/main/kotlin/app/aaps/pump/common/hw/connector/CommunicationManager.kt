package app.aaps.pump.common.hw.connector

import app.aaps.core.interfaces.pump.defs.PumpDeviceState
import app.aaps.pump.common.hw.rileylink.ble.data.RLMessage
import app.aaps.pump.common.hw.rileylink.ble.defs.RLMessageType

/**
 * used by medlink
 */
interface CommunicationManager {
    fun createResponseMessage(payload: ByteArray): RLMessage

    fun setPumpDeviceState(pumpDeviceState: PumpDeviceState)

    fun wakeUp(force: Boolean)

    val notConnectedCount: Int

    fun hasTunning(): Boolean

    // FIXME change wakeup
    // TODO we might need to fix this. Maybe make pump awake for shorter time (battery factor for pump) - Andy
    fun wakeUp(duration_minutes: Int, force: Boolean)

    fun setRadioFrequencyForPump(freqMHz: Double)

    fun tuneForDevice(): Double

    fun isValidFrequency(frequency: Double): Boolean

    /**
     * Do device connection, with wakeup
     *
     * @return
     */
    fun tryToConnectToDevice(): Boolean

    fun createPumpMessageContent(type: RLMessageType): ByteArray

    fun quickTuneForPump(startFrequencyMHz: Double): Double
}
