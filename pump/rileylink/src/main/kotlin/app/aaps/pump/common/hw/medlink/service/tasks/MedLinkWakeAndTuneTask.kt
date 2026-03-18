package app.aaps.pump.common.hw.medlink.service.tasks

import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventRefreshButtonState
import javax.inject.Inject

class MedLinkWakeAndTuneTask(activePlugin: ActivePlugin) : MedLinkPumpTask(activePlugin) {

    @Inject lateinit var rxBus: RxBus

    override fun run() {
        rxBus.send(EventRefreshButtonState(false))
        pumpDevice?.setBusy(true)
        pumpDevice?.rileyLinkService?.doTuneUpDevice()
        pumpDevice?.setBusy(false)
        rxBus.send(EventRefreshButtonState(true))
    }
}