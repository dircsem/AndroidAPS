package app.aaps.pump.common.driver

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.pump.common.driver.ble.PumpBLESelector
import app.aaps.pump.common.driver.db.PumpDriverDatabaseOperation
import app.aaps.pump.common.driver.history.PumpHistoryDataProvider

/**
 * MedLink: PumpPluginAbstract needs a driver configuration since AAPS 3.4.2.2. MedLink binds its service in
 * MedLinkPumpPluginAbstract.onStart and does not use the BLE selector / history provider of the common driver.
 */
class MedLinkPumpDriverConfiguration(private val pumpType: PumpType) : PumpDriverConfiguration {

    override fun getPumpBLESelector(): PumpBLESelector? = null

    override fun getPumpHistoryDataProvider(): PumpHistoryDataProvider? = null

    override fun getPumpDriverDatabaseOperation(): PumpDriverDatabaseOperation =
        throw UnsupportedOperationException("MedLink does not use PumpDriverDatabaseOperation")

    override fun getPumpType(): PumpType = pumpType

    override var logPrefix: String = "MedLinkMedtronicPumpPlugin::"
    override var canHandleDST: Boolean = false
    override var hasService: Boolean = true
}
