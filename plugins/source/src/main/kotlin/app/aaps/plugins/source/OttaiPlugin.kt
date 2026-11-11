package app.aaps.plugins.source



import app.aaps.core.data.plugin.PluginType

import app.aaps.core.interfaces.configuration.Config

import app.aaps.core.interfaces.logging.AAPSLogger

import app.aaps.core.interfaces.plugin.PluginDescription

import app.aaps.core.interfaces.resources.ResourceHelper

import app.aaps.core.interfaces.source.BgSource

import app.aaps.core.keys.interfaces.Preferences

import app.aaps.core.ui.compose.icons.IcGenericCgm

import app.aaps.plugins.source.compose.BgSourceComposeContent

import javax.inject.Inject

import javax.inject.Singleton



@Singleton

class OttaiPlugin @Inject constructor(

    rh: ResourceHelper,

    aapsLogger: AAPSLogger,

    preferences: Preferences,

    config: Config,

) : AbstractBgSourcePlugin(

    PluginDescription()

        .mainType(PluginType.BGSOURCE)

        .composeContent { plugin ->

            BgSourceComposeContent(

                title = rh.gs(R.string.ottai_app)

            )

        }

        .icon(IcGenericCgm)

        .pluginName(R.string.ottai_app)

        .preferencesVisibleInSimpleMode(false)

        .description(R.string.description_source_patched_ottai_app),

    ownPreferences = emptyList(),

    aapsLogger, rh, preferences, config

), BgSource


