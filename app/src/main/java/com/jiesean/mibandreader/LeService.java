package com.jiesean.mibandreader;

import android.annotation.TargetApi;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.RequiresApi;
import android.util.Log;

import com.jiesean.mibandreader.model.BatteryInfoParser;
import com.jiesean.mibandreader.model.CommandPool;
import com.jiesean.mibandreader.model.Profile;
import com.jiesean.mibandreader.model.StepParser;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

public class LeService extends Service {

    private String TAG = "abc";
    private String mTargetDeviceName = "Mi ";

    /** 自定义binder，用于service绑定activity之后为activity提供操作service的接口 */
    private LocalBinder mBinder = new LocalBinder();
    private Handler mHandler;
    private Intent intent;
    /** 设置扫描时限 */
    private int SCAN_PERIOD = 20000;
    private boolean mScanning = false;
    private int mColorIndex = 0;
    private CommandPool mCommandPool;

    /** bluetooth */
    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBluetoothLeScanner;
    private ScanCallback mScanCallback;
    private LeGattCallback mLeGattCallback;
    private BluetoothGatt mGatt;
    private BluetoothDevice mTargetDevice;

    /** Characteristic */
    BluetoothGattCharacteristic alertChar;
    BluetoothGattCharacteristic stepChar;
    BluetoothGattCharacteristic batteryChar;
    BluetoothGattCharacteristic controlPointChar;
    BluetoothGattCharacteristic vibrationChar;

    @Override
    public IBinder onBind(Intent intent) {
        Log.e(TAG, "service onBind()");
        return mBinder;
    }

    @Override
    public void onCreate() {
        Log.e(TAG, "service onCreate()");

        mScanCallback = new LeScanCallback();
        mLeGattCallback = new LeGattCallback();

        mHandler = new Handler();

    }

    /**
     * 继承Binder类，实现localbinder,为activity提供操作接口
     */
    public class LocalBinder extends Binder {
        /**
         * 初始化蓝牙
         * @return
         */
        public boolean initBluetooth() {
            Log.e(TAG, "initBluetooth");

            // init bluetooth adapter.api 21 above
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            mBluetoothAdapter = mBluetoothManager.getAdapter();
            if (mBluetoothAdapter == null) {
                return false;
            } else if (mBluetoothAdapter.getState() == BluetoothAdapter.STATE_OFF) {
                boolean bluetoothState = mBluetoothAdapter.enable();
                return bluetoothState;
            }
            return true;
        }

        /**
         * 初始化蓝牙扫描
         * @return
         */
        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        public boolean initLeScanner() {
            Log.e(TAG, "initLeScanner");

            mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();
            if (mBluetoothLeScanner != null) {
                return true;
            }
            return false;
        }

