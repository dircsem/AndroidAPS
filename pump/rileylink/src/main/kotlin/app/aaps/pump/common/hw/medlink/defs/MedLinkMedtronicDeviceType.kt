package app.aaps.pump.common.hw.medlink.defs

/**
 * Taken from MedtronicDeviceType
 *
 *
 * Author: Dirceu Semighini Filho
 *
 * used by medlink
 */
enum class MedLinkMedtronicDeviceType {
    Unknown_Device,  //

    // Pump
    MedLinkMedtronic_511("511"),  //

    MedLinkMedtronic_512("512"),  //
    MedLinkMedtronic_712("712"),  //
    MedLinkMedtronic_512_712(
        MedLinkMedtronic_512,
        MedLinkMedtronic_712
    ),  //

    MedLinkMedtronic_515("515"),  //
    MedLinkMedtronic_715("715"),  //
    MedLinkMedtronic_515_715(
        MedLinkMedtronic_515,
        MedLinkMedtronic_715
    ),  //

    MedLinkMedtronic_522("522"),  //
    MedLinkMedtronic_722("722"),  //
    MedLinkMedtronic_522_722(
        MedLinkMedtronic_522,
        MedLinkMedtronic_722
    ),  //

    MedLinkMedtronic_523_Revel("523"),  //
    MedLinkMedtronic_723_Revel("723"),  //

    MedLinkMedtronic_554_Veo("554"),  //
    MedLinkMedtronic_754_Veo("754"),  //

    MedLinkMedtronic_512andHigher(
        MedLinkMedtronic_512,
        MedLinkMedtronic_712,
        MedLinkMedtronic_515,
        MedLinkMedtronic_715,
        MedLinkMedtronic_522,
        MedLinkMedtronic_722,  //
        MedLinkMedtronic_523_Revel,
        MedLinkMedtronic_723_Revel,
        MedLinkMedtronic_554_Veo,
        MedLinkMedtronic_754_Veo
    ),  //

    MedLinkMedtronic_515andHigher(
        MedLinkMedtronic_515,
        MedLinkMedtronic_715,
        MedLinkMedtronic_522,
        MedLinkMedtronic_722,
        MedLinkMedtronic_523_Revel,
        MedLinkMedtronic_723_Revel,  //
        MedLinkMedtronic_554_Veo,
        MedLinkMedtronic_754_Veo
    ),  //
    MedLinkMedtronic_522andHigher(
        MedLinkMedtronic_522,
        MedLinkMedtronic_722,
        MedLinkMedtronic_523_Revel,
        MedLinkMedtronic_723_Revel,  //
        MedLinkMedtronic_554_Veo,
        MedLinkMedtronic_754_Veo
    ),  //
    MedLinkMedtronic_523andHigher(
        MedLinkMedtronic_523_Revel,
        MedLinkMedtronic_723_Revel,
        MedLinkMedtronic_554_Veo,  //
        MedLinkMedtronic_754_Veo
    ),  //

    MedLinkMedtronic_554andHigher(
        MedLinkMedtronic_554_Veo,
        MedLinkMedtronic_754_Veo
    ),  //


    //
    All;

    var pumpModel: String? = null
        private set

    //    public static boolean isLargerFormat(MedtronicDeviceType model) {
    //        return isSameDevice(model, Medtronic_523andHigher);
    //    }
    var isFamily: Boolean
        private set
    lateinit var familyMembers: Array<MedLinkMedtronicDeviceType?>
        private set


    constructor(pumpModel: String?) {
        this.isFamily = false
        this.pumpModel = pumpModel
    }


    constructor(vararg familyMembers: MedLinkMedtronicDeviceType?) {

        this.familyMembers =
            familyMembers.toList().toTypedArray()


        this.isFamily = true
    }


    val isMedtronic_523orHigher: Boolean
        //    public boolean isLargerFormat() {
        get() = isSameDevice(
            this,
            MedLinkMedtronic_523andHigher
        )


    val bolusStrokes: Int
        get() = if (this.isMedtronic_523orHigher) 40 else 10


    companion object {
        var mapByDescription: MutableMap<String?, MedLinkMedtronicDeviceType?>

        init {
            mapByDescription = HashMap<String?, MedLinkMedtronicDeviceType?>()

            for (minimedDeviceType in entries) {
                if (!minimedDeviceType.isFamily) {
                    mapByDescription.put(minimedDeviceType.pumpModel, minimedDeviceType)
                }
            }
        }

        @JvmStatic
        fun isSameDevice(
            deviceWeCheck: MedLinkMedtronicDeviceType?,
            deviceSources: MedLinkMedtronicDeviceType
        ): Boolean {
            if (deviceSources.isFamily) {
                for (mdt in deviceSources.familyMembers!!) {
                    if (mdt == deviceWeCheck) return true
                }
            } else {
                return (deviceWeCheck == deviceSources)
            }

            return false
        }


        fun getByDescription(desc: String?): MedLinkMedtronicDeviceType? {
            if (mapByDescription.containsKey(desc)) {
                return mapByDescription.get(desc)
            } else {
                return Unknown_Device
            }
        }
    }
}