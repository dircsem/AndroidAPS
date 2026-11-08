package app.aaps.pump.common.hw.medlink.ble.operations

import android.bluetooth.BluetoothGatt
import app.aaps.pump.common.hw.medlink.ble.MedLinkBLE
import java.util.UUID
import java.util.concurrent.Semaphore

/**
 * Created by dirceu on 12/27/20.
 * Converted from Java: the .java file lived under src/main/kotlin, so javac never compiled it and the
 * class was missing at runtime (NoClassDefFoundError when MedLinkBLE was loaded in tests).
 */
abstract class BLECommOperation {

    @JvmField var timedOut = false
    @JvmField var interrupted = false
    var value: ByteArray? = null
        protected set
    protected var gatt: BluetoothGatt? = null
    protected var operationComplete = Semaphore(0, true)

    // This is to be run on the main thread
    abstract fun execute(comm: MedLinkBLE)

    open fun gattOperationCompletionCallback(uuid: UUID, value: ByteArray) {}

    open val gattOperationTimeout_ms: Int
        get() = 82000
}
