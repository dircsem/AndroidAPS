package app.aaps.plugins.aps.openAPSAIMI
import android.content.Context
import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.interfaces.aps.DetermineBasalAdapter
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.constraints.Objectives
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.ImportExportPrefs
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.target
import app.aaps.core.utils.MidnightUtils
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.plugins.aps.openAPSSMB.OpenAPSSMBPlugin
import dagger.android.HasAndroidInjector
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenAPSAIMIPlugin  @Inject constructor(
    private val injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    rxBus: RxBus,
    constraintChecker: ConstraintsChecker,
    rh: ResourceHelper,
    profileFunction: ProfileFunction,
    context: Context,
    activePlugin: ActivePlugin,
    iobCobCalculator: IobCobCalculator,
    processedTbrEbData: ProcessedTbrEbData,
    hardLimits: HardLimits,
    profiler: Profiler,
    sp: SP,
    preferences: Preferences,
    dateUtil: DateUtil,
    persistenceLayer: PersistenceLayer,
    glucoseStatusProvider: GlucoseStatusProvider,
    bgQualityCheck: BgQualityCheck,
    tddCalculator: TddCalculator,
    importExportPrefs: ImportExportPrefs,
    config: Config,
    private val uiInteraction: UiInteraction,
    private val objectives: Objectives
) : OpenAPSSMBPlugin(
    injector,
    aapsLogger,
    rxBus,
    constraintChecker,
    rh,
    profileFunction,
    context,
    activePlugin,
    iobCobCalculator,
    processedTbrEbData,
    hardLimits,
    profiler,
    preferences,
    dateUtil,
    persistenceLayer,
    glucoseStatusProvider,
    bgQualityCheck,
    tddCalculator,
    importExportPrefs,
    config
    ) {

        init {
            pluginDescription
                .pluginName(R.string.openapsaimi)
                .description(R.string.description_openapsaimi)
                .shortName(R.string.oaps_aimi_shortname)
                .preferencesId(R.xml.pref_openapsaimi)
                .preferencesVisibleInSimpleMode(true)
                .setDefault(false)
        }

    override fun specialEnableCondition(): Boolean {
        return try {
            activePlugin.activePump.pumpDescription.isTempBasalCapable
        } catch (ignored: Exception) {
            // may fail during initialization
            true
        }
    }

    override fun specialShowInListCondition(): Boolean {
        val pump = activePlugin.activePump
        return pump.pumpDescription.isTempBasalCapable
    }

    override fun invoke(initiator: String, tempBasalFallback: Boolean) {
        aapsLogger.debug(LTag.APS, "invoke from $initiator tempBasalFallback: $tempBasalFallback")
        lastAPSResult = null
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        val profile = profileFunction.getProfile()
        val pump = activePlugin.activePump
        if (profile == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(app.aaps.core.ui.R.string.no_profile_set)))
            aapsLogger.debug(LTag.APS, rh.gs(app.aaps.core.ui.R.string.no_profile_set))
            return
        }
        if (!isEnabled()) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_disabled)))
            aapsLogger.debug(LTag.APS, rh.gs(R.string.openapsma_disabled))
            return
        }
        if (glucoseStatus == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_no_glucose_data)))
            aapsLogger.debug(LTag.APS, rh.gs(R.string.openapsma_no_glucose_data))
            return
        }

        val inputConstraints = ConstraintObject(0.0, aapsLogger) // fake. only for collecting all results
        val maxBasal = constraintChecker.getMaxBasalAllowed(profile).also {
            inputConstraints.copyReasons(it)
        }.value()
        var start = System.currentTimeMillis()
        var startPart = System.currentTimeMillis()
        profiler.log(LTag.APS, "getMealData()", startPart)
        val maxIob = constraintChecker.getMaxIOBAllowed().also { maxIOBAllowedConstraint ->
            inputConstraints.copyReasons(maxIOBAllowedConstraint)
        }.value()

        var minBg =
            hardLimits.verifyHardLimits(
                Round.roundTo(profile.getTargetLowMgdl(), 0.1),
                app.aaps.core.ui.R.string.profile_low_target,
                HardLimits.VERY_HARD_LIMIT_MIN_BG[0],
                HardLimits.VERY_HARD_LIMIT_MIN_BG[1]
            )
        var maxBg =
            hardLimits.verifyHardLimits(
                Round.roundTo(profile.getTargetHighMgdl(), 0.1),
                app.aaps.core.ui.R.string.profile_high_target,
                HardLimits.VERY_HARD_LIMIT_MAX_BG[0],
                HardLimits.VERY_HARD_LIMIT_MAX_BG[1]
            )
        var targetBg =
            hardLimits.verifyHardLimits(profile.getTargetMgdl(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.VERY_HARD_LIMIT_TARGET_BG[0], HardLimits.VERY_HARD_LIMIT_TARGET_BG[1])
        var isTempTarget = false
        val tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())

        if (tempTarget != null) {
            isTempTarget = true
            minBg =
                hardLimits.verifyHardLimits(
                    tempTarget.lowTarget,
                    app.aaps.core.ui.R.string.temp_target_low_target,
                    HardLimits.VERY_HARD_LIMIT_TEMP_MIN_BG[0].toDouble(),
                    HardLimits.VERY_HARD_LIMIT_TEMP_MIN_BG[1].toDouble()
                )
            maxBg =
                hardLimits.verifyHardLimits(
                    tempTarget.highTarget,
                    app.aaps.core.ui.R.string.temp_target_high_target,
                    HardLimits.VERY_HARD_LIMIT_TEMP_MAX_BG[0].toDouble(),
                    HardLimits.VERY_HARD_LIMIT_TEMP_MAX_BG[1].toDouble()
                )
            targetBg =
                hardLimits.verifyHardLimits(
                    tempTarget.target(),
                    app.aaps.core.ui.R.string.temp_target_value,
                    HardLimits.VERY_HARD_LIMIT_TEMP_TARGET_BG[0].toDouble(),
                    HardLimits.VERY_HARD_LIMIT_TEMP_TARGET_BG[1].toDouble()
                )
        }
        if (!hardLimits.checkHardLimits(profile.dia, app.aaps.core.ui.R.string.profile_dia, hardLimits.minDia(), hardLimits.maxDia())) return
        if (!hardLimits.checkHardLimits(
                profile.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight()),
                app.aaps.core.ui.R.string.profile_carbs_ratio_value,
                hardLimits.minIC(),
                hardLimits.maxIC()
            )
        ) return
        if (!hardLimits.checkHardLimits(profile.getIsfMgdl(), app.aaps.core.ui.R.string.profile_sensitivity_value, HardLimits.MIN_ISF, HardLimits.MAX_ISF)) return
        if (!hardLimits.checkHardLimits(profile.getMaxDailyBasal(), app.aaps.core.ui.R.string.profile_max_daily_basal_value, 0.02, hardLimits.maxBasal())) return
        if (!hardLimits.checkHardLimits(pump.baseBasalRate, app.aaps.core.ui.R.string.current_basal_value, 0.01, hardLimits.maxBasal())) return
        startPart = System.currentTimeMillis()
        if (constraintChecker.isAutosensModeEnabled().value()) {
            val autosensData = iobCobCalculator.getLastAutosensDataWithWaitForCalculationFinish("OpenAPSPlugin")
            if (autosensData == null) {
                rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openaps_no_as_data)))
                return
            }
            lastAutosensResult = autosensData.autosensResult
        } else {
            lastAutosensResult.sensResult = "autosens disabled"
        }
        val iobArray = iobCobCalculator.calculateIobArrayForSMB(lastAutosensResult, SMBDefaults.exercise_mode, SMBDefaults.half_basal_exercise_target, isTempTarget)
        profiler.log(LTag.APS, "calculateIobArrayInDia()", startPart)
        startPart = System.currentTimeMillis()
        val smbAllowed = ConstraintObject(!tempBasalFallback, aapsLogger).also {
            constraintChecker.isSMBModeEnabled(it)
            inputConstraints.copyReasons(it)
        }
        val advancedFiltering = ConstraintObject(!tempBasalFallback, aapsLogger).also {
            constraintChecker.isAdvancedFilteringEnabled(it)
            inputConstraints.copyReasons(it)
        }
        val uam = ConstraintObject(true, aapsLogger).also {
            constraintChecker.isUAMEnabled(it)
            inputConstraints.copyReasons(it)
        }
        dynIsfEnabled = ConstraintObject(true, aapsLogger).also {
            constraintChecker.isDynIsfModeEnabled(it)
            inputConstraints.copyReasons(it)
        }
        val flatBGsDetected = bgQualityCheck.state == BgQualityCheck.State.FLAT
        profiler.log(LTag.APS, "detectSensitivityAndCarbAbsorption()", startPart)
        profiler.log(LTag.APS, "SMB data gathering", start)
        start = System.currentTimeMillis()

        // DynamicISF specific
        // without these values DynISF doesn't work properly
        // Current implementation is fallback to SMB if TDD history is not available. Thus calculated here
        tdd1D = tddCalculator.averageTDD(tddCalculator.calculate(1, allowMissingDays = false))?.totalAmount
        tdd7D = tddCalculator.averageTDD(tddCalculator.calculate(7, allowMissingDays = false))?.totalAmount
        tddLast24H = tddCalculator.calculateDaily(-24, 0)?.totalAmount
        tddLast4H = tddCalculator.calculateDaily(-4, 0)?.totalAmount
        tddLast8to4H = tddCalculator.calculateDaily(-8, -4)?.totalAmount

        if (tdd1D == null || tdd7D == null || tddLast4H == null || tddLast8to4H == null || tddLast24H == null) {
            inputConstraints.copyReasons(
                ConstraintObject(false, aapsLogger).also {
                    it.set(false, rh.gs(R.string.fallback_smb_no_tdd), this)
                }
            )
            inputConstraints.copyReasons(
                ConstraintObject(false, aapsLogger).apply { set(true, "tdd1D=$tdd1D tdd7D=$tdd7D tddLast4H=$tddLast4H tddLast8to4H=$tddLast8to4H tddLast24H=$tddLast24H", this) }
            )
        }


        provideDetermineBasalAdapter().also { determineBasalAdapterSMBJS ->
            determineBasalAdapterSMBJS.setData(
                profile, maxIob, maxBasal, minBg, maxBg, targetBg,
                activePlugin.activePump.baseBasalRate,
                iobArray,
                glucoseStatus,
                iobCobCalculator.getMealDataWithWaitingForCalculationFinish(),
                lastAutosensResult.ratio,
                isTempTarget,
                smbAllowed.value(),
                uam.value(),
                advancedFiltering.value(),
                flatBGsDetected,
                tdd1D = tdd1D,
                tdd7D = tdd7D,
                tddLast24H = tddLast24H,
                tddLast4H = tddLast4H,
                tddLast8to4H = tddLast8to4H
            )
            val now = System.currentTimeMillis()
            val determineBasalResultAIMISMB = determineBasalAdapterSMBJS.invoke()
            profiler.log(LTag.APS, "SMB calculation", start)
            if (determineBasalResultAIMISMB == null) {
                aapsLogger.error(LTag.APS, "SMB calculation returned null")
                lastDetermineBasalAdapter = null
                lastAPSResult = null
                lastAPSRun = 0
            } else {
                // TODO still needed with oref1?
                // Fix bug determine basal
                if (determineBasalResultAIMISMB != null) {
                    if (determineBasalResultAIMISMB.rate == 0.0 && determineBasalResultAIMISMB.duration == 0 && processedTbrEbData.getTempBasalIncludingConvertedExtended(dateUtil.now()) == null)
                        determineBasalResultAIMISMB.isTempBasalRequested = false
                }
                determineBasalResultAIMISMB!!.iob = iobArray[0]
                determineBasalResultAIMISMB!!.json?.put("timestamp", dateUtil.toISOString(now))
                determineBasalResultAIMISMB!!.inputConstraints = inputConstraints

                lastDetermineBasalAdapter = determineBasalAdapterSMBJS
                lastAPSResult = determineBasalResultAIMISMB as DetermineBasalResultAIMISMB
                lastAPSRun = now
            }
        }
        rxBus.send(EventOpenAPSUpdateGui())
    }
        override fun provideDetermineBasalAdapter(): DetermineBasalAdapter = DetermineBasalAdapterAIMI(injector)
        /*override fun provideDetermineBasalAdapter(): DetermineBasalAdapter =
            if (tdd1D == null || tdd7D == null || tddLast4H == null || tddLast8to4H == null || tddLast24H == null || !dynIsfEnabled.value())
                DetermineBasalAdapterSMBJS(ScriptReader(context), injector)
            else DetermineBasalAdapterAIMI(ScriptReader(context), injector)*/

    }
