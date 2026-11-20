package app.aaps.pump.medtronic.data

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.common.sync.PumpDbEntryTBR
import app.aaps.pump.common.sync.PumpSyncStorage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Night 2026-11-19 03:21:01: the STOP answer added a TBR to [PumpSyncStorage] while the loop TBR command added one and
 * saved the list: `ConcurrentModificationException` in `saveStorageTBR`, the TBR command failed. With [locked] the
 * two threads wait for each other.
 */
class MedLinkSyncStorageLockTest {

    private val storage = PumpSyncStorage(mock<PumpSync>(), mock<Preferences>(), mock<AAPSLogger>())

    private fun entry(i: Long) = PumpDbEntryTBR(
        temporaryId = i, date = i, pumpType = PumpType.GENERIC_AAPS, serialNumber = "1", pumpId = null,
        rate = 0.0, isAbsolute = true, durationInSeconds = 1800, tbrType = PumpSync.TemporaryBasalType.NORMAL
    )

    @Test
    fun `two threads add and save the TBR list at the same time - no error, nothing lost`() {
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val start = CountDownLatch(1)
        val each = 300
        val threads = (0 until 2).map { t ->
            thread {
                start.await()
                try {
                    repeat(each) { i ->
                        storage.locked {
                            pumpSyncStorageTBR.add(entry(t * 10_000L + i))
                            saveStorageTBR()
                        }
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertTrue(errors.isEmpty(), "errors: $errors")
        assertEquals(2 * each, storage.locked { pumpSyncStorageTBR.size })
    }

    @Test
    fun `a copy taken under the lock does not change when the list changes later`() {
        storage.locked { pumpSyncStorageTBR.add(entry(1)) }
        val copy = storage.locked { getTBRs().toMutableList() }
        storage.locked { pumpSyncStorageTBR.add(entry(2)) }
        assertEquals(1, copy.size)
        assertEquals(2, storage.locked { pumpSyncStorageTBR.size })
    }
}
