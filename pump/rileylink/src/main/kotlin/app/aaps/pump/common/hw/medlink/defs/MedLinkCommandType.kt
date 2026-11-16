package app.aaps.pump.common.hw.medlink.defs

import androidx.annotation.StringRes
import app.aaps.pump.common.hw.rileylink.R
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * Created by dirceu on 17/09/2020.
 */
/**
 * @param descriptionRes short text shown on the main screen (pump activity dialog) while the MedLink runs this
 * command; 0 = internal step, not shown
 */
enum class MedLinkCommandType constructor(
    command: String,
    val needActivePump: Boolean = false,
    val listCommand: Boolean = false,
    val actionCommand: Boolean = false,
    @StringRes val descriptionRes: Int = 0,
) {

    NoCommand(""),
    ReadCharacteristic("\"ReadCharacteristic\"", listCommand = true),
    Notification("SetNotificationBlocking", listCommand = true),
    PumpModel("OK+CONN", listCommand = true),
    Connect("OK+CONN", listCommand = true, descriptionRes = R.string.medlink_cmd_connect),
    GetState("S", listCommand = true, descriptionRes = R.string.medlink_cmd_get_state),  //
    StopStartPump("A", descriptionRes = R.string.medlink_cmd_stop_start_pump),
    Bolus("X", true, descriptionRes = R.string.medlink_cmd_bolus),
    BolusAmount("BOLUS", true, descriptionRes = R.string.medlink_cmd_bolus_amount),
    StartPump("START",true, actionCommand = true, descriptionRes = R.string.medlink_cmd_start_pump),
    StopPump("STOP",true, actionCommand = true, descriptionRes = R.string.medlink_cmd_stop_pump),
    IsigHistory("I", listCommand = true, descriptionRes = R.string.medlink_cmd_isig_history),
    PreviousIsigHistory("J", listCommand = true, descriptionRes = R.string.medlink_cmd_isig_history),
    BGHistory("C", listCommand = true, descriptionRes = R.string.medlink_cmd_bg_history),
    PreviousBGHistory("T", listCommand = true, descriptionRes = R.string.medlink_cmd_bg_history),
    BolusHistory("H", descriptionRes = R.string.medlink_cmd_bolus_history),
    ActiveBasalProfile("E", listCommand = true, descriptionRes = R.string.medlink_cmd_active_basal_profile),
    BaseProfile("F", listCommand = true, descriptionRes = R.string.medlink_cmd_basal_profile),
    Calibrate("K", true,  actionCommand = true, descriptionRes = R.string.medlink_cmd_calibrate),
    CalibrateFrequency("U"),
    CalibrateFrequencyArgument(""),
    CalibrateValue("CAL", true),
    BolusStatus("M", descriptionRes = R.string.medlink_cmd_bolus_status),
    WakeUp("M", descriptionRes = R.string.medlink_cmd_wake_up),
    SMBBolus("X", true, descriptionRes = R.string.medlink_cmd_smb_bolus),
    TBRBolus("X", true, descriptionRes = R.string.medlink_cmd_tbr_bolus),
    PreviousBolusHistory("G", listCommand = true, descriptionRes = R.string.medlink_cmd_bolus_history),
    RemoveStopCommand("");

    val code: String
    var config: Iterator<String>? = null
    var insulinAmount = 0.0
    var bgValue = 0.0
    var resourceId: Int = 0
    fun getCommandDescription(): String {
        return name
    }

    private fun appendEndLine(buff: StringBuilder): ByteArray {
        buff.append("\r").append("\n")
        return buff.toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun getRaw(argument: String = ""): ByteArray {
        // if (config != null && config!!.hasNext()) {
        //     val buffer = StringBuilder(config!!.next())
        //     appendEndLine(buffer)
        // } else
        return  if (this.code != null && this.code.isNotEmpty()) {
            appendEndLine(StringBuilder(this.code).append(argument))
        } else {
            ByteArray(0)
        }
    }

    private fun isSameCommand(command: ByteArray?): Boolean {
        return command != null && Arrays.equals(getRaw(), command)
    }

    private fun isSameCommand(command: String?): Boolean {
        return command != null && command == this.code
    }

    fun isSameCommand(command: MedLinkCommandType?): Boolean {
        return command != null && (isSameCommand(command.getRaw()) || isSameCommand(command.code))
    }

    //    MedLinkCommandType(MedLinkCommandType command, Double insulinAmount) {
    //        this.code = command.code;
    //        this.insulinAmount = insulinAmount;
    //    }
    init {
        this.code = command
    }

    override fun toString(): String {
        return when {
            (this == BolusAmount)                -> {
                super.toString() +
                    " bolusAmount: $insulinAmount"
            }
            (this == CalibrateFrequencyArgument) -> {
                super.toString() +
                    "calibrateArgument: $"

            }

            (this == CalibrateValue)             -> {
                super.toString() +
                    "bgValue: $bgValue"

            }

            else                                 -> {
                super.toString()
            }
        }
    }

    fun isManualBolus(command: MedLinkCommandType) : Boolean  {
        return when(command) {
            Bolus -> true
            else ->  false
        }
    }
}