package app.aaps.pump.medtronic.defs


import app.aaps.pump.common.hw.medlink.defs.MedLinkCommandType
import app.aaps.pump.common.hw.medlink.defs.MedLinkMedtronicDeviceType
import app.aaps.pump.medtronic.R
import app.aaps.pump.medtronic.comm.message.MessageBody
import app.aaps.pump.medtronic.comm.message.PumpAckMessageBody
import app.aaps.pump.medtronic.comm.message.UnknownMessageBody
import java.io.Serializable

/**
 * Taken from GNU Gluco Control diabetes management software (ggc.sourceforge.net)
 *
 *
 * Description: Medtronic Commands (Pump and CGMS) for all 512 and later models (just 5xx)
 *
 *
 * Link to original/unmodified file:
 * https://sourceforge.net/p/ggc/code/HEAD/tree/trunk/ggc-plugins/ggc-plugins-base/src/
 * main/java/ggc/plugin/device/impl/minimed/enums/MinimedCommandType.java
 *
 *
 * A lot of stuff has been removed because it is not needed anymore (historical stuff from CareLink
 * and Carelink USB communication.
 *
 *
 * Author: Andy {andy@atech-software.com}
 *
 * used by medlink
 */
enum class MedLinkMedtronicCommandType : Serializable // , MinimedCommandTypeInterface
{
    InvalidCommand(
        0, "Invalid Command", null, null,
        MedLinkCommandType.NoCommand
    ),  //

    // Pump Responses (9)
    CommandACK,  //            0x06, "ACK - Acknowledge", MedtronicDeviceType.All, MinimedCommandParameterType.NoParameters), //
    //    CommandNAK(0x15, "NAK - Not Acknowledged", MedtronicDeviceType.All, MinimedCommandParameterType.NoParameters), //

    // All (8)
    //    PushAck(91, "Push ACK", MedtronicDeviceType.All, MinimedCommandParameterType.FixedParameters, getByteArray(2)), //
    //
    //    PushEsc(91, "Push Esc", MedtronicDeviceType.All, MinimedCommandParameterType.FixedParameters, getByteArray(1)), //
    //
    //    PushButton(0x5b, "Push Button", MedtronicDeviceType.All, MinimedCommandParameterType.NoParameters), // 91
    //
    RFPowerOn(
        93,
        "RF Power On",
        MedtronicDeviceType.All,
        MinimedCommandParameterType.FixedParameters,
//        MedLinkMedtronicCommandType.run {
//            getByteArray(
//                1, 10
//            )
//        }
    ),  //
    //
    //    RFPowerOff(93, "RF Power Off", MedtronicDeviceType.All, MinimedCommandParameterType.FixedParameters, getByteArray(
    //            0, 0)), //

    SuspendActivate(
        77, "Set Suspend", MedtronicDeviceType.All,
        MinimedCommandParameterType.NoParameters, MedLinkCommandType.StopStartPump
    ),  //a

    Bolus(
        77,
        "Bolus",
        MedtronicDeviceType.All,
        MinimedCommandParameterType.FixedParameters,
//        MedLinkMedtronicCommandType.Companion.getByteArray()
    ),  //a
    // SetSuspend(77, "Set Suspend", MinimedTargetType.InitCommand, MedtronicDeviceType.All,
    // MinimedCommandParameterType.FixedParameters, getByteArray(1)), //
    // CancelSuspend(77, "Cancel Suspend", MinimedTargetType.InitCommand, MedtronicDeviceType.All,
    // MinimedCommandParameterType.FixedParameters, getByteArray(0)), //
    //    PumpState(131, "Pump State", MedtronicDeviceType.All, MinimedCommandParameterType.NoParameters), //
    //
    //    ReadPumpErrorStatus(117, "Pump Error Status", MedtronicDeviceType.All, MinimedCommandParameterType.NoParameters), //
    // 511 (InitCommand = 2, Config 7, Data = 1(+3)
    //    DetectBolus(75, "Detect Bolus", MedtronicDeviceType.Medtronic_511, MinimedCommandParameterType.FixedParameters, getByteArray(
    //        0, 0, 0)), //
    // RemoteControlIds(118, "Remote Control Ids", MinimedTargetType.PumpConfiguration_NA, MedtronicDeviceType.All,
    // MinimedCommandParameterType.NoParameters), //
    // FirmwareVersion(116, "Firmware Version", MinimedTargetType.InitCommand, MedtronicDeviceType.All,
    // MinimedCommandParameterType.NoParameters), //
    // PumpId(113, "Pump Id", MinimedTargetType.PumpConfiguration, MedtronicDeviceType.All,
    // MinimedCommandParameterType.NoParameters), // init

