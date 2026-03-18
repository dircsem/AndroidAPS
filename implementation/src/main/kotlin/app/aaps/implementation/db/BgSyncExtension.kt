package app.aaps.implementation.db

import app.aaps.database.entities.GlucoseValue
import app.aaps.core.interfaces.source.BgSync
import app.aaps.core.interfaces.source.BgSync.BgArrow.DOUBLE_DOWN
import app.aaps.core.interfaces.source.BgSync.BgArrow.DOUBLE_UP
import app.aaps.core.interfaces.source.BgSync.BgArrow.FLAT
import app.aaps.core.interfaces.source.BgSync.BgArrow.FORTY_FIVE_DOWN
import app.aaps.core.interfaces.source.BgSync.BgArrow.FORTY_FIVE_UP
import app.aaps.core.interfaces.source.BgSync.BgArrow.NONE
import app.aaps.core.interfaces.source.BgSync.BgArrow.SINGLE_DOWN
import app.aaps.core.interfaces.source.BgSync.BgArrow.SINGLE_UP
import app.aaps.core.interfaces.source.BgSync.BgArrow.TRIPLE_DOWN
import app.aaps.core.interfaces.source.BgSync.BgArrow.TRIPLE_UP
import app.aaps.core.interfaces.source.BgSync.GlucoseUnit
import app.aaps.core.interfaces.source.BgSync.GlucoseUnit.MGDL
import app.aaps.core.interfaces.source.BgSync.GlucoseUnit.MMOL
import app.aaps.core.interfaces.source.BgSync.SourceSensor.A_COB_PREDICTION
import app.aaps.core.interfaces.source.BgSync.SourceSensor.COB_PREDICTION
import app.aaps.core.interfaces.source.BgSync.SourceSensor.DEXCOM_G6_NATIVE
import app.aaps.core.interfaces.source.BgSync.SourceSensor.DEXCOM_G6_NATIVE_XDRIP
import app.aaps.core.interfaces.source.BgSync.SourceSensor.DEXCOM_NATIVE_UNKNOWN
import app.aaps.core.interfaces.source.BgSync.SourceSensor.EVERSENSE
import app.aaps.core.interfaces.source.BgSync.SourceSensor.GLUNOVO_NATIVE
import app.aaps.core.interfaces.source.BgSync.SourceSensor.IOB_PREDICTION
import app.aaps.core.interfaces.source.BgSync.SourceSensor.LIBRE_1_BLUCON
import app.aaps.core.interfaces.source.BgSync.SourceSensor.LIBRE_1_BLUE
import app.aaps.core.interfaces.source.BgSync.SourceSensor.LIBRE_1_LIMITTER
import app.aaps.core.interfaces.source.BgSync.SourceSensor.LIBRE_1_NET
import app.aaps.core.interfaces.source.BgSync.SourceSensor.LIBRE_1_PL
import app.aaps.core.interfaces.source.BgSync.SourceSensor.LIBRE_1_RF
import app.aaps.core.interfaces.source.BgSync.SourceSensor.LIBRE_1_TOMATO
import app.aaps.core.interfaces.source.BgSync.SourceSensor.LIBRE_2_NATIVE
import app.aaps.core.interfaces.source.BgSync.SourceSensor.MEDTRUM_A6
import app.aaps.core.interfaces.source.BgSync.SourceSensor.MM_600_SERIES
import app.aaps.core.interfaces.source.BgSync.SourceSensor.MM_ENLITE
import app.aaps.core.interfaces.source.BgSync.SourceSensor.POCTECH_NATIVE
import app.aaps.core.interfaces.source.BgSync.SourceSensor.RANDOM
import app.aaps.core.interfaces.source.BgSync.SourceSensor.UAM_PREDICTION
import app.aaps.core.interfaces.source.BgSync.SourceSensor.UNKNOWN
import app.aaps.core.interfaces.source.BgSync.SourceSensor.ZT_PREDICTION


