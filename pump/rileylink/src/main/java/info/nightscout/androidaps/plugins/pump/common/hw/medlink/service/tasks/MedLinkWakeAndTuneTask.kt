package info.nightscout.androidaps.plugins.pump.common.hw.medlink.service.tasks

import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventRefreshButtonState
import dagger.android.HasAndroidInjector
import javax.inject.Inject

class MedLinkWakeAndTuneTask(injector: HasAndroidInjector) : MedLinkPumpTask(injector) {

    @Inject lateinit var rxBus: RxBus

    override fun run() {
        rxBus.send(EventRefreshButtonState(false))
        pumpDevice?.setBusy(true)
        pumpDevice?.rileyLinkService?.doTuneUpDevice()
        pumpDevice?.setBusy(false)
        rxBus.send(EventRefreshButtonState(true))
    }
}