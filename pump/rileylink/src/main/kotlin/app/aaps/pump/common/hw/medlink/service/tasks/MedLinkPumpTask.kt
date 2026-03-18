package app.aaps.pump.common.hw.medlink.service.tasks

import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.pump.common.hw.rileylink.service.tasks.ServiceTask
import dagger.android.HasAndroidInjector


open class MedLinkPumpTask(activePlugin: ActivePlugin) : ServiceTask(activePlugin)

