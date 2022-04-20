package info.nightscout.androidaps.plugins.general.overview

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AnimationDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnLongClickListener
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.text.toSpanned
import androidx.recyclerview.widget.LinearLayoutManager
import com.jjoe64.graphview.GraphView
import dagger.android.HasAndroidInjector
import dagger.android.support.DaggerFragment
import info.nightscout.androidaps.Constants
import info.nightscout.androidaps.R
import info.nightscout.androidaps.data.ProfileSealed
import info.nightscout.androidaps.database.AppRepository
import info.nightscout.androidaps.database.entities.UserEntry.Action
import info.nightscout.androidaps.database.entities.UserEntry.Sources
import info.nightscout.androidaps.database.interfaces.end
import info.nightscout.androidaps.databinding.OverviewFragmentBinding
import info.nightscout.androidaps.dialogs.*
import info.nightscout.androidaps.events.*
import info.nightscout.androidaps.extensions.directionToIcon
import info.nightscout.androidaps.extensions.runOnUiThread
import info.nightscout.androidaps.extensions.toVisibility
import info.nightscout.androidaps.extensions.valueToUnitsString
import info.nightscout.androidaps.interfaces.*
import info.nightscout.androidaps.logging.UserEntryLogger
import info.nightscout.androidaps.plugins.aps.loop.events.EventNewOpenLoopNotification
import info.nightscout.androidaps.plugins.aps.openAPSSMB.DetermineBasalResultSMB
import info.nightscout.androidaps.plugins.bus.RxBus
import info.nightscout.androidaps.plugins.configBuilder.ConstraintChecker
import info.nightscout.androidaps.plugins.constraints.bgQualityCheck.BgQualityCheckPlugin
import info.nightscout.androidaps.plugins.general.automation.AutomationPlugin
import info.nightscout.androidaps.plugins.general.nsclient.data.NSDeviceStatus
import info.nightscout.androidaps.plugins.general.overview.activities.QuickWizardListActivity
import info.nightscout.androidaps.plugins.general.overview.events.*
import info.nightscout.androidaps.plugins.general.overview.graphData.GraphData
import info.nightscout.androidaps.plugins.general.overview.notifications.NotificationStore
import info.nightscout.androidaps.plugins.general.wear.events.EventWearInitiateAction
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.GlucoseStatusProvider
import info.nightscout.androidaps.plugins.pump.common.defs.PumpType
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.defs.MedLinkPumpDevice
import info.nightscout.androidaps.plugins.pump.omnipod.eros.OmnipodErosPumpPlugin
import info.nightscout.androidaps.plugins.source.DexcomPlugin
import info.nightscout.androidaps.plugins.source.MedLinkPlugin
import info.nightscout.androidaps.plugins.source.XdripPlugin
import info.nightscout.androidaps.skins.SkinProvider
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.androidaps.utils.DefaultValueHelper
import info.nightscout.androidaps.utils.FabricPrivacy
import info.nightscout.androidaps.utils.ToastUtils
import info.nightscout.androidaps.utils.TrendCalculator
import info.nightscout.androidaps.utils.alertDialogs.OKDialog
import info.nightscout.androidaps.utils.buildHelper.BuildHelper
import info.nightscout.androidaps.utils.protection.ProtectionCheck
import info.nightscout.androidaps.utils.resources.ResourceHelper
import info.nightscout.androidaps.utils.rx.AapsSchedulers
import info.nightscout.androidaps.utils.ui.SingleClickButton
import info.nightscout.androidaps.utils.ui.UIRunnable
import info.nightscout.androidaps.utils.wizard.QuickWizard
import info.nightscout.shared.logging.AAPSLogger
import info.nightscout.shared.sharedPreferences.SP
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.min
import info.nightscout.androidaps.data.IobTotal
import info.nightscout.androidaps.data.MealData
import info.nightscout.androidaps.database.entities.Bolus
import info.nightscout.androidaps.utils.*
import info.nightscout.androidaps.utils.stats.TirCalculator
import info.nightscout.androidaps.utils.T



class OverviewFragment : DaggerFragment(), View.OnClickListener, OnLongClickListener {

    @Inject lateinit var injector: HasAndroidInjector
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var sp: SP
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var defaultValueHelper: DefaultValueHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var constraintChecker: ConstraintChecker
    @Inject lateinit var statusLightHandler: StatusLightHandler
    @Inject lateinit var nsDeviceStatus: NSDeviceStatus
    @Inject lateinit var loop: Loop
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var dexcomPlugin: DexcomPlugin
    @Inject lateinit var medLinkPlugin: MedLinkPlugin
    @Inject lateinit var medLinkMediator: MedLinkPlugin.MedLinkMediator
    @Inject lateinit var dexcomMediator: DexcomPlugin.DexcomMediator
    @Inject lateinit var xdripPlugin: XdripPlugin
    @Inject lateinit var notificationStore: NotificationStore
    @Inject lateinit var quickWizard: QuickWizard
    @Inject lateinit var buildHelper: BuildHelper
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var overviewMenus: OverviewMenus
    @Inject lateinit var skinProvider: SkinProvider
    @Inject lateinit var trendCalculator: TrendCalculator
    @Inject lateinit var config: Config
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var repository: AppRepository
    @Inject lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Inject lateinit var overviewData: OverviewData
    @Inject lateinit var overviewPlugin: OverviewPlugin
    @Inject lateinit var automationPlugin: AutomationPlugin
    @Inject lateinit var bgQualityCheckPlugin: BgQualityCheckPlugin