    SetRealTimeClock(
        0x40,
        "Set Pump Time",
        MedtronicDeviceType.All,
        MinimedCommandParameterType.NoParameters,  //
        0
    ),  //

    //
    GetRealTimeClock(
        112, "Get Pump Time", MedtronicDeviceType.All, MinimedCommandParameterType.NoParameters,  //
        7, R.string.medtronic_cmd_desc_get_time, MedLinkCommandType.GetState
    ),  // 0x70

    //
    GetBatteryStatus(
        0x72,
        "Get Battery Status",
        MedtronicDeviceType.All,
        MinimedCommandParameterType.NoParameters
    ),  //
    GetBattery(0x72),  //

    GetRemainingInsulin(
        0x73,
        "Read Remaining Insulin",
        MedtronicDeviceType.All,
        MinimedCommandParameterType.NoParameters,
        2
    ),  // 115

    SetBolus(
        0x42, "Set Bolus", MedtronicDeviceType.All, MinimedCommandParameterType.NoParameters,  //
        0, R.string.medtronic_cmd_desc_set_bolus, MedLinkCommandType.Bolus
    ),  // 66

    //
    // 512
    //TODO not needed
    ReadTemporaryBasal(
        0x98,
        "Read Temporary Basal",
        MedtronicDeviceType.Medtronic_512andHigher,
        MinimedCommandParameterType.NoParameters,  //
        5,
        R.string.medtronic_cmd_desc_get_tbr,
        MedLinkCommandType.NoCommand
    ),  // 152
    //
    //    SetTemporaryBasal(76, "Set Temporay Basal", MedtronicDeviceType.Medtronic_512andHigher, MinimedCommandParameterType.NoParameters, //
    //            0, R.string.medtronic_cmd_desc_set_tbr),

    // 512 Config
    PumpModel(
        141, "Pump Model", MedtronicDeviceType.Medtronic_512andHigher,
        MinimedCommandParameterType.NoParameters,  //
        5, R.string.medtronic_cmd_desc_get_model, MedLinkCommandType.Connect
    ),  // 0x8D
    // BGTargets_512(140, "BG Targets", MinimedTargetType.PumpConfiguration, MedtronicDeviceType.Medtronic_512_712,
    // MinimedCommandParameterType.NoParameters), //
    // BGUnits(137, "BG Units", MinimedTargetType.PumpConfiguration, MedtronicDeviceType.Medtronic_512andHigher,
    // MinimedCommandParameterType.NoParameters), //
    // Language(134, "Language", MinimedTargetType.PumpConfiguration, MedtronicDeviceType.Medtronic_512andHigher,
    // MinimedCommandParameterType.NoParameters), //

    Settings_512(
        145,
        "Configuration",
        MedtronicDeviceType.Medtronic_512_712,
        MinimedCommandParameterType.NoParameters,  //
        64,
        1,
        18,
        R.string.medtronic_cmd_desc_get_settings
    ),  //
    // BGAlarmClocks(142, "BG Alarm Clocks", MinimedTargetType.PumpConfiguration,
    // MedtronicDeviceType.Medtronic_512andHigher, MinimedCommandParameterType.NoParameters), //
    // BGAlarmEnable(151, "BG Alarm Enable", MinimedTargetType.PumpConfiguration,
    // MedtronicDeviceType.Medtronic_512andHigher, MinimedCommandParameterType.NoParameters), //
    // BGReminderEnable(144, "BG Reminder Enable", MinimedTargetType.PumpConfiguration,
    // MedtronicDeviceType.Medtronic_512andHigher, MinimedCommandParameterType.NoParameters), //
    // ReadInsulinSensitivities(0x8b, "Read Insulin Sensitivities", MinimedTargetType.PumpConfiguration,
    // MedtronicDeviceType.Medtronic_512andHigher, MinimedCommandParameterType.NoParameters), // 139

