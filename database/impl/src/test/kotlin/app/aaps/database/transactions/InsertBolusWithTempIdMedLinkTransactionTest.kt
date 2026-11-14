package app.aaps.database.transactions

import app.aaps.database.DelegatedAppDatabase
import app.aaps.database.daos.BolusDao
import app.aaps.database.daos.delegated.DelegatedBolusDao
import app.aaps.database.entities.Bolus
import app.aaps.database.entities.embedments.InsulinConfiguration
import app.aaps.database.entities.embedments.InterfaceIDs
import app.aaps.database.entities.interfaces.DBEntry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class InsertBolusWithTempIdMedLinkTransactionTest {

    private lateinit var database: DelegatedAppDatabase
    private lateinit var bolusDao: BolusDao
    private val changes = mutableListOf<DBEntry>()

    @BeforeEach
    fun setup() {
        bolusDao = mock()
        database = mock()
        changes.clear()
        // the real delegated dao: it puts every tracked insert / update into the change list
        whenever(database.bolusDao).thenReturn(DelegatedBolusDao(changes, bolusDao))
    }

    @Test
    fun `new bolus is inserted and tracked as a change so the bolus changed event is sent`() = runTest {
        val bolus = createBolus(tempId = 500L, amount = 0.2)
        whenever(bolusDao.findByPumpTempIds(500L, InterfaceIDs.PumpType.MEDLINK_MEDTRONIC_554_754_VEO, "ABC123")).thenReturn(null)
        whenever(bolusDao.insertNewEntry(bolus)).thenReturn(1L)

        val result = runTransaction(bolus)

        assertThat(result.inserted).containsExactly(bolus)
        assertThat(bolus.id).isEqualTo(1L)
        assertThat(changes).containsExactly(bolus)
        verify(bolusDao, never()).insert(any())
    }

    @Test
    fun `bolus already stored with the same temp id is not inserted again`() = runTest {
        val bolus = createBolus(tempId = 500L, amount = 0.2)
        val existing = createBolus(tempId = 500L, amount = 0.2)
        whenever(bolusDao.findByPumpTempIds(500L, InterfaceIDs.PumpType.MEDLINK_MEDTRONIC_554_754_VEO, "ABC123")).thenReturn(existing)

        val result = runTransaction(bolus)

        assertThat(result.inserted).isEmpty()
        assertThat(changes).isEmpty()
        verify(bolusDao, never()).insertNewEntry(any())
        verify(bolusDao, never()).insert(any())
    }

    private suspend fun runTransaction(bolus: Bolus): InsertBolusWithTempIdMedLinkTransaction.TransactionResult {
        val transaction = InsertBolusWithTempIdMedLinkTransaction(bolus)
        transaction.database = database
        return transaction.run()
    }

    private fun createBolus(tempId: Long, amount: Double): Bolus = Bolus(
        timestamp = System.currentTimeMillis(),
        amount = amount,
        type = Bolus.Type.SMB,
        interfaceIDs_backing = InterfaceIDs(
            temporaryId = tempId,
            pumpType = InterfaceIDs.PumpType.MEDLINK_MEDTRONIC_554_754_VEO,
            pumpSerial = "ABC123"
        ),
        insulinConfiguration = InsulinConfiguration("some", 600000L, 60000L, 1.0)
    )
}
