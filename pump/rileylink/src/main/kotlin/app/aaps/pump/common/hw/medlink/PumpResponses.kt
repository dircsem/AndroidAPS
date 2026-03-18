package app.aaps.pump.common.hw.medlink

enum class PumpResponses(bolusDelivered: String) {
    StatusFullProcessed("Status complete parse success"),
    StatusPartialProcessed("Status partial parse success"),
    CommandProcessed("ComandProcessed"),
    DeliveringBolus("DeliveringBolus"),
    BolusDelivered("BolusDelivered"),
    UnknownAnswer("UnknowAnswer: "), StatusProcessFailed("Status Process Failed: ");

    @JvmField
    val answer: String = bolusDelivered
}
