package info.nightscout.androidaps.plugins.pump.common.hw.medlink.ble;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import info.nightscout.androidaps.data.Intervals;
import info.nightscout.androidaps.logging.LTag;
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.MedLinkConst;
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.MedLinkUtil;
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.defs.MedLinkCommandType;
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.service.MedLinkServiceData;
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.RileyLinkConst;
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.ble.RileyLinkBLE;
import info.nightscout.androidaps.plugins.pump.common.hw.medlink.ble.data.GattAttributes;
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.ble.operations.BLECommOperationResult;
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.ble.operations.CharacteristicReadOperation;
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.defs.RileyLinkError;
import info.nightscout.androidaps.plugins.pump.common.hw.rileylink.defs.RileyLinkServiceState;
import info.nightscout.androidaps.plugins.pump.common.utils.ByteUtil;
import info.nightscout.androidaps.plugins.pump.common.utils.StringUtil;
import info.nightscout.androidaps.plugins.pump.common.utils.ThreadUtil;

/**
 * Created by Dirceu on 30/09/20.
 */
@Singleton
public class MedLinkBLE extends RileyLinkBLE {

    @Inject
    MedLinkUtil medLinkUtil;
    @Inject
    MedLinkServiceData medLinkServiceData;

    private boolean isConnected = false;
    private String pumpModel = null;
    private List<RemainingCommand> remainingCommands = new ArrayList<>();

