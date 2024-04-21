package info.nightscout.androidaps.plugins.pump.common.di

import dagger.Module
import dagger.android.ContributesAndroidInjector
import info.nightscout.androidaps.plugins.pump.common.dialog.MedLinkBLEConfigActivity
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.MedLinkCommunicationManager
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.ble.MedLinkBLE
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.ble.MedLinkRFSpy
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.ble.command.SendAndListen
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.dialog.MedLinkStatusActivity
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.dialog.MedLinkStatusGeneralFragment
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.dialog.MedLinkStatusHistoryFragment
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.service.MedLinkBluetoothStateReceiver
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.service.MedLinkBroadcastReceiver
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.service.MedLinkService
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.service.tasks.InitializeMedLinkPumpManagerTask
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.service.tasks.MedLinkDiscoverGattServicesTask
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.service.tasks.MedLinkPumpTask
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.service.tasks.MedLinkWakeAndTuneTask
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.service.tasks.ResetMedLinkConfigurationTask
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.service.tasks.ServiceTask

@Module
@Suppress("unused")
abstract class MedLinkModule {
    // @ContributesAndroidInjector abstract fun serviceTaskProvider(): ServiceTask
    @ContributesAndroidInjector abstract fun pumpTaskProvider(): MedLinkPumpTask
    @ContributesAndroidInjector abstract fun discoverGattServicesTaskProvider(): MedLinkDiscoverGattServicesTask
    @ContributesAndroidInjector abstract fun initializePumpManagerTask(): InitializeMedLinkPumpManagerTask
    @ContributesAndroidInjector abstract fun resetMedLinkConfigurationTaskProvider(): ResetMedLinkConfigurationTask
    @ContributesAndroidInjector abstract fun rfSpyProvider(): MedLinkRFSpy
    @ContributesAndroidInjector abstract fun wakeAndTuneTask(): MedLinkWakeAndTuneTask
    @ContributesAndroidInjector
    abstract fun medLinkCommunicationManagerProvider(): MedLinkCommunicationManager
    @ContributesAndroidInjector abstract fun medLinkBLEProvider(): MedLinkBLE


    // @ContributesAndroidInjector abstract fun radioPacket(): RadioPacket
    @ContributesAndroidInjector abstract fun sendAndListen(): SendAndListen

    @ContributesAndroidInjector abstract fun medLinkService(): MedLinkService
    @ContributesAndroidInjector abstract fun medLinkBleConfigActivity(): MedLinkBLEConfigActivity
    @ContributesAndroidInjector abstract fun contributesMedLinkStatusGeneral(): MedLinkStatusGeneralFragment
    @ContributesAndroidInjector abstract fun contributesMedLinkStatusHistoryFragment(): MedLinkStatusHistoryFragment
    @ContributesAndroidInjector abstract fun contributesMedLinkStatusActivity(): MedLinkStatusActivity
    @ContributesAndroidInjector abstract fun contributesMedLinkBroadcastReceiver(): MedLinkBroadcastReceiver
    @ContributesAndroidInjector abstract fun contributesMedLinkBluetoothStateReceiver(): MedLinkBluetoothStateReceiver

}