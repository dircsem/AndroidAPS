package app.aaps.pump.common.hw.medlink.ble

import android.os.AsyncTask
import android.os.SystemClock
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.common.hw.medlink.defs.MedLinkEncodingType
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Created by Dirceu on 06/10/20.
 */
class MedLinkRFSpyReader // xyz setRileyLinkBle(medLinkBle);
internal constructor(private val aapsLogger: AAPSLogger, private var medLinkBle: MedLinkBLE?) {
    private val waitForRadioData = Semaphore(0, true)
    private val mDataQueue = LinkedBlockingQueue<ByteArray?>()
    private val acquireCount = 0
    private var releaseCount = 0
    private val stopAtNull = true


    fun setMedLinkBle(medLinkBle: MedLinkBLE?) {
        if (readerTask != null) {
            readerTask.cancel(true)
        }
        this.medLinkBle = medLinkBle
    }

    fun setMedLinkEncodingType(encodingType: MedLinkEncodingType?) {
        aapsLogger.debug("setRileyLinkEncodingType: " + encodingType)
        //        stopAtNull = !(encodingType == RileyLinkEncodingType.Manchester || //
//                encodingType == MedLinkEncodingType.FourByteSixByteLocal);
    }


    // This timeout must be coordinated with the length of the RFSpy radio operation or Bad Things Happen.
    fun poll(timeout_ms: Int): ByteArray? {
//        aapsLogger.debug(LTag.PUMPBTCOMM, ThreadUtil.sig() + "Entering poll at t==" + SystemClock.uptimeMillis() + ", timeout is " + timeout_ms
//                + " mDataQueue size is " + mDataQueue.size());

        if (mDataQueue.isEmpty()) {
            try {
                // block until timeout or data available.
                // returns null if timeout.
                val dataFromQueue = mDataQueue.poll(timeout_ms.toLong(), TimeUnit.MILLISECONDS)
                if (dataFromQueue != null) {
                    aapsLogger.debug(
                        LTag.PUMPBTCOMM, ("Got data [" + dataFromQueue + "] at t=="
                                + SystemClock.uptimeMillis())
                    )
                } else {
                    aapsLogger.debug(
                        LTag.PUMPBTCOMM,
                        "Got data [null] at t==" + SystemClock.uptimeMillis()
                    )
                }
                return dataFromQueue
            } catch (e: InterruptedException) {
                aapsLogger.error(LTag.PUMPBTCOMM, "poll: Interrupted waiting for data")
            }
        }

        return null
    }


    // Call this from the "response count" notification handler.
    fun newDataIsAvailable() {
        releaseCount++

        //        aapsLogger.debug(LTag.PUMPBTCOMM, ThreadUtil.sig() + "waitForRadioData released(count=" + releaseCount + ") at t="
//                + SystemClock.uptimeMillis());
        waitForRadioData.release()
    }


    fun start() {
        isRunning = true

        aapsLogger.debug(LTag.PUMPBTCOMM, "RFSpyReader starting")
        //        readerTask = new AsyncTask<Void, Void, Void>() {
//
//            @Override
//            protected Void doInBackground(Void... voids) {
//                UUID serviceUUID = UUID.fromString(GattAttributes.SERVICE_UUID);
//                UUID radioDataUUID = UUID.fromString(GattAttributes.GATT_UUID);
//                BLECommOperationResult result;
//                while (isRunning) {
//                    try {
//                        acquireCount++;
//                        aapsLogger.debug(LTag.PUMPBTCOMM, ThreadUtil.sig() + "waitForRadioData before acquired (count=" + acquireCount + ") at t="
//                                + SystemClock.uptimeMillis());
//                        waitForRadioData.acquire();
//                        aapsLogger.debug(LTag.PUMPBTCOMM, ThreadUtil.sig() + "waitForRadioData acquired (count=" + acquireCount + ") at t="
//                                + SystemClock.uptimeMillis());
//                        SystemClock.sleep(100);
//                        SystemClock.sleep(1);
//                        result = medLinkBle.readCharacteristicBlocking(serviceUUID, radioDataUUID);
//                        SystemClock.sleep(100);
//
//                        if (result.resultCode == BLECommOperationResult.RESULT_SUCCESS) {
//                            if (stopAtNull) {
//                                // only data up to the first null is valid
//                                for (int i = 0; i < result.value.length; i++) {
//                                    if (result.value[i] == 0) {
//                                        result.value = ByteUtil.substring(result.value, 0, i);
//                                        break;
//                                    }
//                                }
//                            }
//                            mDataQueue.add(result.value);
//                        } else if (result.resultCode == BLECommOperationResult.RESULT_INTERRUPTED) {
//                            aapsLogger.error(LTag.PUMPBTCOMM, "Read operation was interrupted");
//                        } else if (result.resultCode == BLECommOperationResult.RESULT_TIMEOUT) {
//                            aapsLogger.error(LTag.PUMPBTCOMM, "Read operation on Radio Data timed out");
//                        } else if (result.resultCode == BLECommOperationResult.RESULT_BUSY) {
//                            aapsLogger.error(LTag.PUMPBTCOMM, "FAIL: RileyLinkBLE reports operation already in progress");
//                        } else if (result.resultCode == BLECommOperationResult.RESULT_NONE) {
//                            aapsLogger.error(LTag.PUMPBTCOMM, "FAIL: got invalid result code: " + result.resultCode);
//                        }
//                    } catch (InterruptedException e) {
//                        aapsLogger.error(LTag.PUMPBTCOMM, "Interrupted while waiting for data");
//                    }
//                }
//                return null;
//            }
//        }.execute();
    }

    fun stop() {
        isRunning = false
    }

    companion object {
        private val readerTask: AsyncTask<Void?, Void?, Void?>? = null
        private var isRunning = false
    }
}
