package app.aaps.di

import app.aaps.pump.medtronic.comm.MedLinkMedtronicCommunicationManager
import app.aaps.pump.medtronic.comm.ui.MedLinkMedtronicUITaskCp
import dagger.Module
import dagger.android.ContributesAndroidInjector

@Module
@Suppress("unused")
abstract class MedLinkMedtronicModule {
    @ContributesAndroidInjector
    abstract fun medtronicCommunicationManagerProvider(): MedLinkMedtronicCommunicationManager
    @ContributesAndroidInjector
    abstract fun medtronicUITaskProviderCp(): MedLinkMedtronicUITaskCp
}