package app.aaps.pump.common.hw.medlink.ble.data

import app.aaps.pump.common.hw.medlink.MedLinkUtil
import dagger.android.HasAndroidInjector
import javax.inject.Inject

/**
 * Created by dirceu on 10/12/20.
 * copied from rileylink RadioPacket
 */
class RadioPacket(injector: HasAndroidInjector, pkt: ByteArray?) {
    @Inject
    lateinit var medLinkUtil: MedLinkUtil

    //        return null;
//        switch ((MedLinkEncodingType)medLinkUtil.getEncoding()) {
//            case Manchester: { // We have this encoding in RL firmware
//                return pkt;
//            }

//            case FourByteSixByteLocal: {
//                byte[] withCRC = getWithCRC();
//
//                byte[] encoded = medLinkUtil.getEncoding4b6b().encode4b6b(withCRC);
//                return ByteUtil.concat(encoded, (byte) 0);
//            }

    //            default:
//                throw new NotImplementedException(("Encoding not supported: " + medLinkUtil.getEncoding().toString()));
    val encoded: ByteArray?


    init {
        injector.androidInjector().inject(this)
        this.encoded = pkt
    }


    private val withCRC: ByteArray
        get() {
            val withCRC = ByteArray(0)
            //                ByteUtil.concat(pkt, CRC.crc8(pkt));
            return withCRC
        }
}