fun BgSync.BgArrow.toDbType(): GlucoseValue.TrendArrow =
    when (this) {
        NONE -> GlucoseValue.TrendArrow.NONE
        TRIPLE_UP -> GlucoseValue.TrendArrow.TRIPLE_UP
        DOUBLE_UP -> GlucoseValue.TrendArrow.DOUBLE_UP
        SINGLE_UP -> GlucoseValue.TrendArrow.SINGLE_UP
        FORTY_FIVE_UP -> GlucoseValue.TrendArrow.FORTY_FIVE_UP
        FLAT -> GlucoseValue.TrendArrow.FLAT
        FORTY_FIVE_DOWN -> GlucoseValue.TrendArrow.FORTY_FIVE_DOWN
        SINGLE_DOWN -> GlucoseValue.TrendArrow.SINGLE_DOWN
        DOUBLE_DOWN -> GlucoseValue.TrendArrow.DOUBLE_DOWN
        TRIPLE_DOWN -> GlucoseValue.TrendArrow.TRIPLE_DOWN
    }

fun BgSync.SourceSensor.toDbType(): GlucoseValue.SourceSensor =
    when (this) {
        DEXCOM_NATIVE_UNKNOWN -> GlucoseValue.SourceSensor.DEXCOM_NATIVE_UNKNOWN
        DEXCOM_G6_NATIVE -> GlucoseValue.SourceSensor.DEXCOM_G6_NATIVE
        MEDTRUM_A6 -> GlucoseValue.SourceSensor.MEDTRUM_A6
        DEXCOM_G6_NATIVE_XDRIP -> GlucoseValue.SourceSensor.DEXCOM_G6_NATIVE_XDRIP
        LIBRE_1_NET -> GlucoseValue.SourceSensor.LIBRE_1_NET
        LIBRE_1_BLUE -> GlucoseValue.SourceSensor.LIBRE_1_BLUE
        LIBRE_1_PL -> GlucoseValue.SourceSensor.LIBRE_1_PL
        LIBRE_1_BLUCON -> GlucoseValue.SourceSensor.LIBRE_1_BLUCON
        LIBRE_1_TOMATO -> GlucoseValue.SourceSensor.LIBRE_1_TOMATO
        LIBRE_1_RF -> GlucoseValue.SourceSensor.LIBRE_1_RF
        LIBRE_1_LIMITTER -> GlucoseValue.SourceSensor.LIBRE_1_LIMITTER
        LIBRE_2_NATIVE -> GlucoseValue.SourceSensor.LIBRE_2_NATIVE
        POCTECH_NATIVE -> GlucoseValue.SourceSensor.POCTECH_NATIVE
        GLUNOVO_NATIVE -> GlucoseValue.SourceSensor.GLUNOVO_NATIVE
        MM_600_SERIES -> GlucoseValue.SourceSensor.MM_600_SERIES
        EVERSENSE -> GlucoseValue.SourceSensor.EVERSENSE
        RANDOM -> GlucoseValue.SourceSensor.RANDOM
        UNKNOWN -> GlucoseValue.SourceSensor.UNKNOWN
        IOB_PREDICTION -> GlucoseValue.SourceSensor.IOB_PREDICTION
        A_COB_PREDICTION -> GlucoseValue.SourceSensor.A_COB_PREDICTION
        COB_PREDICTION -> GlucoseValue.SourceSensor.COB_PREDICTION
        UAM_PREDICTION -> GlucoseValue.SourceSensor.UAM_PREDICTION
        ZT_PREDICTION -> GlucoseValue.SourceSensor.ZT_PREDICTION
        MM_ENLITE -> GlucoseValue.SourceSensor.MM_ENLITE
    }

fun BgSync.GlucoseUnit.toDbType(): app.aaps.database.entities.data.GlucoseUnit =
    when (this) {
        MGDL -> app.aaps.database.entities.data.GlucoseUnit.MGDL
        MMOL -> app.aaps.database.entities.data.GlucoseUnit.MMOL
    }
