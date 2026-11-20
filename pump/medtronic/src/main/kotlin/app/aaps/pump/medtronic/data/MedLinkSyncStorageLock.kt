package app.aaps.pump.medtronic.data

import app.aaps.pump.common.sync.PumpSyncStorage

/**
 * One lock for every MedLink use of [PumpSyncStorage] (`pump/common`, upstream code, not changed on purpose).
 *
 * Its bolus and TBR lists are plain lists, and the save walks the whole list. Night 2026-11-19 03:21:01: the STOP
 * answer added a TBR while the loop TBR command added one and saved: `ConcurrentModificationException`, the TBR
 * command failed. The lock is the storage object itself, the same as its `@Synchronized initStorage()`.
 *
 * Writers (add, remove, save) run inside the lock. Readers that go on working while they write to the database take a
 * copy inside the lock (`locked { getTBRs().toMutableList() }`), so the lock is never held for long.
 */
internal inline fun <T> PumpSyncStorage.locked(block: PumpSyncStorage.() -> T): T = synchronized(this) { block() }
