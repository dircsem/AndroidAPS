package app.aaps.pump.medtronic.di

import app.aaps.core.interfaces.di.PumpDriver
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.pump.medtronic.MedtronicPumpPlugin
import app.aaps.pump.medtronic.MedLinkMedtronicPumpPlugin
import app.aaps.pump.medtronic.comm.MedtronicCommunicationManager
import app.aaps.pump.medtronic.comm.ui.MedtronicUIComm
import app.aaps.pump.medtronic.comm.ui.MedtronicUITask
import app.aaps.pump.medtronic.service.RileyLinkMedtronicService
import dagger.Binds
import dagger.Module
import dagger.android.ContributesAndroidInjector
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntKey
import dagger.multibindings.IntoMap
import app.aaps.core.interfaces.plugin.MedLinkProfileParser
import app.aaps.core.interfaces.pump.MedLinkPumpStatus
import app.aaps.pump.common.data.PumpStatus
import app.aaps.pump.common.hw.medlink.activities.MedLinkStandardReturn
import app.aaps.pump.common.hw.medlink.defs.MedLinkMedtronicDeviceType
import app.aaps.pump.medtronic.MedLinkMedtronicFragment
import app.aaps.pump.medtronic.comm.MedLinkMedtronicCommunicationManager
import app.aaps.pump.medtronic.comm.ui.MedLinkMedtronicUIComm
import app.aaps.pump.medtronic.service.MedLinkMedtronicService
import app.aaps.pump.medtronic.data.MedLinkProfileParserImpl
import app.aaps.pump.medtronic.data.dto.BasalProfile
import app.aaps.pump.medtronic.dialog.MedLinkMedtronicHistoryActivity
import app.aaps.pump.medtronic.driver.MedLinkMedtronicPumpStatus
import app.aaps.pump.medtronic.driver.MedtronicPumpStatus

@Module
@InstallIn(SingletonComponent::class)
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
    abstract fun contributesRileyLinkMedtronicService(): RileyLinkMedtronicService

    @ContributesAndroidInjector
    abstract fun medtronicCommunicationManagerProvider(): MedtronicCommunicationManager
    @ContributesAndroidInjector abstract fun medtronicUITaskProvider(): MedtronicUITask
    @ContributesAndroidInjector abstract fun medtronicUICommProvider(): MedtronicUIComm

    @Binds abstract fun bindMedLinkMedtronicPumpStatus(medLinkProfileParser:  MedLinkMedtronicPumpStatus)
            : MedLinkPumpStatus
    @Binds abstract fun bindMedLinkPumpStatus(medLinkProfileParser:  MedtronicPumpStatus)
            : PumpStatus

    // Pump plugin registration — @IntKey range 1000–1200, see PluginsListModule for overview
    @Binds
    @PumpDriver
    @IntoMap
    @IntKey(1090)
    abstract fun bindMedtronicPumpPlugin(plugin: MedtronicPumpPlugin): PluginBase

    // MedLink Medtronic pump (Veo through the MedLink) — @IntKey range 1000–1200
    @Binds
    @PumpDriver
    @IntoMap
    @IntKey(1091)
    abstract fun bindMedLinkMedtronicPumpPlugin(plugin: MedLinkMedtronicPumpPlugin): PluginBase
}