    @Inject
    public MedLinkBLE(final Context context) {
        super(context, true);

        bluetoothGattCallback = new BluetoothGattCallback() {
            @Override
            public void onCharacteristicChanged(final BluetoothGatt gatt,
                                                final BluetoothGattCharacteristic characteristic) {
                super.onCharacteristicChanged(gatt, characteristic);
                aapsLogger.debug(LTag.PUMPBTCOMM, "onCharchanged ");
                if (gattDebugEnabled) {
                    aapsLogger.debug(LTag.PUMPBTCOMM, ThreadUtil.sig() + "onCharacteristicChanged "
                            + GattAttributes.lookup(characteristic.getUuid()) + " "
                            + ByteUtil.getHex(characteristic.getValue()) + " "+
                            StringUtil.fromBytes(characteristic.getValue()));
//                    if (characteristic.getUuid().equals(UUID.fromString(GattAttributes.CHARA_RADIO_RESPONSE_COUNT))) {
//                        aapsLogger.debug(LTag.PUMPBTCOMM, "Response Count is " + ByteUtil.shortHexString(characteristic.getValue()));
//                    }
                }
                String answer = new String(characteristic.getValue());
                addResponse(answer);
                aapsLogger.debug("MedLink answer "+answer);
                if(answer.contains("Powerdown") || answer.contains("AT+SLEEP")){
                    aapsLogger.debug("MedLink off "+answer);
                    setConnected(false);
                }
                if(answer.contains("Confirmed") || answer.contains("wake-up")){
                    aapsLogger.debug("MedLink waked "+answer);
                }
                if(answer.contains("Error communication") ){
                    aapsLogger.debug("MedLink waked "+answer);
                    medLinkServiceData.setRileyLinkServiceState(RileyLinkServiceState.RileyLinkError);
                }
                if(answer.contains("Medtronic")){
                    setPumpModel(answer);
                }
                if(answer.toLowerCase().contains("eomeomeom")){
                    String pumpResp = buildResp();
                    aapsLogger.info(LTag.PUMPBTCOMM,pumpResp);
                    medLinkUtil.sendBroadcastMessage(pumpResp.toString(),context);
                }
                if(answer.contains("Wait for response") || answer.contains("OK+CONN")){
                    //medLinkUtil.sendBroadcastMessage();
                    aapsLogger.debug("MedLink answering "+answer);
                    if(!remainingCommands.isEmpty() && remainingCommands.get(0).command != characteristic.getValue()) {
                        writeCharacteristic_blocking(UUID.fromString(GattAttributes.SERVICE_UUID), UUID.fromString(GattAttributes.GATT_UUID), MedLinkCommandType.Connect.getRaw());
                    }
                }
                if(answer.equals("Ready")){
                    setConnected(true);
                    aapsLogger.debug("MedLink Ready");
                    medLinkServiceData.setRileyLinkServiceState(RileyLinkServiceState.RileyLinkReady);
                }
                if (radioResponseCountNotified != null) {
                    radioResponseCountNotified.run();
                }
            }


            @Override
            public void onCharacteristicRead(final BluetoothGatt gatt,
                                             final BluetoothGattCharacteristic characteristic, int status) {
                super.onCharacteristicRead(gatt, characteristic, status);
                aapsLogger.debug(LTag.PUMPBTCOMM, "onCharRead ");
                final String statusMessage = getGattStatusMessage(status);
                if (gattDebugEnabled) {
                    aapsLogger.debug(LTag.PUMPBTCOMM, ThreadUtil.sig() + "onCharacteristicRead ("
                            + GattAttributes.lookup(characteristic.getUuid()) + ") " + statusMessage + ":"
                            + ByteUtil.getHex(characteristic.getValue()));
                }
                mCurrentOperation.gattOperationCompletionCallback(characteristic.getUuid(), characteristic.getValue());
            }


            @Override
            public void onCharacteristicWrite(final BluetoothGatt gatt,
                                              final BluetoothGattCharacteristic characteristic, int status) {
                super.onCharacteristicWrite(gatt, characteristic, status);
                aapsLogger.debug("oncharwrite");
                final String uuidString = GattAttributes.lookup(characteristic.getUuid());
                if (gattDebugEnabled) {
                    aapsLogger.debug(LTag.PUMPBTCOMM, ThreadUtil.sig() + "onCharacteristicWrite " + getGattStatusMessage(status) + " "
                            + uuidString + " " + ByteUtil.shortHexString(characteristic.getValue()));
                }
                mCurrentOperation.gattOperationCompletionCallback(characteristic.getUuid(), characteristic.getValue());
            }


            @Override
            public void onConnectionStateChange(final BluetoothGatt gatt, final int status, final int newState) {
                super.onConnectionStateChange(gatt, status, newState);
                aapsLogger.error(LTag.PUMPBTCOMM, "Statechange "+newState);
                // https://github.com/NordicSemiconductor/puck-central-android/blob/master/PuckCentral/app/src/main/java/no/nordicsemi/puckcentral/bluetooth/gatt/GattManager.java#L117
                if (status == 133) {
                    aapsLogger.error(LTag.PUMPBTCOMM, "Got the status 133 bug, closing gatt");
                    disconnect();
                    SystemClock.sleep(500);
                    return;
                }

                if (gattDebugEnabled) {
                    final String stateMessage;
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        stateMessage = "CONNECTED";
                    } else if (newState == BluetoothProfile.STATE_CONNECTING) {
                        stateMessage = "CONNECTING";
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        stateMessage = "DISCONNECTED";
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTING) {
                        stateMessage = "DISCONNECTING";
                    } else {
                        stateMessage = "UNKNOWN newState (" + newState + ")";
                    }

                    aapsLogger.warn(LTag.PUMPBTCOMM, "onConnectionStateChange " + getGattStatusMessage(status) + " " + stateMessage);
                }

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        medLinkUtil.sendBroadcastMessage(RileyLinkConst.Intents.BluetoothConnected, context);
                    } else {
                        aapsLogger.debug(LTag.PUMPBTCOMM, "BT State connected, GATT status {} ({})", status, getGattStatusMessage(status));
                    }

                } else if ((newState == BluetoothProfile.STATE_CONNECTING) || //
                        (newState == BluetoothProfile.STATE_DISCONNECTING)) {
                    aapsLogger.debug(LTag.PUMPBTCOMM, "We are in {} state.", status == BluetoothProfile.STATE_CONNECTING ? "Connecting" :
                            "Disconnecting");
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    medLinkUtil.sendBroadcastMessage(RileyLinkConst.Intents.RileyLinkDisconnected, context);
                    if (manualDisconnect)
                        close();

                    aapsLogger.warn(LTag.PUMPBTCOMM, "MedLink Disconnected.");
                } else {
                    aapsLogger.warn(LTag.PUMPBTCOMM, "Some other state: (status={},newState={})", status, newState);
                }
            }


            @Override
            public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                super.onDescriptorWrite(gatt, descriptor, status);
                aapsLogger.debug(LTag.PUMPBTCOMM, "onDescriptorWrite ");
                if (gattDebugEnabled) {
                    aapsLogger.warn(LTag.PUMPBTCOMM, "onDescriptorWrite " + GattAttributes.lookup(descriptor.getUuid()) + " "
                            + getGattStatusMessage(status) + " written: " + ByteUtil.getHex(descriptor.getValue()));
                }
                mCurrentOperation.gattOperationCompletionCallback(descriptor.getUuid(), descriptor.getValue());
            }


            @Override
            public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                super.onDescriptorRead(gatt, descriptor, status);
                aapsLogger.debug(LTag.PUMPBTCOMM, "onDescriptorRead ");
                mCurrentOperation.gattOperationCompletionCallback(descriptor.getUuid(), descriptor.getValue());
                if (gattDebugEnabled) {
                    aapsLogger.warn(LTag.PUMPBTCOMM, "onDescriptorRead " + getGattStatusMessage(status) + " status " + descriptor);
                }
            }


            @Override
            public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
                super.onMtuChanged(gatt, mtu, status);
                aapsLogger.debug(LTag.PUMPBTCOMM, "onMtuchanged ");
                if (gattDebugEnabled) {
                    aapsLogger.warn(LTag.PUMPBTCOMM, "onMtuChanged " + mtu + " status " + status);
                }
            }


            @Override
            public void onReadRemoteRssi(final BluetoothGatt gatt, int rssi, int status) {
                super.onReadRemoteRssi(gatt, rssi, status);
                aapsLogger.debug(LTag.PUMPBTCOMM, "onReadRemoteRssi ");
                if (gattDebugEnabled) {
                    aapsLogger.warn(LTag.PUMPBTCOMM, "onReadRemoteRssi " + getGattStatusMessage(status) + ": " + rssi);
                }
            }


            @Override
            public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
                super.onReliableWriteCompleted(gatt, status);
                aapsLogger.debug(LTag.PUMPBTCOMM, "onReliableWriteCompleted ");
                if (gattDebugEnabled) {
                    aapsLogger.warn(LTag.PUMPBTCOMM, "onReliableWriteCompleted status " + status);
                }
            }


            @Override
            public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
                super.onServicesDiscovered(gatt, status);

                aapsLogger.warn(LTag.PUMPBTCOMM, "onServicesDiscovered ");
                if (status == BluetoothGatt.GATT_SUCCESS) {

                    boolean medLinkFound = MedLinkConst.DEVICE_NAME.contains(gatt.getDevice().getName());

                    if (gattDebugEnabled) {
                        aapsLogger.warn(LTag.PUMPBTCOMM, "onServicesDiscovered " + getGattStatusMessage(status));
                    }

                    aapsLogger.info(LTag.PUMPBTCOMM, "Gatt device is MedLink device: " + medLinkFound);

                    if (medLinkFound) {
                        mIsConnected = true;
                        medLinkUtil.sendBroadcastMessage(MedLinkConst.Intents.MedLinkReady, context);
                    } else {
                        mIsConnected = false;
                        medLinkServiceData. setServiceState(RileyLinkServiceState.RileyLinkError,
                                RileyLinkError.DeviceIsNotRileyLink);
                    }

                } else {
                    aapsLogger.debug(LTag.PUMPBTCOMM, "onServicesDiscovered " + getGattStatusMessage(status));
                    medLinkUtil.sendBroadcastMessage(RileyLinkConst.Intents.RileyLinkGattFailed, context);
                }
            }
