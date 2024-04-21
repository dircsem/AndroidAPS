package info.nightscout.androidaps.plugins.pump.common.hw.medlink.service.tasks

import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventRefreshButtonState
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.ble.RFSpy
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.service.tasks.PumpTask
import javax.inject.Inject

class ResetMedLinkConfigurationTask(injector: HasAndroidInjector) : PumpTask(injector) {

    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var rfSpy: RFSpy

    override fun run() {
        if (!isRileyLinkDevice) return
        rxBus.send(EventRefreshButtonState(false))
        pumpDevice?.setBusy(true)
        rfSpy.resetRileyLinkConfiguration()
        pumpDevice?.setBusy(false)
        rxBus.send(EventRefreshButtonState(true))
    }
}