    private val disposable = CompositeDisposable()
    public val millsToThePast = T.hours(4).msecs()
    private var smallWidth = false
    private var smallHeight = false
    private lateinit var dm: DisplayMetrics
    private var axisWidth: Int = 0
    private lateinit var refreshLoop: Runnable
    private var handler = Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)

    private val secondaryGraphs = ArrayList<GraphView>()
    private val secondaryGraphsLabel = ArrayList<TextView>()

    private var carbAnimation: AnimationDrawable? = null
    private var insulinAnimation: AnimationDrawable? = null

    private var _binding: OverviewFragmentBinding? = null
    private var lastBolusNormalTime: Long = 0

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        OverviewFragmentBinding.inflate(inflater, container, false).also {
            _binding = it
            //check screen width
            dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                activity?.display?.getRealMetrics(dm)
            else
                activity?.windowManager?.defaultDisplay?.getMetrics(dm)
        }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // pre-process landscape mode
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels
        smallWidth = screenWidth <= Constants.SMALL_WIDTH
        smallHeight = screenHeight <= Constants.SMALL_HEIGHT
        val landscape = screenHeight < screenWidth

        skinProvider.activeSkin().preProcessLandscapeOverviewLayout(dm, binding, landscape, rh.gb(R.bool.isTablet), smallHeight)
        binding.nsclientLayout.visibility = config.NSCLIENT.toVisibility()

        binding.notifications.setHasFixedSize(false)
        binding.notifications.layoutManager = LinearLayoutManager(view.context)
        axisWidth = if (dm.densityDpi <= 120) 3 else if (dm.densityDpi <= 160) 10 else if (dm.densityDpi <= 320) 35 else if (dm.densityDpi <= 420) 50 else if (dm.densityDpi <= 560) 70 else 80
        binding.graphsLayout.bgGraph.gridLabelRenderer?.gridColor = rh.gac(context, R.attr.graphgrid)
        binding.graphsLayout.bgGraph.gridLabelRenderer?.reloadStyles()
        binding.graphsLayout.bgGraph.gridLabelRenderer?.labelVerticalWidth = axisWidth
        binding.graphsLayout.bgGraph.layoutParams?.height = rh.dpToPx(skinProvider.activeSkin().mainGraphHeight)

        carbAnimation = binding.infoLayout.carbsIcon.background as AnimationDrawable?
        carbAnimation?.setEnterFadeDuration(1200)
        carbAnimation?.setExitFadeDuration(1200)
        // insulinAnimation = binding.infoLayout.overviewInsulinIcon.background as AnimationDrawable?
        // insulinAnimation?.setEnterFadeDuration(1200)
        // insulinAnimation?.setExitFadeDuration(1200)



        binding.graphsLayout.bgGraph.setOnLongClickListener {
            overviewData.rangeToDisplay += 6
            overviewData.rangeToDisplay = if (overviewData.rangeToDisplay > 24) 6 else overviewData.rangeToDisplay
            sp.putInt(R.string.key_rangetodisplay, overviewData.rangeToDisplay)
            rxBus.send(EventPreferenceChange(rh, R.string.key_rangetodisplay))
            sp.putBoolean(R.string.key_objectiveusescale, true)
            false
        }
        prepareGraphsIfNeeded(overviewMenus.setting.size)
        context?.let { overviewMenus.setupChartMenu(it, binding.graphsLayout.chartMenuButton) }

        binding.activeProfile.setOnClickListener(this)
        binding.activeProfile.setOnLongClickListener(this)
        binding.tempTarget.setOnClickListener(this)
        binding.tempTarget.setOnLongClickListener(this)
        binding.buttonsLayout.acceptTempButton.setOnClickListener(this)
        binding.buttonsLayout.treatmentButton.setOnClickListener(this)
        binding.buttonsLayout.wizardButton.setOnClickListener(this)
        binding.buttonsLayout.calibrationButton.setOnClickListener(this)
        binding.buttonsLayout.cgmButton.setOnClickListener(this)
        binding.buttonsLayout.insulinButton.setOnClickListener(this)
        binding.buttonsLayout.carbsButton.setOnClickListener(this)
        binding.buttonsLayout.quickWizardButton.setOnClickListener(this)
        binding.buttonsLayout.quickWizardButton.setOnLongClickListener(this)
        binding.infoLayout.apsMode.setOnClickListener(this)
        binding.infoLayout.apsMode.setOnLongClickListener(this)
        binding.activeProfile.setOnLongClickListener(this)
    }

    @Synchronized
    override fun onPause() {
        super.onPause()
        disposable.clear()
        handler.removeCallbacksAndMessages(null)
    }

    @Synchronized
    override fun onResume() {
        super.onResume()
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewCalcProgress::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateCalcProgress() }, fabricPrivacy::logException)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewIobCob::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateIobCob() }, fabricPrivacy::logException)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewSensitivity::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateSensitivity() }, fabricPrivacy::logException)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewGraph::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateGraph() }, fabricPrivacy::logException)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewPumpStatus::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updatePumpStatus() }, fabricPrivacy::logException)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewNotification::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateNotification() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventNewBG::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateBg() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventRefreshOverview::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           if (it.now) refreshAll()
                           else scheduleUpdateGUI()
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventAcceptOpenLoopChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventPreferenceChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventNewOpenLoopNotification::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventPumpStatusChanged::class.java)
            .observeOn(aapsSchedulers.main)
            .delay(30, TimeUnit.MILLISECONDS, aapsSchedulers.main)
            .subscribe({
                           overviewData.pumpStatus = it.getStatus(rh)
                           updatePumpStatus()
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventEffectiveProfileSwitchChanged::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateProfile() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventTempTargetChange::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateTemporaryTarget() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventExtendedBolusChange::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateExtendedBolus() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventTempBasalChange::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateTemporaryBasal() }, fabricPrivacy::logException)

        refreshLoop = Runnable {
            refreshAll()
            handler.postDelayed(refreshLoop, 60 * 1000L)
        }
        handler.postDelayed(refreshLoop, 60 * 1000L)

        refreshAll()
        updatePumpStatus()
        updateCalcProgress()
    }

    fun refreshAll() {
        runOnUiThread {
            _binding ?: return@runOnUiThread
            updateBg()
            updateTime()
            updateProfile()
            updateTemporaryBasal()
            updateExtendedBolus()
            updateTemporaryTarget()
            updateIobCob()
            updateSensitivity()
            updateGraph()
            updateNotification()
        }
    }

    @Synchronized
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onClick(v: View) {
        // try to fix  https://fabric.io/nightscout3/android/apps/info.nightscout.androidaps/issues/5aca7a1536c7b23527eb4be7?time=last-seven-days
        // https://stackoverflow.com/questions/14860239/checking-if-state-is-saved-before-committing-a-fragmenttransaction
        if (childFragmentManager.isStateSaved) return
        activity?.let { activity ->
            when (v.id) {
                R.id.treatment_button    -> protectionCheck.queryProtection(
                    activity,
                    ProtectionCheck.Protection.BOLUS,
                    UIRunnable { if (isAdded) TreatmentDialog().show(childFragmentManager, "Overview") })
                R.id.wizard_button       -> protectionCheck.queryProtection(
                    activity,
                    ProtectionCheck.Protection.BOLUS,
                    UIRunnable { if (isAdded) WizardDialog().show(childFragmentManager, "Overview") })
                R.id.insulin_button      -> protectionCheck.queryProtection(
                    activity,
                    ProtectionCheck.Protection.BOLUS,
                    UIRunnable { if (isAdded) InsulinDialog().show(childFragmentManager, "Overview") })
                R.id.quick_wizard_button -> protectionCheck.queryProtection(activity, ProtectionCheck.Protection.BOLUS, UIRunnable { if (isAdded) onClickQuickWizard() })
                R.id.carbs_button        -> protectionCheck.queryProtection(
                    activity,
                    ProtectionCheck.Protection.BOLUS,
                    UIRunnable { if (isAdded) CarbsDialog().show(childFragmentManager, "Overview") })
                R.id.temp_target         -> protectionCheck.queryProtection(
                    activity,
                    ProtectionCheck.Protection.BOLUS,
                    UIRunnable { if (isAdded) TempTargetDialog().show(childFragmentManager, "Overview") })

                R.id.active_profile      -> {
                    ProfileViewerDialog().also { pvd ->
                        pvd.arguments = Bundle().also {
                            it.putLong("time", dateUtil.now())
                            it.putInt("mode", ProfileViewerDialog.Mode.RUNNING_PROFILE.ordinal)
                        }
                    }.show(childFragmentManager, "ProfileViewDialog")
                }

                R.id.cgm_button          -> {
                    if (xdripPlugin.isEnabled())
                        openCgmApp("com.eveningoutpost.dexdrip")
                    else if (dexcomPlugin.isEnabled()) {
                        dexcomMediator.findDexcomPackageName()?.let {
                            openCgmApp(it)
                        }
                            ?: ToastUtils.showToastInUiThread(activity, rh.gs(R.string.dexcom_app_not_installed))
                    }
                }

                R.id.calibration_button  -> {
                    if (xdripPlugin.isEnabled() || medLinkPlugin.isEnabled()) {
                        CalibrationDialog().show(childFragmentManager, "CalibrationDialog")
                    } else if (dexcomPlugin.isEnabled()) {
                        try {
                            dexcomMediator.findDexcomPackageName()?.let {
                                startActivity(
                                    Intent("com.dexcom.cgm.activities.MeterEntryActivity")
                                        .setPackage(it)
                                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                )
                            }
                                ?: ToastUtils.showToastInUiThread(activity, rh.gs(R.string.dexcom_app_not_installed))
                        } catch (e: ActivityNotFoundException) {
                            ToastUtils.showToastInUiThread(activity, rh.gs(R.string.g5appnotdetected))
                        }
                    }
                }

                R.id.accept_temp_button  -> {
                    profileFunction.getProfile() ?: return
                    if ((loop as PluginBase).isEnabled()) {
                        handler.post {
                            val lastRun = loop.lastRun
                            loop.invoke("Accept temp button", false)
                            if (lastRun?.lastAPSRun != null && lastRun.constraintsProcessed?.isChangeRequested == true) {
                                protectionCheck.queryProtection(activity, ProtectionCheck.Protection.BOLUS, UIRunnable {
                                    if (isAdded)
                                        OKDialog.showConfirmation(activity, rh.gs(R.string.tempbasal_label), lastRun.constraintsProcessed?.toSpanned()
                                            ?: "".toSpanned(), {
                                                                      uel.log(Action.ACCEPTS_TEMP_BASAL, Sources.Overview)
                                                                      (context?.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?)?.cancel(Constants.notificationID)
                                                                      rxBus.send(EventWearInitiateAction("cancelChangeRequest"))
                                                                      Thread { loop.acceptChangeRequest() }.run()
                                                                      binding.buttonsLayout.acceptTempButton.visibility = View.GONE
                                                                  })
                                })
                            }
                        }
                    }
                }

                R.id.aps_mode            -> {
                    protectionCheck.queryProtection(activity, ProtectionCheck.Protection.BOLUS, UIRunnable {
                        if (isAdded) LoopDialog().also { dialog ->
                            dialog.arguments = Bundle().also { it.putInt("showOkCancel", 1) }
                        }.show(childFragmentManager, "Overview")
                    })
                }
            }
        }
    }

    private fun openCgmApp(packageName: String) {
        context?.let {
            val packageManager = it.packageManager
            try {
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                    ?: throw ActivityNotFoundException()
                intent.addCategory(Intent.CATEGORY_LAUNCHER)
                it.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                OKDialog.show(it, "", rh.gs(R.string.error_starting_cgm))
            }
        }
    }
    private fun bolusMealLinks(now: Long) = repository.getBolusesDataFromTime(now - millsToThePast, false).blockingGet()

    override fun onLongClick(v: View): Boolean {
        when (v.id) {
            R.id.quick_wizard_button -> {
                startActivity(Intent(v.context, QuickWizardListActivity::class.java))
                return true
            }

            R.id.aps_mode            -> {
                activity?.let { activity ->
                    protectionCheck.queryProtection(activity, ProtectionCheck.Protection.BOLUS, UIRunnable {
                        LoopDialog().also { dialog ->
                            dialog.arguments = Bundle().also { it.putInt("showOkCancel", 0) }
                        }.show(childFragmentManager, "Overview")
                    })
                }
            }

            R.id.temp_target         -> v.performClick()
            R.id.active_profile      -> activity?.let { activity ->
                if (loop.isDisconnected) OKDialog.show(activity, rh.gs(R.string.not_available_full), rh.gs(R.string.smscommunicator_pumpdisconnected))
                else
                    protectionCheck.queryProtection(
                        activity,
                        ProtectionCheck.Protection.BOLUS,
                        UIRunnable { ProfileSwitchDialog().show(childFragmentManager, "ProfileSwitchDialog") })
            }

        }
        return false
    }

    private fun onClickQuickWizard() {
        val actualBg = iobCobCalculator.ads.actualBg()
        val profile = profileFunction.getProfile()
        val profileName = profileFunction.getProfileName()
        val pump = activePlugin.activePump
        val quickWizardEntry = quickWizard.getActive()
        if (quickWizardEntry != null && actualBg != null && profile != null) {
            binding.buttonsLayout.quickWizardButton.visibility = View.VISIBLE
            val wizard = quickWizardEntry.doCalc(profile, profileName, actualBg, true)
            if (wizard.calculatedTotalInsulin > 0.0 && quickWizardEntry.carbs() > 0.0) {
                val carbsAfterConstraints = constraintChecker.applyCarbsConstraints(Constraint(quickWizardEntry.carbs())).value()
                activity?.let {
                    if (abs(wizard.insulinAfterConstraints - wizard.calculatedTotalInsulin) >= pump.pumpDescription.pumpType.determineCorrectBolusStepSize(wizard.insulinAfterConstraints) || carbsAfterConstraints != quickWizardEntry.carbs()) {
                        OKDialog.show(it, rh.gs(R.string.treatmentdeliveryerror), rh.gs(R.string.constraints_violation) + "\n" + rh.gs(R.string.changeyourinput))
                        return
                    }
                    wizard.confirmAndExecute(it)
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun processButtonsVisibility() {
        val lastBG = iobCobCalculator.ads.lastBg()
        val pump = activePlugin.activePump
        val profile = profileFunction.getProfile()
        val profileName = profileFunction.getProfileName()
        val actualBG = iobCobCalculator.ads.actualBg()

        // QuickWizard button
        val quickWizardEntry = quickWizard.getActive()
        if (quickWizardEntry != null && lastBG != null && profile != null && pump.isInitialized() && (!pump.isSuspended() && pump !is MedLinkPumpDevice) && !loop.isDisconnected) {
            binding.buttonsLayout.quickWizardButton.visibility = View.VISIBLE
            val wizard = quickWizardEntry.doCalc(profile, profileName, lastBG, false)
            binding.buttonsLayout.quickWizardButton.text = quickWizardEntry.buttonText() + "\n" + rh.gs(R.string.format_carbs, quickWizardEntry.carbs()) +
                " " + rh.gs(R.string.formatinsulinunits, wizard.calculatedTotalInsulin)
            if (wizard.calculatedTotalInsulin <= 0) binding.buttonsLayout.quickWizardButton.visibility = View.GONE
        } else binding.buttonsLayout.quickWizardButton.visibility = View.GONE

        // **** Temp button ****
        val lastRun = loop.lastRun
        val closedLoopEnabled = constraintChecker.isClosedLoopAllowed()

        val showAcceptButton = !closedLoopEnabled.value() && // Open mode needed
            lastRun != null &&
            (lastRun.lastOpenModeAccept == 0L || lastRun.lastOpenModeAccept < lastRun.lastAPSRun) &&// never accepted or before last result
            lastRun.constraintsProcessed?.isChangeRequested == true // change is requested

        if (showAcceptButton && pump.isInitialized() && (!pump.isSuspended() && pump is MedLinkPumpDevice) && (loop as PluginBase).isEnabled()) {
            binding.buttonsLayout.acceptTempButton.visibility = View.VISIBLE
            binding.buttonsLayout.acceptTempButton.text = "${rh.gs(R.string.setbasalquestion)}\n${lastRun!!.constraintsProcessed}"
        } else {
            binding.buttonsLayout.acceptTempButton.visibility = View.GONE
        }

        // **** Various treatment buttons ****
        binding.buttonsLayout.carbsButton.visibility =
            ((!activePlugin.activePump.pumpDescription.storesCarbInfo || pump.isInitialized() && (!pump.isSuspended() || pump is MedLinkPumpDevice)) && profile != null
                && sp.getBoolean(R.string.key_show_carbs_button, true)).toVisibility()
        binding.buttonsLayout.treatmentButton.visibility = (!loop.isDisconnected && pump.isInitialized() && (!pump.isSuspended() || pump is MedLinkPumpDevice) && profile != null
            && sp.getBoolean(R.string.key_show_treatment_button, false)).toVisibility()
        binding.buttonsLayout.wizardButton.visibility = (!loop.isDisconnected && pump.isInitialized() && (!pump.isSuspended() || pump is MedLinkPumpDevice) && profile != null
            && sp.getBoolean(R.string.key_show_wizard_button, true)).toVisibility()
        binding.buttonsLayout.insulinButton.visibility = (!loop.isDisconnected && pump.isInitialized() && (!pump.isSuspended() || pump is MedLinkPumpDevice) && profile != null
            && sp.getBoolean(R.string.key_show_insulin_button, true)).toVisibility()

        // **** Calibration & CGM buttons ****
        val xDripIsBgSource = xdripPlugin.isEnabled() || medLinkPlugin.isEnabled()
        val dexcomIsSource = dexcomPlugin.isEnabled()
        binding.buttonsLayout.calibrationButton.visibility = (medLinkPlugin.isEnabled() ||xDripIsBgSource && actualBG != null && sp.getBoolean(R.string.key_show_calibration_button, true))
            .toVisibility()
        if (dexcomIsSource) {
            binding.buttonsLayout.cgmButton.setCompoundDrawablesWithIntrinsicBounds(null, rh.gd(R.drawable.ic_byoda), null, null)
            for (drawable in binding.buttonsLayout.cgmButton.compoundDrawables) {
                drawable?.mutate()
                drawable?.colorFilter = PorterDuffColorFilter(rh.gac(context, R.attr.cgmdexColor), PorterDuff.Mode.SRC_IN)
            }
            binding.buttonsLayout.cgmButton.setTextColor(rh.gac(context, R.attr.cgmdexColor))
        } else if (xDripIsBgSource) {
            binding.buttonsLayout.cgmButton.setCompoundDrawablesWithIntrinsicBounds(null, rh.gd(R.drawable.ic_xdrip), null, null)
            for (drawable in binding.buttonsLayout.cgmButton.compoundDrawables) {
                drawable?.mutate()
                drawable?.colorFilter = PorterDuffColorFilter(rh.gac(context, R.attr.cgmxdripColor), PorterDuff.Mode.SRC_IN)
            }
            binding.buttonsLayout.cgmButton.setTextColor(rh.gac(context, R.attr.cgmxdripColor))
        }
        binding.buttonsLayout.cgmButton.visibility = (sp.getBoolean(R.string.key_show_cgm_button, false) && ((xDripIsBgSource && !medLinkPlugin.isEnabled() )|| dexcomIsSource)).toVisibility()

        // Automation buttons
        binding.buttonsLayout.userButtonsLayout.removeAllViews()
        val events = automationPlugin.userEvents()
        if (!loop.isDisconnected && pump.isInitialized() && !pump.isSuspended() && profile != null)
            for (event in events)
                if (event.isEnabled && event.trigger.shouldRun())
                    context?.let { context ->
                        SingleClickButton(context).also {
                            it.setTextColor(rh.gac(context, R.attr.treatmentButton))
                            it.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.5f).also { l ->
                                l.setMargins(0, 0, rh.dpToPx(-4), 0)
                            }
                            it.setCompoundDrawablesWithIntrinsicBounds(null, rh.gd(R.drawable.ic_danar_useropt), null, null)
                            it.text = event.title

                            it.setOnClickListener {
                                OKDialog.showConfirmation(context, rh.gs(R.string.run_question, event.title), { handler.post { automationPlugin.processEvent(event) } })
                            }
                            binding.buttonsLayout.userButtonsLayout.addView(it)
                        }
                    }
        binding.buttonsLayout.userButtonsLayout.visibility = events.isNotEmpty().toVisibility()
    }

    private fun processAps() {
        val pump = activePlugin.activePump

        // aps mode
        val closedLoopEnabled = constraintChecker.isClosedLoopAllowed()

        fun apsModeSetA11yLabel(stringRes: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                binding.infoLayout.apsMode.stateDescription = rh.gs(stringRes)
            } else {
                binding.infoLayout.apsMode.contentDescription = rh.gs(R.string.apsmode_title) + " " + rh.gs(stringRes)
            }
        }

        if (config.APS && pump.pumpDescription.isTempBasalCapable) {
            binding.infoLayout.apsMode.visibility = View.VISIBLE
            binding.infoLayout.timeLayout.visibility = View.GONE
            when {
                (loop as PluginBase).isEnabled() && loop.isSuperBolus                       -> {
                    binding.infoLayout.apsMode.setImageResource(R.drawable.ic_loop_superbolus)
                    apsModeSetA11yLabel(R.string.superbolus)
                    binding.infoLayout.apsModeText.text = dateUtil.age(loop.minutesToEndOfSuspend() * 60000L, true, rh)
                    binding.infoLayout.apsModeText.visibility = View.VISIBLE
                }

                loop.isDisconnected                                                         -> {
                    binding.infoLayout.apsMode.setImageResource(R.drawable.ic_loop_disconnected)
                    apsModeSetA11yLabel(R.string.disconnected)
                    binding.infoLayout.apsModeText.text = dateUtil.age(loop.minutesToEndOfSuspend() * 60000L, true, rh)
                    binding.infoLayout.apsModeText.visibility = View.VISIBLE
                }

                (loop as PluginBase).isEnabled() && loop.isSuspended                        -> {
                    binding.infoLayout.apsMode.setImageResource(R.drawable.ic_loop_paused)
                    apsModeSetA11yLabel(R.string.suspendloop_label)
                    binding.infoLayout.apsModeText.text = dateUtil.age(loop.minutesToEndOfSuspend() * 60000L, true, rh)
                    binding.infoLayout.apsModeText.visibility = View.VISIBLE
                }

                pump.isSuspended()                                                          -> {
                    binding.infoLayout.apsMode.setImageResource(
                        if (pump.model() == PumpType.OMNIPOD_EROS || pump.model() == PumpType.OMNIPOD_DASH) {
                            // For Omnipod, indicate the pump as disconnected when it's suspended.
                            // The only way to 'reconnect' it, is through the Omnipod tab
                            apsModeSetA11yLabel(R.string.disconnected)
                            R.drawable.ic_loop_disconnected
                        } else {
                            apsModeSetA11yLabel(R.string.pump_paused)
                            R.drawable.ic_loop_paused
                        }
                    )
                    binding.infoLayout.apsModeText.visibility = View.GONE
                }

                (loop as PluginBase).isEnabled() && closedLoopEnabled.value() && loop.isLGS -> {
                    binding.infoLayout.apsMode.setImageResource(R.drawable.ic_loop_lgs)
                    apsModeSetA11yLabel(R.string.uel_lgs_loop_mode)
                    binding.infoLayout.apsModeText.visibility = View.GONE
                }

                (loop as PluginBase).isEnabled() && closedLoopEnabled.value()               -> {
                    binding.infoLayout.apsMode.setImageResource(R.drawable.ic_loop_closed)
                    apsModeSetA11yLabel(R.string.closedloop)
                    binding.infoLayout.apsModeText.visibility = View.GONE
                }

                (loop as PluginBase).isEnabled() && !closedLoopEnabled.value()              -> {
                    binding.infoLayout.apsMode.setImageResource(R.drawable.ic_loop_open)
                    apsModeSetA11yLabel(R.string.openloop)
                    binding.infoLayout.apsModeText.visibility = View.GONE
                }

                else                                                                        -> {
                    binding.infoLayout.apsMode.setImageResource(R.drawable.ic_loop_disabled)
                    apsModeSetA11yLabel(R.string.disabledloop)
                    binding.infoLayout.apsModeText.visibility = View.GONE
                }
            }
            // Show variable sensitivity
            val request = loop.lastRun?.request
            if (request is DetermineBasalResultSMB) {
                val isfMgdl = profileFunction.getProfile()?.getIsfMgdl()
                val variableSens = request.variableSens
                if (variableSens != isfMgdl && variableSens != null && isfMgdl != null) {
                    binding.infoLayout.variableSensitivity.text =
                        String.format(
                            Locale.getDefault(), "%1$.1f→%2$.1f",
                            Profile.toUnits(isfMgdl, isfMgdl * Constants.MGDL_TO_MMOLL, profileFunction.getUnits()),
                            Profile.toUnits(variableSens, variableSens * Constants.MGDL_TO_MMOLL, profileFunction.getUnits())
                        )
                    binding.infoLayout.variableSensitivity.visibility = View.VISIBLE
                } else binding.infoLayout.variableSensitivity.visibility = View.GONE
            } else binding.infoLayout.variableSensitivity.visibility = View.GONE
        } else {
            //nsclient
            binding.infoLayout.apsMode.visibility = View.GONE
            binding.infoLayout.apsModeText.visibility = View.GONE
            binding.infoLayout.timeLayout.visibility = View.VISIBLE
        }

        // pump status from ns
        binding.pump.text = nsDeviceStatus.pumpStatus
        binding.pump.setOnClickListener { activity?.let { OKDialog.show(it, rh.gs(R.string.pump), nsDeviceStatus.extendedPumpStatus) } }

        // OpenAPS status from ns
        binding.openaps.text = nsDeviceStatus.openApsStatus
        binding.openaps.setOnClickListener { activity?.let { OKDialog.show(it, rh.gs(R.string.openaps), nsDeviceStatus.extendedOpenApsStatus) } }

        // Uploader status from ns
        binding.uploader.text = nsDeviceStatus.uploaderStatusSpanned
        binding.uploader.setOnClickListener { activity?.let { OKDialog.show(it, rh.gs(R.string.uploader), nsDeviceStatus.extendedUploaderStatus) } }
    }

    private fun prepareGraphsIfNeeded(numOfGraphs: Int) {
        if (numOfGraphs != secondaryGraphs.size - 1) {
            //aapsLogger.debug("New secondary graph count ${numOfGraphs-1}")
            // rebuild needed
            secondaryGraphs.clear()
            secondaryGraphsLabel.clear()
            binding.graphsLayout.iobGraph.removeAllViews()
            for (i in 1 until numOfGraphs) {
                val relativeLayout = RelativeLayout(context)
                relativeLayout.layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

                val graph = GraphView(context)
                graph.layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh.dpToPx(skinProvider.activeSkin().secondaryGraphHeight)).also { it.setMargins(0, rh.dpToPx(15), 0, rh.dpToPx(10)) }
                graph.gridLabelRenderer?.gridColor = rh.gac(context, R.attr.graphgrid)
                graph.gridLabelRenderer?.reloadStyles()
                graph.gridLabelRenderer?.isHorizontalLabelsVisible = false
                graph.gridLabelRenderer?.labelVerticalWidth = axisWidth
                graph.gridLabelRenderer?.numVerticalLabels = 3
                graph.viewport.backgroundColor = rh.gac(context, R.attr.viewPortbackgroundColor)
                relativeLayout.addView(graph)

                val label = TextView(context)
                val layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.setMargins(rh.dpToPx(30), rh.dpToPx(25), 0, 0) }
                layoutParams.addRule(RelativeLayout.ALIGN_PARENT_TOP)
                layoutParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                label.layoutParams = layoutParams
                relativeLayout.addView(label)
                secondaryGraphsLabel.add(label)

                binding.graphsLayout.iobGraph.addView(relativeLayout)
                secondaryGraphs.add(graph)
            }
        }
    }

    var task: Runnable? = null

    private fun scheduleUpdateGUI() {
        class UpdateRunnable : Runnable {

            override fun run() {
                refreshAll()
                task = null
            }
        }
        task?.let { handler.removeCallbacks(it) }
        task = UpdateRunnable()
        task?.let { handler.postDelayed(it, 500) }
    }

    @SuppressLint("SetTextI18n")
    fun updateBg() {
        _binding ?: return
        val units = profileFunction.getUnits()
        binding.infoLayout.bg.text = overviewData.lastBg?.valueToUnitsString(units)
            ?: rh.gs(R.string.notavailable)
        binding.infoLayout.bg.setTextColor(overviewData.lastBgColor(context))
        binding.infoLayout.arrow.setImageResource(trendCalculator.getTrendArrow(overviewData.lastBg).directionToIcon())
        binding.infoLayout.arrow.setColorFilter(overviewData.lastBgColor(context))
        binding.infoLayout.arrow.contentDescription = overviewData.lastBgDescription + " " + rh.gs(R.string.and) + " " + trendCalculator.getTrendDescription(overviewData.lastBg)

        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        if (glucoseStatus != null) {
            binding.infoLayout.deltaLarge.text = Profile.toSignedUnitsString(glucoseStatus.delta, glucoseStatus.delta * Constants.MGDL_TO_MMOLL, units)
            binding.infoLayout.deltaLarge.setTextColor(overviewData.lastBgColor(context))
            binding.infoLayout.delta.text = Profile.toSignedUnitsString(glucoseStatus.delta, glucoseStatus.delta * Constants.MGDL_TO_MMOLL, units)
            binding.infoLayout.avgDelta.text = Profile.toSignedUnitsString(glucoseStatus.shortAvgDelta, glucoseStatus.shortAvgDelta * Constants.MGDL_TO_MMOLL, units)
            binding.infoLayout.longAvgDelta.text = Profile.toSignedUnitsString(glucoseStatus.longAvgDelta, glucoseStatus.longAvgDelta * Constants.MGDL_TO_MMOLL, units)
        } else {
            binding.infoLayout.deltaLarge.text = ""
            binding.infoLayout.delta.text = "Δ " + rh.gs(R.string.notavailable)
            binding.infoLayout.avgDelta.text = ""
            binding.infoLayout.longAvgDelta.text = ""
        }

        // strike through if BG is old
        binding.infoLayout.bg.paintFlags =
            if (!overviewData.isActualBg) binding.infoLayout.bg.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            else binding.infoLayout.bg.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()

        val outDate = (if (!overviewData.isActualBg) rh.gs(R.string.a11y_bg_outdated) else "")
        binding.infoLayout.bg.contentDescription =
            rh.gs(R.string.a11y_blood_glucose) + " " + binding.infoLayout.bg.text.toString() + " " + overviewData.lastBgDescription + " " + outDate

        binding.infoLayout.timeAgo.text = dateUtil.minAgo(rh, overviewData.lastBg?.timestamp)
        binding.infoLayout.timeAgo.contentDescription = dateUtil.minAgoLong(rh, overviewData.lastBg?.timestamp)
        binding.infoLayout.timeAgoShort.text = "(" + dateUtil.minAgoShort(overviewData.lastBg?.timestamp) + ")"

        val qualityIcon = bgQualityCheckPlugin.icon()
        if (qualityIcon != 0) {
            binding.infoLayout.bgQuality.visibility = View.VISIBLE
            binding.infoLayout.bgQuality.setImageResource(qualityIcon)
            binding.infoLayout.bgQuality.contentDescription = rh.gs(R.string.a11y_bg_quality) + " " + bgQualityCheckPlugin.stateDescription()
            binding.infoLayout.bgQuality.setOnClickListener {
                context?.let { context -> OKDialog.show(context, rh.gs(R.string.data_status), bgQualityCheckPlugin.message) }
            }
        } else {
            binding.infoLayout.bgQuality.visibility = View.GONE
        }
    }

    fun updateProfile() {
        _binding ?: return
        val profileBackgroundColor =
            profileFunction.getProfile()?.let {
                if (it is ProfileSealed.EPS) {
                    if (it.value.originalPercentage != 100 || it.value.originalTimeshift != 0L || it.value.originalDuration != 0L)
                        rh.gac(context, R.attr.ribbonWarningColor)
                    else rh.gac(context, R.attr.ribbonDefaultColor)
                } else if (it is ProfileSealed.PS) {
                    rh.gac(context, R.attr.ribbonDefaultColor)
                } else {
                    rh.gac(context, R.attr.ribbonDefaultColor)
                }
            } ?: rh.gac(context, R.attr.ribbonCriticalColor)

        val profileTextColor =
            profileFunction.getProfile()?.let {
                if (it is ProfileSealed.EPS) {
                    if (it.value.originalPercentage != 100 || it.value.originalTimeshift != 0L || it.value.originalDuration != 0L)
                        rh.gac(context, R.attr.ribbonTextWarningColor)
                    else rh.gac(context, R.attr.ribbonTextDefaultColor)
                } else if (it is ProfileSealed.PS) {
                    rh.gac(context, R.attr.ribbonTextDefaultColor)
                } else {
                    rh.gac(context, R.attr.ribbonTextDefaultColor)
                }
            } ?: rh.gac(context, R.attr.ribbonTextDefaultColor)

        binding.activeProfile.text = profileFunction.getProfileNameWithRemainingTime()
        binding.activeProfile.setBackgroundColor(profileBackgroundColor)
        binding.activeProfile.setTextColor(profileTextColor)
    }

    private fun updateTemporaryBasal() {
        _binding ?: return
        binding.infoLayout.baseBasal.text = overviewData.temporaryBasalText(iobCobCalculator)
        binding.infoLayout.baseBasal.setTextColor(overviewData.temporaryBasalColor(context, iobCobCalculator))
        binding.infoLayout.baseBasalIcon.setImageResource(overviewData.temporaryBasalIcon(iobCobCalculator))
        binding.infoLayout.basalLayout.setOnClickListener {
            activity?.let { OKDialog.show(it, rh.gs(R.string.basal), overviewData.temporaryBasalDialogText(iobCobCalculator)) }
        }
    }

    private fun updateExtendedBolus() {
        _binding ?: return
        val pump = activePlugin.activePump
        binding.infoLayout.extendedBolus.text = overviewData.extendedBolusText(iobCobCalculator)
        binding.infoLayout.extendedLayout.setOnClickListener {
            activity?.let { OKDialog.show(it, rh.gs(R.string.extended_bolus), overviewData.extendedBolusDialogText(iobCobCalculator)) }
        }
        binding.infoLayout.extendedLayout.visibility = (iobCobCalculator.getExtendedBolus(dateUtil.now()) != null && !pump.isFakingTempsByExtendedBoluses).toVisibility()
    }

    fun updateTime() {
        _binding ?: return
        binding.infoLayout.time.text = dateUtil.timeString(dateUtil.now())
        // Status lights
        val pump = activePlugin.activePump
        val isPatchPump = pump.pumpDescription.isPatchPump
        binding.statusLightsLayout.apply {
            cannulaOrPatch.setImageResource(if (isPatchPump) R.drawable.ic_patch_pump_outline else R.drawable.ic_cp_age_cannula)
            cannulaOrPatch.contentDescription = rh.gs(if (isPatchPump) R.string.statuslights_patch_pump_age else R.string.statuslights_cannula_age)
            cannulaOrPatch.scaleX = if (isPatchPump) 1.4f else 2f
            cannulaOrPatch.scaleY = cannulaOrPatch.scaleX
            insulinAge.visibility = isPatchPump.not().toVisibility()
            batteryLayout.visibility = (!isPatchPump || pump.pumpDescription.useHardwareLink).toVisibility()
            pbAge.visibility = (pump.pumpDescription.isBatteryReplaceable || pump.isBatteryChangeLoggingEnabled()).toVisibility()
            val useBatteryLevel = (pump.model() == PumpType.OMNIPOD_EROS && pump is OmnipodErosPumpPlugin)
                || (pump.model() != PumpType.ACCU_CHEK_COMBO && pump.model() != PumpType.OMNIPOD_DASH)
            batteryLevel.visibility = useBatteryLevel.toVisibility()
            statusLights.visibility = (sp.getBoolean(R.string.key_show_statuslights, true) || config.NSCLIENT).toVisibility()
        }
        statusLightHandler.updateStatusLights(
            binding.statusLightsLayout.cannulaAge,
            binding.statusLightsLayout.insulinAge,
            binding.statusLightsLayout.reservoirLevel,
            binding.statusLightsLayout.sensorAge,
            null,
            binding.statusLightsLayout.pbAge,
            binding.statusLightsLayout.batteryLevel,
            binding.statusLightsLayout.medlinkBatteryLevel
        )
        processButtonsVisibility()
        processAps()
    }

    fun updateIobCob() {
        _binding ?: return
        binding.infoLayout.iob.text = overviewData.iobText(iobCobCalculator)
        binding.infoLayout.iobLayout.setOnClickListener {
            activity?.let { OKDialog.show(it, rh.gs(R.string.iob), overviewData.iobDialogText(iobCobCalculator)) }
        }

        binding.statusLightsLayout.medlinkSeparator.visibility = (binding.pump is MedLinkPumpDevice).toVisibility()
        binding.statusLightsLayout.pbAge.visibility = (binding.pump !is MedLinkPumpDevice || (sp.getString(R.string
            .key_medlink_battery_info, "Age") == "Age")).toVisibility()
        binding.statusLightsLayout.medlinkBatteryLevel.visibility = (binding.pump !is MedLinkPumpDevice || sp.getString(R.string
            .key_medlink_battery_info, "Age") != "Age").toVisibility()
        statusLightHandler.updateStatusLights(binding.statusLightsLayout.cannulaAge,
                                              binding.statusLightsLayout.insulinAge,
                                              binding.statusLightsLayout.reservoirLevel,
                                              binding.statusLightsLayout.sensorAge,
                                              binding.statusLightsLayout.medlinkBatteryLevel,
                                              binding.statusLightsLayout.pbAge,
                                              binding.statusLightsLayout.batteryLevel,
                                              binding.statusLightsLayout.medlinkBatteryLevel)

        // cob
        var cobText = overviewData.cobInfo(iobCobCalculator).displayText(rh, dateUtil, buildHelper.isEngineeringMode()) ?: rh.gs(R.string.value_unavailable_short)

        val constraintsProcessed = loop.lastRun?.constraintsProcessed
        val lastRun = loop.lastRun
        if (config.APS && constraintsProcessed != null && lastRun != null) {
            if (constraintsProcessed.carbsReq > 0) {
                //only display carbsreq when carbs have not been entered recently
                if (overviewData.lastCarbsTime < lastRun.lastAPSRun) {
                    cobText += " | " + constraintsProcessed.carbsReq + " " + rh.gs(R.string.required)
                }
                if (carbAnimation?.isRunning == false)
                    carbAnimation?.start()
            } else {
                carbAnimation?.stop()
                carbAnimation?.selectDrawable(0)
            }
        }
        binding.infoLayout.cob.text = cobText
    }

    @SuppressLint("SetTextI18n")
    fun updateTemporaryTarget() {
        _binding ?: return
        val units = profileFunction.getUnits()
        val tempTarget = overviewData.temporaryTarget
        if (tempTarget != null) {
            binding.tempTarget.setTextColor(rh.gac(context, R.attr.ribbonTextWarningColor))
            binding.tempTarget.setBackgroundColor(rh.gac(context, R.attr.ribbonWarningColor))
            binding.tempTarget.text = Profile.toTargetRangeString(tempTarget.lowTarget, tempTarget.highTarget, GlucoseUnit.MGDL, units) + " " + dateUtil.untilString(tempTarget.end, rh)
        } else {
            // If the target is not the same as set in the profile then oref has overridden it
            profileFunction.getProfile()?.let { profile ->
                val targetUsed = loop.lastRun?.constraintsProcessed?.targetBG ?: 0.0

                if (targetUsed != 0.0 && abs(profile.getTargetMgdl() - targetUsed) > 0.01) {
                    aapsLogger.debug("Adjusted target. Profile: ${profile.getTargetMgdl()} APS: $targetUsed")
                    binding.tempTarget.text = Profile.toTargetRangeString(targetUsed, targetUsed, GlucoseUnit.MGDL, units)
                    binding.tempTarget.setTextColor(rh.gac(context, R.attr.ribbonTextWarningColor))
                    binding.tempTarget.setBackgroundColor(rh.gac(context, R.attr.tempTargetBackgroundColor))
                } else {
                    binding.tempTarget.setTextColor(rh.gac(context, R.attr.ribbonTextDefaultColor))
                    binding.tempTarget.setBackgroundColor(rh.gac(context, R.attr.ribbonDefaultColor))
                    binding.tempTarget.text = Profile.toTargetRangeString(profile.getTargetLowMgdl(), profile.getTargetHighMgdl(), GlucoseUnit.MGDL, units)
                }
            }
        }
    }

    private fun updateGraph() {
        _binding ?: return
        val pump = activePlugin.activePump
        val graphData = GraphData(injector, binding.graphsLayout.bgGraph, overviewData)
        val menuChartSettings = overviewMenus.setting
        graphData.addInRangeArea(overviewData.fromTime, overviewData.endTime, defaultValueHelper.determineLowLine(), defaultValueHelper.determineHighLine())
        graphData.addBgReadings(menuChartSettings[0][OverviewMenus.CharType.PRE.ordinal])
        if (buildHelper.isDev()) graphData.addBucketedData()
        graphData.addTreatments()
        if (menuChartSettings[0][OverviewMenus.CharType.TREAT.ordinal])
            graphData.addTherapyEvents()
        if (menuChartSettings[0][OverviewMenus.CharType.ACT.ordinal])
            graphData.addActivity(0.8)
        if ((pump.pumpDescription.isTempBasalCapable || config.NSCLIENT) && menuChartSettings[0][OverviewMenus.CharType.BAS.ordinal])
            graphData.addBasals()
        graphData.addTargetLine()
        graphData.addNowLine(dateUtil.now())

        // set manual x bounds to have nice steps
        graphData.setNumVerticalLabels()
        graphData.formatAxis(overviewData.fromTime, overviewData.endTime)

        graphData.performUpdate()

        // 2nd graphs
        prepareGraphsIfNeeded(menuChartSettings.size)
        val secondaryGraphsData: ArrayList<GraphData> = ArrayList()

        val now = System.currentTimeMillis()
        for (g in 0 until min(secondaryGraphs.size, menuChartSettings.size + 1)) {
            val secondGraphData = GraphData(injector, secondaryGraphs[g], overviewData)
            var useABSForScale = false
            var useIobForScale = false
            var useCobForScale = false
            var useDevForScale = false
            var useRatioForScale = false
            var useDSForScale = false
            var useBGIForScale = false
            when {
                menuChartSettings[g + 1][OverviewMenus.CharType.ABS.ordinal]      -> useABSForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.IOB.ordinal]      -> useIobForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.COB.ordinal]      -> useCobForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.DEV.ordinal]      -> useDevForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.BGI.ordinal]      -> useBGIForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.SEN.ordinal]      -> useRatioForScale = true
                menuChartSettings[g + 1][OverviewMenus.CharType.DEVSLOPE.ordinal] -> useDSForScale = true
            }
            val alignDevBgiScale = menuChartSettings[g + 1][OverviewMenus.CharType.DEV.ordinal] && menuChartSettings[g + 1][OverviewMenus.CharType.BGI.ordinal]

            if (menuChartSettings[g + 1][OverviewMenus.CharType.ABS.ordinal]) secondGraphData.addAbsIob(useABSForScale, 1.0)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.IOB.ordinal]) secondGraphData.addIob(useIobForScale, 1.0)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.COB.ordinal]) secondGraphData.addCob(useCobForScale, if (useCobForScale) 1.0 else 0.5)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.DEV.ordinal]) secondGraphData.addDeviations(useDevForScale, 1.0)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.BGI.ordinal]) secondGraphData.addMinusBGI(useBGIForScale, if (alignDevBgiScale) 1.0 else 0.8)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.SEN.ordinal]) secondGraphData.addRatio(useRatioForScale, if (useRatioForScale) 1.0 else 0.8)
            if (menuChartSettings[g + 1][OverviewMenus.CharType.DEVSLOPE.ordinal] && buildHelper.isDev()) secondGraphData.addDeviationSlope(
                useDSForScale,
                if (useDSForScale) 1.0 else 0.8,
                useRatioForScale
            )

            // set manual x bounds to have nice steps
            secondGraphData.formatAxis(overviewData.fromTime, overviewData.endTime)
            secondGraphData.addNowLine(now)
            secondaryGraphsData.add(secondGraphData)
        }
        for (g in 0 until min(secondaryGraphs.size, menuChartSettings.size + 1)) {
            secondaryGraphsLabel[g].text = overviewMenus.enabledTypes(g + 1)
            secondaryGraphs[g].visibility = (
                menuChartSettings[g + 1][OverviewMenus.CharType.ABS.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.IOB.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.COB.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.DEV.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.BGI.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.SEN.ordinal] ||
                    menuChartSettings[g + 1][OverviewMenus.CharType.DEVSLOPE.ordinal]
                ).toVisibility()
            secondaryGraphsData[g].performUpdate()
        }
    }

    private fun updateCalcProgress() {
        _binding ?: return
        binding.progressBar.progress = overviewData.calcProgressPct
        binding.progressBar.visibility = (overviewData.calcProgressPct != 100).toVisibility()
    }

    private fun updateSensitivity() {
        _binding ?: return
        if (sp.getBoolean(R.string.key_openapsama_useautosens, false) && constraintChecker.isAutosensModeEnabled().value()) {
            binding.infoLayout.sensitivityIcon.setImageResource(R.drawable.ic_swap_vert_black_48dp_green)
        } else {
            binding.infoLayout.sensitivityIcon.setImageResource(R.drawable.ic_x_swap_vert)
        }

        binding.infoLayout.sensitivity.text =
            overviewData.lastAutosensData(iobCobCalculator)?.let { autosensData ->
                String.format(Locale.ENGLISH, "%.0f%%", autosensData.autosensResult.ratio * 100)
            } ?: ""
    }

    private fun updatePumpStatus() {
        _binding ?: return
        val status = overviewData.pumpStatus
        binding.pumpStatus.text = status
        binding.pumpStatusLayout.visibility = (status != "").toVisibility()
    }

    private fun updateNotification() {
        _binding ?: return
        binding.notifications.let { notificationStore.updateNotifications(it) }
    }
}