    // 512 Data
    GetHistoryData(
        128,
        "Get History",
        MedtronicDeviceType.Medtronic_512andHigher,
        MinimedCommandParameterType.SubCommands,  //
        1024,
        16,
        1024,
        R.string.medtronic_cmd_desc_get_history
    ),  // 0x80

    //
    GetBasalProfileSTD(
        146,
        "Get Profile Standard",
        MedtronicDeviceType.Medtronic_512andHigher,
        MinimedCommandParameterType.NoParameters,  //
        64,
        3,
        192,
        R.string.medtronic_cmd_desc_get_basal_profile,
        MedLinkCommandType.GetState
    ),  // 146

    //
    GetBasalProfileA(
        147,
        "Get Profile A",
        MedtronicDeviceType.Medtronic_512andHigher,
        MinimedCommandParameterType.NoParameters,  //
        64,
        3,
        192,
        R.string.medtronic_cmd_desc_get_basal_profile
    ),

    GetBasalProfileB(
        148,
        "Get Profile B",
        MedtronicDeviceType.Medtronic_512andHigher,
        MinimedCommandParameterType.NoParameters,  //
        64,
        3,
        192,
        R.string.medtronic_cmd_desc_get_basal_profile
    ),  // 148
    //    SetBasalProfileSTD(0x6f, "Set Profile Standard", MedtronicDeviceType.Medtronic_512andHigher, MinimedCommandParameterType.NoParameters, //
    //            64, 3, 192, R.string.medtronic_cmd_desc_set_basal_profile), // 111
    //
    //    SetBasalProfileA(0x30, "Set Profile A", MedtronicDeviceType.Medtronic_512andHigher, MinimedCommandParameterType.NoParameters, //
    //            64, 3, 192, R.string.medtronic_cmd_desc_set_basal_profile), // 48
    //    SetBasalProfileB(0x31, "Set Profile B", MedtronicDeviceType.Medtronic_512andHigher, MinimedCommandParameterType.NoParameters, //
    //            64, 3, 192, R.string.medtronic_cmd_desc_set_basal_profile), // 49

    // 515
    PumpStatus(
        206, "Pump Status", MedtronicDeviceType.Medtronic_515andHigher,
        MinimedCommandParameterType.NoParameters, MedLinkCommandType.GetState
    ),  // PumpConfiguration

    Settings(
        192,
        "Configuration",
        MedtronicDeviceType.Medtronic_515andHigher,
        MinimedCommandParameterType.NoParameters,  //
        64,
        1,
        21,
        R.string.medtronic_cmd_desc_get_settings
    ),
    ; //
    // 522
    //    SensorSettings_522(153, "Sensor Configuration", MedtronicDeviceType.Medtronic_522andHigher, MinimedCommandParameterType.NoParameters), //
    //
    //    GlucoseHistory(154, "Glucose History", MedtronicDeviceType.Medtronic_522andHigher, MinimedCommandParameterType.SubCommands, 1024, 32, 0, null), //
    // 523
    //    SensorSettings(207, "Sensor Configuration", MedtronicDeviceType.Medtronic_523andHigher, MinimedCommandParameterType.NoParameters), //
    //
    // 553
    // 554
    // var MESSAGES = {
    // READ_TIME : 0x70,
    // READ_BATTERY_STATUS: 0x72,
    // READ_HISTORY : 0x80,
    // READ_CARB_RATIOS : 0x8A,
    // READ_INSULIN_SENSITIVITIES: 0x8B,
    // READ_MODEL : 0x8D,
    // READ_PROFILE_STD : 0x92,
    // READ_PROFILE_A : 0x93,
    // READ_PROFILE_B : 0x94,
    // READ_CBG_HISTORY: 0x9A,
    // READ_ISIG_HISTORY: 0x9B,
    // READ_CURRENT_PAGE : 0x9D,
    // READ_BG_TARGETS : 0x9F,
    // READ_SETTINGS : 0xC0, 192
    // READ_CURRENT_CBG_PAGE : 0xCD
    // };
    // Fake Commands
    //    CancelTBR(),

    var command: MedLinkCommandType? = null
    var commandCode: Byte = 0
    lateinit var commandDescription: String
    var commandParameters: ByteArray? = null
    var commandParametersCount: Int = 0
    var maxRecords: Int = 1
    var resourceId: Int? = null
        private set
    var command_type: Int = 0
    var allowedRetries: Int = 2
    var maxAllowedTime: Int = 2000
    var parameterType: MinimedCommandParameterType? = null
    var minimalBufferSizeToStartReading: Int = 14
    var expectedLength: Int = 0

