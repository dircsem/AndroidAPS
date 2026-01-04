package app.aaps.database.impl.transactions

import app.aaps.database.entities.MedLinkConfig


// class InsertOrUpdateMedLinkConfigTransaction(private val medLinkConfig: MedLinkConfig) : Transaction<InsertOrUpdateMedLinkConfigTransaction.TransactionResult>() {
//
//     class TransactionResult {
//
//         val inserted = mutableListOf<MedLinkConfig>()
//         val updated = mutableListOf<MedLinkConfig>()
//     }
//
//     override fun run(): TransactionResult {
//         val result = TransactionResult()
//         val current = database.medLinkConfigDao.findById(medLinkConfig.id)
//         if (current == null) {
//             database.medLinkConfigDao.insertNewEntry(medLinkConfig)
//             result.inserted.add(medLinkConfig)
//         } else {
//             database.medLinkConfigDao.updateExistingEntry(medLinkConfig)
//             result.updated.add(medLinkConfig)
//         }
//         return result
//     }
// }