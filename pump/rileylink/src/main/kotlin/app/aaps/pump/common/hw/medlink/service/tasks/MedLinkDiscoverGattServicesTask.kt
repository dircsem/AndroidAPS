package app.aaps.pump.common.hw.medlink.service.tasks

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.pump.common.hw.rileylink.service.tasks.ServiceTask

import javax.inject.Inject

class MedLinkDiscoverGattServicesTask(activePlugin: ActivePlugin, private val needToConnect: Boolean = false) : ServiceTask(activePlugin) {

    @Inject lateinit var aapsLogger: AAPSLogger

    override fun run() {
        if (needToConnect) pumpDevice?.rileyLinkService?.rileyLinkBLE?.connectGatt()
        pumpDevice?.rileyLinkService?.rileyLinkBLE?.discoverServices()
    }
}