    //MinimedTargetType targetType;
    var devices: MedtronicDeviceType? = null
    var recordLength: Int = 64
        private set


    constructor()
    constructor(code: Int) {
        // this is for "fake" commands needed by AAPS MedtronicUITask
        this.command_type = code.toByte().toInt()
    }

    //    MedtronicCommandType(int code, String description, MedtronicDeviceType devices,
    //            MinimedCommandParameterType parameterType) {
    //        this(code, description, devices, parameterType, 64, 1, 0, 0, 0, 0);
    //    }
    //
    //
    //    MedtronicCommandType(int code, String description, MedtronicDeviceType devices,
    //            MinimedCommandParameterType parameterType, int expectedLength) {
    //        this(code, description, devices, parameterType, 64, 1, 0, 0, 0, expectedLength);
    //    }
    //
    //
    //    MedtronicCommandType(int code, String description, MedtronicDeviceType devices,
    //            MinimedCommandParameterType parameterType, int recordLength, int maxRecords, int commandType) {
    //        this(code, description, devices, parameterType, recordLength, maxRecords, 0, 0, commandType, 0);
    //    }
    //
    //
    //    MedtronicCommandType(int code, String description, MedtronicDeviceType devices,
    //            MinimedCommandParameterType parameterType, int recordLength, int maxRecords, int commandType,
    //            int expectedLength) {
    //        this(code, description, devices, parameterType, recordLength, maxRecords, 0, 0, commandType,
    //            expectedLength);
    //    }
    //
    //
//    constructor(
//        code: Int, description: String, devices: MedtronicDeviceType?,
//        parameterType: MinimedCommandParameterType?, cmd_params: ByteArray
//    ) : this(code, description, devices, parameterType, 0, 1, 0, 0, 11, 0) {
//        this.commandParameters = cmd_params
//        this.commandParametersCount = cmd_params.size
//    }


    constructor(
        code: Int, description: String, devices: MedtronicDeviceType?,  //
        parameterType: MinimedCommandParameterType?, command: MedLinkCommandType?
    ) : this(code, description, devices, parameterType, 64, 1, 0, null, command)


    // NEW
    //    MedlinkMedtronicCommandType(int code, String description, MedtronicDeviceType devices,
    //                         MinimedCommandParameterType parameterType, int recordLength, int maxRecords, int commandType) {
    //        this(code, description, devices, parameterType, recordLength, maxRecords, 0, null);
    //    }
    // NEW
    constructor(
        code: Int,
        description: String,
        devices: MedtronicDeviceType?,  //
        parameterType: MinimedCommandParameterType?,
        expectedLength: Int,
        command: MedLinkCommandType?
    ) : this(code, description, devices, parameterType, 64, 1, expectedLength, null, command)

    // NEW
    constructor(
        code: Int, description: String, devices: MedtronicDeviceType?,  //
        parameterType: MinimedCommandParameterType?, expectedLength: Int,
        resourceId: Int, command: MedLinkCommandType?
    ) : this(code, description, devices, parameterType, 64, 1, expectedLength, resourceId, command)

    // NEW

    constructor(
        code: Int,
        description: String,
        devices: MedtronicDeviceType?,  //
        parameterType: MinimedCommandParameterType?,
        recordLength: Int,
        max_recs: Int,
        expectedLength: Int,
        resourceId: Int?,
        command: MedLinkCommandType? = null
    ) {
        this.command = command
        this.commandCode = code.toByte()
        this.commandDescription = description
        this.devices = devices
        this.recordLength = recordLength
        this.maxRecords = max_recs
        this.resourceId = resourceId

        this.commandParametersCount = 0
        this.allowedRetries = 2
        this.parameterType = parameterType
        this.expectedLength = expectedLength

        if (this.parameterType == MinimedCommandParameterType.SubCommands) {
            this.minimalBufferSizeToStartReading = 200
        }
    }

