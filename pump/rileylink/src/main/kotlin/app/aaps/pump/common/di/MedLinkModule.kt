package app.aaps.pump.common.di

import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

import app.aaps.pump.common.dialog.MedLinkBLEConfigActivity
import app.aaps.pump.common.hw.medlink.MedLinkCommunicationManager
import app.aaps.pump.common.hw.medlink.ble.MedLinkBLE
import app.aaps.pump.common.hw.medlink.ble.MedLinkRFSpy
import app.aaps.pump.common.hw.medlink.ble.command.SendAndListen
import app.aaps.pump.common.hw.medlink.dialog.MedLinkStatusActivity
import app.aaps.pump.common.hw.medlink.dialog.MedLinkStatusGeneralFragment
import app.aaps.pump.common.hw.medlink.dialog.MedLinkStatusHistoryFragment
import app.aaps.pump.common.hw.medlink.service.MedLinkBluetoothStateReceiver
import app.aaps.pump.common.hw.medlink.service.MedLinkBroadcastReceiver
import app.aaps.pump.common.hw.medlink.service.MedLinkService
import app.aaps.pump.common.hw.medlink.service.MedLinkServiceData
import app.aaps.pump.common.hw.medlink.service.tasks.InitializeMedLinkPumpManagerTask
import app.aaps.pump.common.hw.medlink.service.tasks.MedLinkDiscoverGattServicesTask
import app.aaps.pump.common.hw.medlink.service.tasks.MedLinkPumpTask
import app.aaps.pump.common.hw.medlink.service.tasks.MedLinkWakeAndTuneTask
import app.aaps.pump.common.hw.medlink.service.tasks.ResetMedLinkConfigurationTask
import app.aaps.pump.common.hw.rileylink.service.tasks.InitializePumpManagerTask
import dagger.Module
import dagger.android.ContributesAndroidInjector

@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
abstract class MedLinkModule {
    // @ContributesAndroidInjector abstract fun serviceTaskProvider(): ServiceTask
    @ContributesAndroidInjector
    abstract fun pumpTaskProvider(): MedLinkPumpTask
    @ContributesAndroidInjector
    abstract fun discoverGattServicesTaskProvider(): MedLinkDiscoverGattServicesTask
    @ContributesAndroidInjector
    abstract fun initializePumpManagerTask(): InitializeMedLinkPumpManagerTask
    @ContributesAndroidInjector
    abstract fun resetMedLinkConfigurationTaskProvider(): ResetMedLinkConfigurationTask
    @ContributesAndroidInjector
    abstract fun rfSpyProvider(): MedLinkRFSpy
    @ContributesAndroidInjector
    abstract fun wakeAndTuneTask(): MedLinkWakeAndTuneTask
    @ContributesAndroidInjector
    abstract fun medLinkCommunicationManagerProvider(): MedLinkCommunicationManager
    @ContributesAndroidInjector
    abstract fun medLinkBLEProvider(): MedLinkBLE


    // @ContributesAndroidInjector abstract fun radioPacket(): RadioPacket
    @ContributesAndroidInjector
    abstract fun sendAndListen(): SendAndListen


    @ContributesAndroidInjector
    abstract fun medLinkServiceData(): MedLinkServiceData

    @ContributesAndroidInjector
    abstract fun medLinkService(): MedLinkService
    @ContributesAndroidInjector
    abstract fun medLinkBleConfigActivity(): MedLinkBLEConfigActivity
    @ContributesAndroidInjector
    abstract fun contributesMedLinkStatusGeneral(): MedLinkStatusGeneralFragment
    @ContributesAndroidInjector
    abstract fun contributesMedLinkStatusHistoryFragment(): MedLinkStatusHistoryFragment
    @ContributesAndroidInjector
    abstract fun contributesMedLinkStatusActivity(): MedLinkStatusActivity
    @ContributesAndroidInjector
    abstract fun contributesMedLinkBroadcastReceiver(): MedLinkBroadcastReceiver
    @ContributesAndroidInjector
    abstract fun contributesMedLinkBluetoothStateReceiver(): MedLinkBluetoothStateReceiver
}