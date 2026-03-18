package app.aaps.pump.common.defs

enum class PumpRunningState(val status: String) {

    Running("normal"),
    Suspended("suspended"),
    TempBasalSuspended("tempBasalSuspended"),
    Unknow("unknow");

    fun isSuspended(): Boolean {
        return status == "suspended" || status == "tempBasalSuspended" || status == "unknow"
    }
}