        /**
         * 开始扫描
         */
        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        public void startLeScan() {
            Log.e(TAG, "startLeScan");

            mBluetoothLeScanner.startScan(mScanCallback);
            mScanning = true;
            // Stops scanning after a pre-defined scan period.
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mScanning) {
                        Log.e(TAG, "扫描超时，停止扫描 Stop Scan Time Out");
                        mScanning = false;
                        mBluetoothLeScanner.stopScan(mScanCallback);
                        notifyUI("state", "3");
                    }
                }
            }, SCAN_PERIOD);
        }

        /**
         * Start the bonding (pairing) process with the remote device.
         * 绑定设备
         * @return
         */
        public int bondTargetDevice() {
            if (mTargetDevice == null) {
                return -1;
            } else {
                boolean result = mBluetoothAdapter.getBondedDevices().contains(mTargetDevice);
                Log.e(TAG, "已经绑定"+ result);
                if (result) {
                    /** 已经绑定 */
                    return 1;
                }
                result = mTargetDevice.createBond();
                Log.e(TAG, "开始绑定"+ result);
                return (result ? 0 : -1);
            }
        }

        /**
         * 连接GATT
         * @return
         */
        public int connectToGatt() {
            if (!mBluetoothAdapter.getBondedDevices().contains(mTargetDevice)) {
                return -1;
            }
            mTargetDevice.connectGatt(LeService.this, true, mLeGattCallback);
            return 0;
        }

        public void startAlert() {
            Log.e(TAG, "startLeScan extent: ");

            if (mGatt != null) {
                byte[] value = {(byte) 0x02};
                mCommandPool.addCommand(CommandPool.Type.write, value, alertChar);

                byte[] v1 = {(byte) 0x01};
                mCommandPool.addCommand(CommandPool.Type.write, v1, alertChar);
                byte[] v0 = {(byte) 0x00};
                mCommandPool.addCommand(CommandPool.Type.write, v0, alertChar);
            }
        }

        public void vibrateWithoutLed() {
            Log.e(TAG, "vibrateWithoutLed : ");

            mCommandPool.addCommand(CommandPool.Type.write, Profile.VIBRATION_WITHOUT_LED, vibrationChar);
        }

        public void vibrateWithLed() {
            Log.e(TAG, "vibrateWithLed : ");

            mCommandPool.addCommand(CommandPool.Type.write, Profile.VIBRATION_WITH_LED, vibrationChar);
        }
    }

    /**
     * LE设备扫描结果的callback返回
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private class LeScanCallback extends ScanCallback {

        @Override
        public void onScanResult(int callbackType, ScanResult result) {

            if (result != null) {
                // 此处，我们尝试连接Mi Band 3 设备
                Log.e(TAG, "onScanResult DeviceName : " + result.getDevice().getName() +
                        " DeviceAddress : " + result.getDevice().getAddress());

                if (result.getDevice().getName() != null
                        && result.getDevice().getName().startsWith(mTargetDeviceName)) {
                    // 扫描到我们想要的设备后，立即停止扫描
                    mScanning = false;
                    mTargetDevice = result.getDevice();
                    notifyUI("state", mTargetDevice.getAddress());
                    mBluetoothLeScanner.stopScan(mScanCallback);

                    boolean bondState = mBluetoothAdapter.getBondedDevices().contains(mTargetDevice);
                    if (bondState) {
                        notifyUI("state", 6 + "");
                    }
                    Log.e(TAG, "扫描到目标设备：" + result.getDevice().getName());
                }
            }
        }
    }

    /**
     * gatt连接结果的callback返回
     */
    private class LeGattCallback extends BluetoothGattCallback {

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.e(TAG, "onConnectionStateChange status:" + status + "  newState:" + newState);
            if (newState == 2) {
                gatt.discoverServices();
                mGatt = gatt;
                mCommandPool = new CommandPool(LeService.this, mGatt);
                Thread thread = new Thread(mCommandPool);
                thread.start();

                notifyUI("state", "1");
            } else if (newState == 0) {
                mGatt = null;

                notifyUI("state", "0");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.e(TAG, "onServicesDiscovered status : " + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                List<BluetoothGattService> services = gatt.getServices();

                if (services != null) {
                    Log.e(TAG, "onServicesDiscovered num: " + services.size());
                }

                for (BluetoothGattService bluetoothGattService : services) {
                    Log.e(TAG, "onServicesDiscovered service: " + bluetoothGattService.getUuid());
                    List<BluetoothGattCharacteristic> charc = bluetoothGattService.getCharacteristics();

                    for (BluetoothGattCharacteristic charac : charc) {

                        if (charac.getUuid().equals(
                                UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb"))) {
                            // Device Name
                            int charaProp = charac.getProperties();
                            if ((charaProp & BluetoothGattCharacteristic.PROPERTY_READ) > 0) {
                                Log.e(TAG, " ======2a20====read ");
                                mCommandPool.addCommand(CommandPool.Type.read, null, charac);
                            } else if ((charaProp & BluetoothGattCharacteristic.PROPERTY_WRITE) > 0) {
                                Log.e(TAG, " ======2a20====write ");
//                                charac.setValue(new byte[2]);
//                                gatt.writeCharacteristic(charac);
                                mCommandPool.addCommand(CommandPool.Type.write, new byte[2], charac);
                            } else if ((charaProp
                                    & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) > 0) {
                                Log.e(TAG, " ======2a20====write-noresponse ");
                                mCommandPool.addCommand(CommandPool.Type.write, new byte[2], charac);
                            }
                            if ((charaProp & BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                                Log.e(TAG, " ======2a20====notify ");
                                mCommandPool.addCommand(CommandPool.Type.setNotification, null, charac);
                            }
                            if ((charaProp & BluetoothGattCharacteristic.PROPERTY_INDICATE) > 0) {
                                Log.e(TAG, " ======2a20====indicate ");
//                                mCommandPool.addCommand(CommandPool.Type., null, charac);
                            }
                        }

//                        if (charac.getUuid().equals(
//                                UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb"))) {
//                            // Device Name
//                            Log.e(TAG, " ======2a20==== ");
//                            mCommandPool.addCommand(CommandPool.Type.read, null, charac);
//                        }

//                        else if (charac.getUuid().equals(
//                                UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb"))) {
//                            // Device Name
//                            Log.e(TAG, " ======2a25==== ");
//                            mCommandPool.addCommand(CommandPool.Type.read, null, charac);
//                        }

//                        else if (charac.getUuid().equals(
//                                UUID.fromString("00002a06-0000-1000-8000-00805f9b34fb"))) {
////                            UUID shakeServiceUUID=UUID.fromString("00001802-0000-1000-8000-00805f9b34fb");
////                            UUID shakeCharaUUID=UUID.fromString("00002a06-0000-1000-8000-00805f9b34fb");
////                            BluetoothGattService shakeService=mBluetoothGatt.getService(shakeServiceUUID);
////                            BluetoothGattCharacteristic shakeChara=shakeService.getCharacteristic(shakeCharaUUID);
//                            //1震动两次，2震动八次
//                            Log.e(TAG, " ======尝试写震动数据==== ");
//                            mCommandPool.addCommand(CommandPool.Type.read, null, charac);
//                            charac.setValue(new byte[2]);
//                            gatt.writeCharacteristic(charac);
//                            mCommandPool.addCommand(CommandPool.Type.write, new byte[2], charac);
//                            mCommandPool.addCommand(CommandPool.Type.read, null, charac);
//                        }

//                        else if (charac.getUuid().equals(
//                                UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb"))) {
//                            // Device Name
//                            Log.e(TAG, " ======2a27==== ");
//                            mCommandPool.addCommand(CommandPool.Type.read, null, charac);
//                        }

//                        else if (charac.getUuid().equals(
//                                UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb"))) {
//                            // Device Name
//                            Log.e(TAG, " ======2a37==== ");
//                            mCommandPool.addCommand(CommandPool.Type.read, null, charac);
//                        }

//                        else if (charac.getUuid().equals(
//                                UUID.fromString("00002a23-0000-1000-8000-00805f9b34fb"))) {
//                            // Device Name
//                            Log.e(TAG, " ======2a23==== ");
//                            mCommandPool.addCommand(CommandPool.Type.read, null, charac);
//                        }

//                        if (charac.getUuid().equals(
//                                UUID.fromString("00002a46-0000-1000-8000-00805f9b34fb"))) {
//                            // Device Name
//                            Log.e(TAG, " ======2a46==== ");
//                            mCommandPool.addCommand(CommandPool.Type.read, null, charac);
//                        }

//                        else if (charac.getUuid().equals(
//                                UUID.fromString("00002a44-0000-1000-8000-00805f9b34fb"))) {
//                            // Device Name
//                            Log.e(TAG, " ======2a44==== ");
//                            mCommandPool.addCommand(CommandPool.Type.read, null, charac);
//                        }

//                        else if (charac.getUuid().equals(
//                                UUID.fromString("00002a06-0000-1000-8000-00805f9b34fb"))) {
//                            // Device Name
//                            Log.e(TAG, " ======2a06==== ");
//                            mCommandPool.addCommand(CommandPool.Type.read, null, charac);
//                        }

//                        if (charac.getUuid().equals(
//                                UUID.fromString("00002a2b-0000-1000-8000-00805f9b34fb"))) {
//                            // Device Name
//                            Log.e(TAG, " ======2a2b==== ");
//                            mCommandPool.addCommand(CommandPool.Type.read, null, charac);
//                        }x

//                        else if (charac.getUuid().equals(
//                                UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb"))) {
//                            // Device Name
//                            Log.e(TAG, " ======2a37==== ");
//                            mCommandPool.addCommand(CommandPool.Type.read, null, charac);
//                        }

//                        else if (charac.getUuid().equals(
//                                UUID.fromString("00002a39-0000-1000-8000-00805f9b34fb"))) {
//                            // Device Name
//                            Log.e(TAG, " ======2a39==== ");
//                            mCommandPool.addCommand(CommandPool.Type.read, null, charac);
//                        }



//                        else if (charac.getUuid().equals(
//                                UUID.fromString("0000fedd-0000-1000-8000-00805f9b34fb"))) {
//                            // Device Name
//                            Log.e(TAG, " ======fedd==== ");
//                            mCommandPool.addCommand(CommandPool.Type.read, null, charac);
//                        }

//                        else if (charac.getUuid().equals(
//                                UUID.fromString("0000fede-0000-1000-8000-00805f9b34fb"))) {
//                            // Device Name
//                            Log.e(TAG, " ======fede==== ");
//                            mCommandPool.addCommand(CommandPool.Type.read, null, charac);
//                        }








//                        if (charac.getUuid().equals(Profile.IMMIDATE_ALERT_CHAR_UUID)) {
//                            Log.e(TAG, "alertChar found!");
//                            //设备 震动特征值
//                            alertChar = charac;
//                        }
//                        if (charac.getUuid().equals(Profile.STEP_CHAR_UUID)) {
//                            Log.e(TAG, "stepchar found!");
//                            //设备 步数
//                            stepChar = charac;
//                            mCommandPool.addCommand(CommandPool.Type.setNotification, null, charac);
//
//                            notifyUI("state", "4");
//                        }
//                        if (charac.getUuid().equals(Profile.BATTERY_CHAR_UUID)) {
//                            Log.e(TAG, "battery found!");
//                            batteryChar = charac;
//
//                            mCommandPool.addCommand(CommandPool.Type.read, null, charac);
//                            mCommandPool.addCommand(CommandPool.Type.setNotification, null, charac);
//                        }
//                        if (charac.getUuid().equals(Profile.CONTROL_POINT_CHAR_UUID)) {
//                            Log.e(TAG, "control point found!");
//                            //LED颜色
//                            controlPointChar = charac;
//                        }
//                        if (charac.getUuid().equals(Profile.VIBRATION_CHAR_UUID)) {
//                            Log.e(TAG, "vibration found!");
//                            //震动
//                            vibrationChar = charac;
//                        }

                    }
                }
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            Log.e(TAG, "onCharacteristicChanged UUID : " + characteristic.getUuid());
            if (characteristic == stepChar) {

                StepParser parser = new StepParser(characteristic.getValue());
                notifyUI("step", parser.getStepNum() + "");
            }
            if (characteristic == batteryChar) {
                BatteryInfoParser parser = new BatteryInfoParser(characteristic.getValue());
                notifyUI("battery", parser.getLevel() + "");
            }

        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.e(TAG, "onCharacteristicWrite UUID: " + characteristic.getUuid() + "state : " + status);
            mCommandPool.onCommandCallbackComplete();
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.e(TAG, "onCharacteristicRead UUID : " + characteristic.getUuid());
            mCommandPool.onCommandCallbackComplete();
            byte[] value = characteristic.getValue();
            for (int index = 0; index < value.length; index++) {
                Log.e(TAG, "第" + index + "位信息:" + value[index]);
                Log.e(TAG, "2进制:" + Util.integerToBinary(value[index]));

                String hex = Integer.toHexString(value[index]);
                Log.e(TAG, "第" + index + "位16进制:" + hex);


            }

            Log.e(TAG, "第一位信息:" + value[0]);
            Log.e(TAG, "读到的byte数组:" + value);
            Log.e(TAG, "读到的信息:" + new String(value));

            if (characteristic.getUuid().equals(Profile.BATTERY_CHAR_UUID)) {
                BatteryInfoParser parser = new BatteryInfoParser(characteristic.getValue());
                notifyUI("battery", parser.getLevel() + "|" + parser.getStatusToString());

            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.e(TAG, "onDescriptorWrite");
            mCommandPool.onCommandCallbackComplete();
        }
    }

    private void notifyUI(String type, String data) {
        intent = new Intent();
        intent.setAction(type);
        intent.putExtra(type, data);
        sendBroadcast(intent);
    }


}
