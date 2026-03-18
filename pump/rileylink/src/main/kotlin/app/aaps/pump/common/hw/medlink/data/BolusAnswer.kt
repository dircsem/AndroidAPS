package app.aaps.pump.common.hw.medlink.data

import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.pump.common.hw.medlink.PumpResponses


/**
 * Created by Dirceu on 01/06/21.
 */
class BolusAnswer {
    val response: PumpResponses
    private var carbs = 0.0
    private var bolusAmount = 0.0
    var delivered: Double = 0.0
        private set
    var answer: String? = null

    lateinit var detailedBolusInfo: DetailedBolusInfo
        private set

    constructor(
        response: PumpResponses, bolusAmount: Double,
        info: DetailedBolusInfo
    ) {
        this.response = response
        this.bolusAmount = bolusAmount
        this.detailedBolusInfo = info
    }


    constructor(response: PumpResponses, answer: String, info: DetailedBolusInfo) {
        this.response = response
        this.answer = answer
        this.detailedBolusInfo = info
    }

    constructor(response: PumpResponses, delivered: Double, answer: String, carbs: Double) {
        this.response = response
        this.delivered = delivered
        this.answer = answer
        this.carbs = carbs
    }
}
