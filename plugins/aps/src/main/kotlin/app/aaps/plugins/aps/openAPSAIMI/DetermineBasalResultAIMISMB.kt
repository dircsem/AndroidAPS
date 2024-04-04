package app.aaps.plugins.aps.openAPSAIMI

import android.text.Spanned
import app.aaps.core.interfaces.aps.VariableSensitivityResult
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.objects.aps.APSResultObject
import app.aaps.core.utils.HtmlHelper
import app.aaps.plugins.aps.openAPSSMB.DetermineBasalResultSMB
import dagger.android.HasAndroidInjector
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.round

class DetermineBasalResultAIMISMB private constructor(injector: HasAndroidInjector) : DetermineBasalResultSMB(injector), VariableSensitivityResult {

    var constraintStr: String = ""
    var glucoseStr: String = ""
    var iobStr: String = ""
    var profileStr: String = ""
    var mealStr: String = ""
    var delta:Float = 0.0f
    var bg:Float = 0.0f
    override var targetBG: Double = 0.0
    var basalaimi:Float = 0.0f
    var enablebasal:Boolean = false
    override var variableSens: Double? = null
    private val apsResultObject = APSResultObject(injector)


    internal constructor(
        injector: HasAndroidInjector,
        requestedSMB: Float,
        constraintStr: String,
        glucoseStr: String,
        iobStr: String,
        profileStr: String,
        mealStr: String,
        reason: String
    ) : this(injector) {
        this.constraintStr = constraintStr
        this.glucoseStr = glucoseStr
        this.iobStr = iobStr
        this.profileStr = profileStr
        this.mealStr = mealStr


        fun extractGlucoseValues() {
            val lines = glucoseStr.split("<br/>")
            lines.forEach { line ->
                when {
                    line.startsWith(" bg: ") -> this.bg = line.substringAfter(" bg: ").toFloatOrNull() ?: 0.0f
                    line.startsWith(" delta: ") -> this.delta = line.substringAfter(" delta: ").toFloatOrNull() ?: 0.0f
                    line.startsWith(" targetBG: ") -> this.targetBG = (line.substringAfter(" targetBG: ").toFloatOrNull() ?: 0.0f).toDouble()
                }
            }
            aapsLogger.debug(LTag.APS, "BG: $bg, Delta: $delta, targetBG: $targetBG")
        }
        fun extractIobValues() {
            val lines = iobStr.split("<br/>")
            lines.forEach { line ->
                when {
                    line.startsWith(" enablebasal:") -> this.enablebasal = line.substringAfter(" enablebasal:").trim().toBoolean()
                    line.startsWith(" basalaimi: ") -> this.basalaimi= line.substringAfter(" basalaimi: ").toFloatOrNull() ?: 0.0f
                    line.startsWith(" ISF: ") -> this.variableSens= line.substringAfter(" ISF: ").toDoubleOrNull() ?: 0.0
                }
            }
            aapsLogger.debug(LTag.APS, "basalaimi: $basalaimi, enablebasal: $enablebasal")
        }


        this.date = dateUtil.now()
        extractGlucoseValues()
        extractIobValues()

        updateAPSResult(apsResultObject)
            this.isTempBasalRequested = true
            this.usePercent = true
        /*if (enablebasal === true) {
            if (delta <= 0 && bg <= 150) {
                this.percent = 0
                this.rate = 0.0
                this.duration = 120
                aapsLogger.debug(LTag.APS, "rate: $rate, percent: $percent, duration: $duration, bg: $bg, delta: $delta")
            } else if (delta > 0 && bg > 80) {
                this.percent = delta.toInt() * 100
                this.rate = basalaimi.toDouble() * delta
                this.duration = 30
                aapsLogger.debug(LTag.APS, "rate: $rate, percent: $percent, duration: $duration, bg: $bg, delta: $delta")
            }
        }else{
            this.rate = 0.0
            this.duration = 120
        }*/
        if (enablebasal) {
            when {
                delta <= 0 && bg <= 140 -> {
                    // Logique pour delta <= 0 et bg <= 150
                    this.percent = 0
                    this.rate = 0.0
                    this.duration = 120
                    aapsLogger.debug(LTag.APS, "Basale désactivée - Rate: $rate, Percent: $percent, Duration: $duration, BG: $bg, Delta: $delta")
                }
                delta > 0 && bg > 80 -> {
                    // Logique pour delta > 0 et bg > 80
                    this.percent = delta.toInt() * 100
                    this.rate = basalaimi.toDouble() * delta
                    this.duration = 30
                    aapsLogger.debug(LTag.APS, "Basale activée - Rate: $rate, Percent: $percent, Duration: $duration, BG: $bg, Delta: $delta")
                }
                else -> {
                    this.percent = 0
                    this.rate = 0.0
                    this.duration = 120
                    aapsLogger.debug(LTag.APS, "Basale désactivée - Rate: $rate, Percent: $percent, Duration: $duration, BG: $bg, Delta: $delta")
                }
            }
        } else {
            // Logique lorsque enablebasal est false
            this.rate = 0.0
            this.duration = 120
            aapsLogger.debug(LTag.APS, "Basale non activée - Rate: $rate, Duration: $duration")
        }


        this.smb = requestedSMB.toDouble()
        if (requestedSMB > 0) {
            this.deliverAt = dateUtil.now()
        }
        //updateAPSResult(apsResultObject)
        this.reason = reason
    }

