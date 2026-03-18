package app.aaps.pump.medtronic.di

import app.aaps.core.interfaces.plugin.MedLinkProfileParser
import app.aaps.core.interfaces.pump.MedLinkPumpStatus
import app.aaps.pump.common.data.PumpStatus
import app.aaps.pump.common.hw.medlink.activities.MedLinkStandardReturn
import app.aaps.pump.common.hw.medlink.defs.MedLinkMedtronicDeviceType
import dagger.Module
import dagger.android.ContributesAndroidInjector
import app.aaps.pump.medtronic.MedLinkMedtronicFragment
import app.aaps.pump.medtronic.comm.MedLinkMedtronicCommunicationManager
import app.aaps.pump.medtronic.comm.ui.MedLinkMedtronicUIComm
import app.aaps.pump.medtronic.service.MedLinkMedtronicService
import app.aaps.pump.medtronic.MedtronicFragment
import app.aaps.pump.medtronic.comm.MedtronicCommunicationManager
import app.aaps.pump.medtronic.comm.ui.MedtronicUIComm
import app.aaps.pump.medtronic.comm.ui.MedtronicUITask
import app.aaps.pump.medtronic.data.MedLinkProfileParserImpl
import app.aaps.pump.medtronic.data.dto.BasalProfile
import app.aaps.pump.medtronic.dialog.MedtronicHistoryActivity
import app.aaps.pump.medtronic.dialog.MedLinkMedtronicHistoryActivity
import app.aaps.pump.medtronic.driver.MedLinkMedtronicPumpStatus
import app.aaps.pump.medtronic.driver.MedtronicPumpStatus
import app.aaps.pump.medtronic.service.RileyLinkMedtronicService
import dagger.Binds

@Module
@Suppress("unused")
abstract class MedtronicModule {

    @ContributesAndroidInjector
    abstract fun contributesMedLinkMedtronicService(): MedLinkMedtronicService

    @ContributesAndroidInjector
    abstract fun contributesMedLinkMedtronicCommunicationManager(): MedLinkMedtronicCommunicationManager
    // @ContributesAndroidInjector
    // abstract fun medLinkMedtronicUITaskProvider(): MedLinkMedtronicUITask
    // @ContributesAndroidInjector
    // abstract fun medLinkMedtronicUITaskCpProvider(): MedLinkMedtronicUITaskCp<Any,Any>

    @ContributesAndroidInjector
    abstract fun medLinkMedtronicUICommProvider(): MedLinkMedtronicUIComm
    @ContributesAndroidInjector
    abstract fun medLinkMedtronicFragmentProvider(): MedLinkMedtronicFragment
    @ContributesAndroidInjector
    abstract fun medLinkMedtronicHistoryActivityProvider(): MedLinkMedtronicHistoryActivity

    @ContributesAndroidInjector
    abstract fun contributesMedtronicHistoryActivity(): MedtronicHistoryActivity
    @ContributesAndroidInjector abstract fun contributesMedtronicFragment(): MedtronicFragment

    @ContributesAndroidInjector
    abstract fun contributesRileyLinkMedtronicService(): RileyLinkMedtronicService

    @ContributesAndroidInjector
    abstract fun medtronicCommunicationManagerProvider(): MedtronicCommunicationManager
    @ContributesAndroidInjector abstract fun medtronicUITaskProvider(): MedtronicUITask
    @ContributesAndroidInjector abstract fun medtronicUICommProvider(): MedtronicUIComm

    @Binds abstract fun bindMedLinkMedtronicPumpStatus(medLinkProfileParser:  MedLinkMedtronicPumpStatus)
            : MedLinkPumpStatus
    @Binds abstract fun bindMedLinkPumpStatus(medLinkProfileParser:  MedtronicPumpStatus)
            : PumpStatus
}