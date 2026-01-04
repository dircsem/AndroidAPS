package app.aaps.pump.common.defs

enum class PumpRunningState(val status: String) {

    Running("normal"),
    Suspended("suspended");
}