    override fun toSpanned(): Spanned {
        val result = "$constraintStr<br/><br/>$glucoseStr<br/><br/>$iobStr" +
            "<br/><br/>$profileStr<br/><br/>$mealStr<br/><br/>$reason"
        return HtmlHelper.fromHtml(result)
    }
    private fun updateAPSResult(apsResult: APSResultObject) {

        val newRate = round(basalaimi.toDouble() * delta)
        val newDuration = 30
        val newtargetBG = targetBG

        aapsLogger.debug(LTag.APS, "basalaimi: $basalaimi, enablebasal: $enablebasal, newtargetBG: $newtargetBG, newduration: $newDuration, newrate: $newRate, delta: $delta, bg: $bg")
        if (enablebasal){
            when{
                delta <= 0 && bg <= 140.0f ->{
                    isTempBasalRequested = true
                    isChangeRequested
                    this.usePercent = true
                    apsResult.rate = 0.0
                    apsResult.duration = newDuration
                }
                delta > 0 && bg > 80 -> {
                    isTempBasalRequested = true
                    this.usePercent = true
                    this.percent = delta.toInt() * 100
                    apsResult.rate = newRate.toDouble() * delta
                    apsResult.duration = newDuration
                    aapsLogger.debug(LTag.APS, "Mise à jour de l'APSResult - Rate: $newRate, Duration: $newDuration, BG: $bg, Delta: $delta")
                }
                else ->{
                    isTempBasalRequested = true
                    isChangeRequested
                    this.usePercent = true
                    this.percent = 0
                    apsResult.rate = 0.0
                    apsResult.duration = newDuration
                }

            }
        }else{
            isTempBasalRequested = true
            isChangeRequested
            this.usePercent = true
            this.percent = 0
            apsResult.rate = 0.0
            apsResult.duration = newDuration
        }

        apsResult.targetBG = newtargetBG

    }

   fun newAndClone(injector: HasAndroidInjector): DetermineBasalResultSMB {
        val newResult = DetermineBasalResultAIMISMB(injector)
        doClone(newResult)
        newResult.rate = this.rate
        newResult.duration = this.duration
        newResult.targetBG = this.targetBG
        return newResult
    }

    override fun json(): JSONObject? {
        val result = "$constraintStr<br/><br/>$glucoseStr<br/><br/>$iobStr" +
            "<br/><br/>$profileStr<br/><br/>$mealStr<br/><br/>$reason"
        val json = JSONObject()
        try {
            // Ajout des données dans l'objet JSON
            //jsonData.put("reason", result)
            if (isChangeRequested) {
                json.put("rate", rate)
                json.put("duration", duration)
                json.put("percent",percent)
                if (variableSens!! >= 15) {
                    json.put("isf", variableSens)
                }
                json.put("reason", result)
            }

        } catch (e: JSONException) {
            aapsLogger.error(LTag.APS, "Error creating JSON object", e)
            return null
        }
        return json
    }


    init {
        hasPredictions = true
    }


}