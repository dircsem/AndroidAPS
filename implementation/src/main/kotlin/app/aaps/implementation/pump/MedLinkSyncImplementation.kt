// package info.nightscout.androidaps.plugins
//
// import app.aaps.core.interfaces.logging.AAPSLogger
// import app.aaps.core.interfaces.pump.DetailedBolusInfo
// import app.aaps.core.interfaces.pump.MedLinkSync
// import app.aaps.implementation.R
// import com.google.gson.Gson
// import com.google.gson.reflect.TypeToken
// import javax.inject.Inject
//
// class MedLinkSyncImplementation @Inject constructor(
//     val aapsLogger: AAPSLogger,
//
// ) : MedLinkSync {
//
//     val store = loadStore()
//     override fun connectNewPump(endRunning: Boolean, pumpId: String) {
//         TODO("Not yet implemented")
//     }
//
//     override fun addConfigWithTempId(timestamp: Long, frequency: Int, temporaryId: Long): Boolean {
//         val config = MedLinkSync.PumpState.MedLinkConfig(referenceId = timestamp, currentFrequency = frequency)
//         repository.runTransactionForResult(InsertOrUpdateMedLinkConfigTransaction(config))
//             .doOnError { aapsLogger.error(LTag.DATABASE, "Error while saving MedLinkConfig", it) }
//             .blockingGet()
//             .also { result ->
//                 result.inserted.forEach { aapsLogger.debug(LTag.DATABASE, "Inserted MedLinkConfig $it") }
//                 return result.inserted.size > 0
//             }
//     }
//
//     override fun findLatestConfig(): InMemoryMedLinkConfig {
//         val lastDbMedLinkConfig = repository.getLastConfig().blockingGet()
//         return if (lastDbMedLinkConfig is ValueWrapper.Existing) InMemoryMedLinkConfig(lastDbMedLinkConfig.value) else {
//             InMemoryMedLinkConfig(
//                 id = System.currentTimeMillis(),
//                 currentFrequency = 0
//             )
//         }
//     }
//
//     override fun findMostCommonFrequencies(): List<Int> {
//         val values = repository.getMostCommonFrequencies().blockingGet()
//         return if (values is ValueWrapper.Existing) values.value else emptyList()
//
//     }
//  private fun saveStore() {
//         var lastTwoEntries = store
//         // Only save last two entries, to avoid too much data in preferences
//         if (store.size > 2) {
//             lastTwoEntries = ArrayList(store.subList(store.size - 2, store.size))
//         }
//         val jsonString = Gson().toJson(lastTwoEntries)
//         sp.putString(rh.gs(R.string.key_bolus_storage), jsonString)
//     }
//
//     private fun loadStore(): ArrayList<InMemoryMedLinkConfig> {
//         val jsonString = sp.getString(rh.gs(R.string.key_bolus_storage), "")
//         return if (jsonString.isNotEmpty()) {
//             val type = object : TypeToken<List<DetailedBolusInfo>>() {}.type
//             Gson().fromJson(jsonString, type)
//         } else {
//             ArrayList()
//         }
//     }
// }