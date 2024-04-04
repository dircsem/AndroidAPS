package app.aaps.di

import android.content.Context
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.objects.Instantiator
import app.aaps.core.interfaces.plugin.MedLinkProfileParser
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.pump.BgSync
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.implementation.instantiator.InstantiatorImpl
import app.aaps.implementation.pump.BgSyncImplementation
import app.aaps.implementations.ConfigImpl
import app.aaps.implementations.UiInteractionImpl
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.activities.MedLinkStandardReturn
import info.nightscout.androidaps.plugins.pump.medtronic.data.MedLinkProfileParserImpl
import info.nightscout.androidaps.plugins.pump.medtronic.data.dto.BasalProfile
import info.nightscout.androidaps.plugins.pump.medtronic.defs.MedLinkMedtronicDeviceType

/**
 * Injections needed for [TestApplication]
 */
@Suppress("unused")
@Module(
    includes = [
        TestModule.AppBindings::class
    ]
)
open class TestModule {

    @Provides
    fun providesPlugins(
        config: Config,
        @PluginsListModule.AllConfigs allConfigs: Map<@JvmSuppressWildcards Int, @JvmSuppressWildcards PluginBase>,
        @PluginsListModule.PumpDriver pumpDrivers: Lazy<Map<@JvmSuppressWildcards Int, @JvmSuppressWildcards PluginBase>>,
        @PluginsListModule.NotNSClient notNsClient: Lazy<Map<@JvmSuppressWildcards Int, @JvmSuppressWildcards PluginBase>>,
        @PluginsListModule.APS aps: Lazy<Map<@JvmSuppressWildcards Int, @JvmSuppressWildcards PluginBase>>,
        //@PluginsListModule.Unfinished unfinished: Lazy<Map<@JvmSuppressWildcards Int,  @JvmSuppressWildcards PluginBase>>
    )
        : List<@JvmSuppressWildcards PluginBase> {
        val plugins = allConfigs.toMutableMap()
        if (config.PUMPDRIVERS) plugins += pumpDrivers.get()
        if (config.APS) plugins += aps.get()
        if (!config.NSCLIENT) plugins += notNsClient.get()
        //if (config.isUnfinishedMode()) plugins += unfinished.get()
        return plugins.toList().sortedBy { it.first }.map { it.second }
    }

    @Module
    interface AppBindings {

        @Binds fun bindContext(mainApp: TestApplication): Context
        @Binds fun bindInjector(mainApp: TestApplication): HasAndroidInjector
        @Binds fun bindConfigInterface(config: ConfigImpl): Config

        @Binds fun bindActivityNames(activityNames: UiInteractionImpl): UiInteraction
        @Binds fun bindInstantiator(instantiatorImpl: InstantiatorImpl): Instantiator

        @Binds fun bindBGSync(bgSyncImplementation: BgSyncImplementation): BgSync

        @Binds fun bindMedLinkProfileParser(medLinkProfileParser: MedLinkProfileParserImpl<MedLinkStandardReturn<MedLinkMedtronicDeviceType>>)
            : MedLinkProfileParser<MedLinkStandardReturn<MedLinkMedtronicDeviceType>, BasalProfile>

    }
}