    @Deprecated("")
    constructor(
        code: Int,
        description: String,
        devices: MedtronicDeviceType?,  //
        parameterType: MinimedCommandParameterType?,
        recordLength: Int,
        max_recs: Int,
//        addy: Int,  //
//        addy_len: Int,
        cmd_type: Int,
        expectedLength: Int
    ) {
        this.commandCode = code.toByte()
        this.commandDescription = description
        //this.targetType = targetType;
        this.devices = devices
        this.recordLength = recordLength
        this.maxRecords = max_recs

        this.command_type = cmd_type
        this.commandParametersCount = 0
        this.allowedRetries = 2
        this.parameterType = parameterType
        this.expectedLength = expectedLength

        if (this.parameterType == MinimedCommandParameterType.SubCommands) {
            this.minimalBufferSizeToStartReading = 200
        }
    }

    constructor(
        code: Int,
        description: String,
        devices: MedtronicDeviceType?,
        parameterType: MinimedCommandParameterType?,
        recordLength: Int = 0
    ) : this(code, description, devices, parameterType, recordLength, null)

    fun getByCode(code: Byte): MedLinkMedtronicCommandType? {
        if (mapByCode.containsKey(code.toString())) {
            return mapByCode.get(code.toString())
        } else {
            return MedLinkMedtronicCommandType.InvalidCommand
        }
    }


    val fullCommandDescription: String
        /**
         * Get Full Command Description
         *
         * @return command description
         */
        get() = ("Command [name=" + this.name + ", id=" + this.commandCode + ",description=" + this.commandDescription
                + "] ")


    fun canReturnData(): Boolean {
        println("CanReturnData: ]id=" + this.name + "max=" + this.maxRecords + "recLen=" + recordLength)
        return (this.maxRecords * this.recordLength) > 0
    }


//    fun getCommand(): MedLinkCommandType? {
//        return command
//    }

    fun getCommandParametersCountMet(): Int {
        if (this.commandParameters == null) {
            return 0
        } else {
            return this.commandParameters!!.size
        }
    }


    fun hasCommandParameters(): Boolean {
        return (getCommandParametersCountMet() > 0)
    }


    override fun toString(): String {
        return name
    }


    enum class MinimedCommandParameterType {
        NoParameters,  //
        FixedParameters,  //
        SubCommands //
    }

    companion object {
        var mapByCode: MutableMap<String, MedLinkMedtronicCommandType?>

        init {
            RFPowerOn.maxAllowedTime = 17000
            RFPowerOn.allowedRetries = 0
            RFPowerOn.recordLength = 0
            RFPowerOn.minimalBufferSizeToStartReading = 1

            mapByCode = HashMap<String, MedLinkMedtronicCommandType?>()

            for (medtronicCommandType in entries) {
                println(medtronicCommandType.toString())

                if (medtronicCommandType.command != null) {
                    val command = medtronicCommandType.command
                    val code = command?.code
                    if(code !=null)
                        mapByCode[code] = medtronicCommandType
                }
            }
        }

        private fun getDeviceTypesArray(vararg types: MedtronicDeviceType?): HashMap<MedtronicDeviceType?, String?> {
            val hashMap = HashMap<MedtronicDeviceType?, String?>()

            for (type in types) {
                hashMap.put(type, null)
            }

            return hashMap
        }


        private fun getByteArray(vararg data: Int): ByteArray {
            val array = ByteArray(data.size)

            for (i in data.indices) {
                array[i] = data[i].toByte()
            }

            return array
        }


        private fun getIntArray(vararg data: Int): IntArray? {
            return data
        }


        fun constructMessageBody(
            messageType: MedLinkMedtronicCommandType,
            bodyData: ByteArray
        ): MessageBody {
            when (messageType) {
                MedLinkMedtronicCommandType.Bolus -> return PumpAckMessageBody(bodyData)
                else -> return UnknownMessageBody(bodyData)
            }
        }

        //TODO check if is necessary to adapt to the new firmware pumps
        @JvmStatic
        fun getSettings(medtronicPumpModel: MedLinkMedtronicDeviceType?): MedLinkMedtronicCommandType {
            if (MedLinkMedtronicDeviceType.isSameDevice(
                    medtronicPumpModel,
                    MedLinkMedtronicDeviceType.MedLinkMedtronic_512_712
                )
            ) return MedLinkMedtronicCommandType.Settings_512
            else return MedLinkMedtronicCommandType.Settings
        }
    }
}
