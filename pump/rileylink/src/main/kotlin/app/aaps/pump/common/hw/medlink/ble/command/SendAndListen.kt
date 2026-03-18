package app.aaps.pump.common.hw.medlink.ble.command

import app.aaps.pump.common.hw.medlink.ble.data.RadioPacket
import app.aaps.pump.common.hw.medlink.service.MedLinkServiceData
import app.aaps.pump.common.hw.rileylink.ble.command.RileyLinkCommand
import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkCommandType
import dagger.android.HasAndroidInjector


class SendAndListen(
    val medLinkServiceData: MedLinkServiceData,
    val sendChannel: Byte,
    val repeatCount: Byte,
    val delayBetweenPackets_ms: Int,
    val listenChannel: Byte,
    val timeout_ms: Int,
    val retryCount: Byte,
    val preambleExtension_ms: Int?,
    val packetToSend: RadioPacket?

) : RileyLinkCommand() {



    constructor(
        medLinkServiceData: MedLinkServiceData,
        sendChannel: Byte,
        repeatCount: Byte,
        delayBetweenPackets_ms: Byte,
        listenChannel: Byte,
        timeout_ms: Int,
        retryCount: Byte,
        packetToSend: RadioPacket?

    ) : this(
        medLinkServiceData,
        sendChannel,
        repeatCount,
        delayBetweenPackets_ms.toInt(),
        listenChannel,
        timeout_ms,
        retryCount,
        null,
        packetToSend
    )


    public override fun getCommandType(): RileyLinkCommandType {
        return RileyLinkCommandType.SendAndListen
    }


    public override fun getRaw(): ByteArray {
        // If firmware version is not set (error reading version from device, shouldn't happen),
        // we will default to version 2
//        boolean isPacketV2 = medLinkServiceData.firmwareVersion == null || RileyLinkFirmwareVersion.Version2AndHigher.getFamilyMembers().contains(medLinkServiceData.firmwareVersion));

//        ArrayList<Byte> bytes = new ArrayList<Byte>();
//        bytes.add(this.getCommandType().getCode());
//        bytes.add(this.sendChannel);
//        bytes.add(this.repeatCount);
//
//        if (isPacketV2) { // delay is unsigned 16-bit integer
//            byte[] delayBuff = ByteBuffer.allocate(4).putInt(delayBetweenPackets_ms).array();
//            bytes.add(delayBuff[2]);
//            bytes.add(delayBuff[3]);
//        } else {
//            bytes.add((byte)delayBetweenPackets_ms);
//        }
//
//        bytes.add(this.listenChannel);

//        byte[] timeoutBuff = ByteBuffer.allocate(4).putInt(timeout_ms).array();

//        bytes.add(timeoutBuff[0]);
//        bytes.add(timeoutBuff[1]);
//        bytes.add(timeoutBuff[2]);
//        bytes.add(timeoutBuff[3]);
//
//        bytes.add(retryCount);
//
//        if (isPacketV2) { // 2.x (and probably higher versions) support preamble extension
//            byte[] preambleBuf = ByteBuffer.allocate(4).putInt(preambleExtension_ms).array();
//            bytes.add(preambleBuf[2]);
//            bytes.add(preambleBuf[3]);
//        }
//        ArrayList<Byte> bytes2 = new ArrayList<Byte>();

        return ByteArray(0)
    }
}