//            @Override
//            public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
//
//                super.onServicesDiscovered(gatt, status);
//
//                if (status == BluetoothGatt.GATT_SUCCESS) {
//                    final List<BluetoothGattService> services = gatt.getServices();
//
//                    boolean medLinkFound = MedLinkConst.DEVICE_NAME.contains(gatt.getDevice().getName());
//                    if (medLinkFound) {
//                        mIsConnected = true;
//                        medLinkUtil.sendBroadcastMessage(MedLinkConst.Intents.MedLinkReady, context);
//                    } else {
//                        mIsConnected = false;
//                        medLinkServiceData.setServiceState(RileyLinkServiceState.RileyLinkError,
//                                RileyLinkError.DeviceIsNotRileyLink);
//                    }
//
//                }
//            }
        };
    }

    private void callback(){
//        medLinkUtil.sendAnswer(pumpResponse.toString());
    }
    private String buildResp() {
        return new StringBuffer(MedLinkConst.Intents.CommandCompleted).
                append("\n").append(pumpResponse).toString();
    }

    private void addResponse(String response) {
        this.pumpResponse.append(response);
    }

    public BLECommOperationResult readCharacteristic_blocking(UUID serviceUUID, UUID charaUUID) {
        BLECommOperationResult rval = new BLECommOperationResult();
        if (bluetoothConnectionGatt != null) {
            try {
                gattOperationSema.acquire();
                SystemClock.sleep(1); // attempting to yield thread, to make sequence of events easier to follow
            } catch (InterruptedException e) {
                aapsLogger.error(LTag.PUMPBTCOMM, "readCharacteristic_blocking: Interrupted waiting for gattOperationSema");
                return rval;
            }
            if (mCurrentOperation != null) {
                rval.resultCode = BLECommOperationResult.RESULT_BUSY;
            } else {
                if (bluetoothConnectionGatt.getService(serviceUUID) == null) {
                    // Catch if the service is not supported by the BLE device
                    rval.resultCode = BLECommOperationResult.RESULT_NONE;
                    aapsLogger.error(LTag.PUMPBTCOMM, "BT Device not supported");
                    // TODO: 11/07/2016 UI update for user
                    // xyz rileyLinkServiceData.setServiceState(RileyLinkServiceState.BluetoothError, RileyLinkError.NoBluetoothAdapter);
                } else {
                    BluetoothGattCharacteristic chara = bluetoothConnectionGatt.getService(serviceUUID).getCharacteristic(
                            charaUUID);
                    if (chara != null) {
                        mCurrentOperation = new CharacteristicReadOperation(aapsLogger, bluetoothConnectionGatt, chara);

                        mCurrentOperation.execute(this);
                        aapsLogger.debug(LTag.PUMPBTCOMM, "Bluetooth communication");
                        aapsLogger.debug(LTag.PUMPBTCOMM, String.valueOf(mCurrentOperation.getValue()));
                        aapsLogger.debug(LTag.PUMPBTCOMM, String.valueOf(mCurrentOperation.getValue()));
                        if (mCurrentOperation.timedOut) {
                            rval.resultCode = BLECommOperationResult.RESULT_TIMEOUT;
                        } else if (mCurrentOperation.interrupted) {
                            rval.resultCode = BLECommOperationResult.RESULT_INTERRUPTED;
                        } else {
                            rval.resultCode = BLECommOperationResult.RESULT_SUCCESS;
                            rval.value = mCurrentOperation.getValue();
                        }
                    }
//                    rval.resultCode
                }
            }
            mCurrentOperation = null;
            gattOperationSema.release();
        } else {
            aapsLogger.error(LTag.PUMPBTCOMM, "readCharacteristic_blocking: not configured!");
            rval.resultCode = BLECommOperationResult.RESULT_NOT_CONFIGURED;
        }
        return rval;
    }



    @Override public BLECommOperationResult writeCharacteristic_blocking(UUID serviceUUID, UUID charaUUID, byte[] command) {
        if(this.isConnected || Arrays.equals(command ,MedLinkCommandType.Connect.getRaw())) {
            return super.writeCharacteristic_blocking(serviceUUID, charaUUID, command);
        }else {
            if(!gattConnected) {
                connectGatt();
            }
            this.addCommand(serviceUUID, charaUUID, command);
            return this.processRemainingCommand();
        }

    }

    private BLECommOperationResult processRemainingCommand() {
        int index = 0;
        while(!this.isConnected ){
            SystemClock.sleep(500);
            index++;
            if(index>20){
                BLECommOperationResult ret = new BLECommOperationResult();
                ret.resultCode = BLECommOperationResult.RESULT_TIMEOUT;
                return ret;
            }
        }
        RemainingCommand remain = remainingCommands.get(0);
        return writeCharacteristic_blocking(remain.serviceUUID, remain.charaUUID, remain.command);

    }

    private class RemainingCommand{
        private final byte[] command;
        private final UUID charaUUID;

        private final UUID serviceUUID;

        private RemainingCommand(UUID serviceUUID, UUID charaUUID, byte[] command){
            this.serviceUUID = serviceUUID;
            this.charaUUID = charaUUID;
            this.command = command;
        }

        public byte[] getCommand() {
            return command;
        }

        public UUID getCharaUUID() {
            return charaUUID;
        }

        public UUID getServiceUUID() {
            return serviceUUID;
        }
    }

    private void addCommand(UUID serviceUUID, UUID charaUUID, byte[] command){
        this.remainingCommands.add(new RemainingCommand(serviceUUID,charaUUID,command));
    }
    public boolean enableNotifications() {
        BLECommOperationResult result = setNotification_blocking(UUID.fromString(GattAttributes.SERVICE_UUID), //
                UUID.fromString(GattAttributes.GATT_UUID));
        if (result.resultCode != BLECommOperationResult.RESULT_SUCCESS) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Error setting response count notification");
            return false;
        }
        return true;
    }

    @Override public boolean isConnected() {
        return isConnected;
    }

    public void setConnected(boolean connected)
    {
        if(!connected){
            this.pumpResponse = new StringBuffer();
        }
        isConnected = connected;
        gattConnected = connected;
    }

    public String getPumpModel() {
        return pumpModel;
    }

    public void setPumpModel(String pumpModel) {
        this.pumpModel = pumpModel;
    }

    public void findMedLink(String RileyLinkAddress) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "RileyLink address: " + RileyLinkAddress);
        // Must verify that this is a valid MAC, or crash.

        rileyLinkDevice = bluetoothAdapter.getRemoteDevice(RileyLinkAddress);
        // if this succeeds, we get a connection state change callback?

        if (rileyLinkDevice != null) {
//            medLinkUtil.sendBroadcastMessage(MedLinkConst.Intents.MedLinkReady, context);
            connectGatt();
        } else {
            aapsLogger.error(LTag.PUMPBTCOMM, "RileyLink device not found with address: " + RileyLinkAddress);
        }
    }

    public void disconnect() {
        super.disconnect();
        setConnected(false);
    }

    public void close() {
        super.close();
        setConnected(false);
    }
}