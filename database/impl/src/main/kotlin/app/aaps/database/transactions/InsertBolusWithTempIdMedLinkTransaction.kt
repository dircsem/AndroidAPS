package app.aaps.database.transactions

import app.aaps.database.entities.Bolus

/**
 * MedLink: the same as [InsertBolusWithTempIdTransaction], but the new bolus is inserted with `insertNewEntry`.
 * So the insert is tracked as a change and the "bolus changed" event is sent: the Treatments screen and the IOB
 * cache see the bolus at once. Other pumps get this event later, when they update the row from the pump history;
 * MedLink never updates this row. Other pumps keep using [InsertBolusWithTempIdTransaction] (upstream, unchanged).
 */
class InsertBolusWithTempIdMedLinkTransaction(
    private val bolus: Bolus
) : Transaction<InsertBolusWithTempIdMedLinkTransaction.TransactionResult>() {

    override suspend fun run(): TransactionResult {
        bolus.interfaceIDs.temporaryId ?: bolus.interfaceIDs.pumpType ?: bolus.interfaceIDs.pumpSerial ?: throw IllegalStateException("Some pump ID is null")
        val result = TransactionResult()
        val current = database.bolusDao.findByPumpTempIds(bolus.interfaceIDs.temporaryId!!, bolus.interfaceIDs.pumpType!!, bolus.interfaceIDs.pumpSerial!!)
        if (current == null) {
            bolus.id = database.bolusDao.insertNewEntry(bolus)
            result.inserted.add(bolus)
        }
        return result
    }

    class TransactionResult {

        val inserted = mutableListOf<Bolus>()
    }
}
