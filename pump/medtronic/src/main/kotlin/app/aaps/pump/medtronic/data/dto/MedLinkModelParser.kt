package app.aaps.pump.medtronic.data.dto

import app.aaps.pump.common.hw.medlink.activities.MedLinkStandardReturn
import app.aaps.pump.common.hw.medlink.defs.MedLinkMedtronicDeviceType
import java.util.function.Supplier
import java.util.stream.Stream

/**
 * Created by Dirceu on 03/01/21.
 */
object MedLinkModelParser {
    fun parse(connection: Supplier<Stream<String>>): MedLinkStandardReturn<MedLinkMedtronicDeviceType> {
        val modelOpt =
            connection.get().filter { f: String? -> f!!.startsWith("medtronic") }.findFirst()
        if (modelOpt.isPresent) {
            if (modelOpt.get() == "medtronic veo") {
                return MedLinkStandardReturn<MedLinkMedtronicDeviceType>(
                    connection,
                    MedLinkMedtronicDeviceType.MedLinkMedtronic_554_Veo
                )
            } else if (modelOpt.get() == "medtronic 722") {
                return MedLinkStandardReturn<MedLinkMedtronicDeviceType>(
                    connection,
                    MedLinkMedtronicDeviceType.MedLinkMedtronic_512_712
                )
            } else {
                return MedLinkStandardReturn(
                    connection,
                    MedLinkMedtronicDeviceType.Unknown_Device,
                    MedLinkStandardReturn.ParsingError.ModelParsingError
                )
            }
        } else {
            return MedLinkStandardReturn(
                connection,
                MedLinkMedtronicDeviceType.Unknown_Device,
                MedLinkStandardReturn.ParsingError.ModelParsingError
            )
        }
    }
}
