package app.aaps.di

import dagger.hilt.migration.DisableInstallInCheck

import app.aaps.pump.medtronic.comm.MedLinkMedtronicCommunicationManager
import app.aaps.pump.medtronic.comm.ui.MedLinkMedtronicUITaskCp
import dagger.Module
import dagger.android.ContributesAndroidInjector

@Module
// Not installed: was never in the Dagger AppComponent before AAPS 4.0; MedtronicModule binds the same classes
@DisableInstallInCheck
@Suppress("unused")
abstract class MedLinkMedtronicModule {
    @ContributesAndroidInjector
    abstract fun medtronicCommunicationManagerProvider(): MedLinkMedtronicCommunicationManager
    @ContributesAndroidInjector
    abstract fun medtronicUITaskProviderCp(): MedLinkMedtronicUITaskCp
}