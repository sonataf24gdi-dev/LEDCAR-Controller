
package com.ledcar.ambient;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.UUID;

public class WidgetBleService extends Service {

    private static final UUID SERVICE_UUID =
            UUID.fromString(
                    "0000ffe0-0000-1000-8000-00805f9b34fb"
            );

    private static final UUID CHAR_UUID =
            UUID.fromString(
                    "0000ffe1-0000-1000-8000-00805f9b34fb"
            );

    private static final String CHANNEL_ID =
            "ambient_widget_ble";

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private final ArrayDeque<byte[]> queue =
            new ArrayDeque<>();

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic characteristic;

    private boolean busy = false;
    private boolean finished = false;
    private int startId;

    private final Runnable timeout = () -> {
        setStatus("연결 실패");
        finishCommand();
    };

    private final Runnable writer = new Runnable() {
        @Override
        public void run() {

            if (finished || gatt == null ||
                    characteristic == null) {
                return;
            }

            byte[] packet = queue.poll();

            if (packet == null) {
                setStatus("대기");
                finishCommand();
                return;
            }

            try {
                int properties =
                        characteristic.getProperties();

                int writeType =
                        (properties &
                                BluetoothGattCharacteristic
                                        .PROPERTY_WRITE_NO_RESPONSE) != 0
                                ? BluetoothGattCharacteristic
                                        .WRITE_TYPE_NO_RESPONSE
                                : BluetoothGattCharacteristic
                                        .WRITE_TYPE_DEFAULT;

                boolean success;

                if (Build.VERSION.SDK_INT >= 33) {

                    int result = gatt.writeCharacteristic(
                            characteristic,
                            packet,
                            writeType
                    );

                    success =
                            result == BluetoothStatusCodes.SUCCESS;

                } else {

                    characteristic.setWriteType(writeType);
                    characteristic.setValue(packet);

                    success = gatt.writeCharacteristic(
                            characteristic
                    );
                }

                if (!success) {
                    setStatus("전송 실패");
                    finishCommand();
                    return;
                }

                handler.postDelayed(this, 180);

            } catch (Exception e) {
                setStatus("전송 실패");
                finishCommand();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {
        this.startId = startId;

        startForeground(
                41,
                createNotification()
        );

        if (intent == null) {
            finishCommand();
            return START_NOT_STICKY;
        }

        if (busy) {
            return START_NOT_STICKY;
        }

        finished = false;
        busy = true;

        String command =
                intent.getStringExtra("command");

        int index =
                intent.getIntExtra("index", -1);

        if (!hasPermission()) {
            setStatus("권한 필요");
            finishCommand();
            return START_NOT_STICKY;
        }

        SharedPreferences app =
                getSharedPreferences(
                        AmbientWidget.PREFS,
                        MODE_PRIVATE
                );

        String address =
                app.getString("last_device", "");

        if (address == null || address.isEmpty()) {
            setStatus("기기 미등록");
            finishCommand();
            return START_NOT_STICKY;
        }

        if (!preparePackets(command, index)) {
            finishCommand();
            return START_NOT_STICKY;
        }

        BluetoothManager manager =
                (BluetoothManager)
                        getSystemService(BLUETOOTH_SERVICE);

        BluetoothAdapter adapter =
                manager == null
                        ? null
                        : manager.getAdapter();

        if (adapter == null || !adapter.isEnabled()) {
            setStatus("BT 꺼짐");
            finishCommand();
            return START_NOT_STICKY;
        }

        try {
            BluetoothDevice device =
                    adapter.getRemoteDevice(address);

            setStatus("연결 중");

            gatt = device.connectGatt(
                    this,
                    false,
                    callback,
                    BluetoothDevice.TRANSPORT_LE
            );

            handler.postDelayed(timeout, 12000);

        } catch (Exception e) {
            setStatus("연결 실패");
            finishCommand();
        }

        return START_NOT_STICKY;
    }

    private boolean preparePackets(
            String command,
            int index
    ) {
        if (command == null) {
            return false;
        }

        queue.clear();

        SharedPreferences app =
                getSharedPreferences(
                        AmbientWidget.PREFS,
                        MODE_PRIVATE
                );

        SharedPreferences widget =
                getSharedPreferences(
                        AmbientWidget.WIDGET_PREFS,
                        MODE_PRIVATE
                );

        if ("preset".equals(command)) {

            if (index < 0 || index >= 6) {
                return false;
            }

            int[] defaults = {
                    0xFFFF3434,
                    0xFFFF9B28,
                    0xFFFFE144,
                    0xFF36D66A,
                    0xFF308BFF,
                    0xFFAE51E9
            };

            int color = app.getInt(
                    "preset_" + index,
                    defaults[index]
            );

            queue.add(hex(
                    "7BFF0407FFFFFFFFBF"
            ));

            queue.add(rgb(
                    ColorRed(color),
                    ColorGreen(color),
                    ColorBlue(color)
            ));

            widget.edit()
                    .putInt("selected_preset", index)
                    .putBoolean("power_on", true)
                    .apply();

            app.edit()
                    .putInt("color_r", ColorRed(color))
                    .putInt("color_g", ColorGreen(color))
                    .putInt("color_b", ColorBlue(color))
                    .putBoolean("mode_running", false)
                    .apply();

        } else if ("sequential".equals(command)) {

            queue.add(hex(
                    "7BFF0407FFFFFFFFBF"
            ));

            queue.add(hex(
                    "7BFF0351FFFFFFFFBF"
            ));

            queue.add(speed(
                    app.getInt("mode_speed", 50)
            ));

            widget.edit()
                    .putInt("selected_preset", -1)
                    .putBoolean("power_on", true)
                    .apply();

            app.edit()
                    .putBoolean("mode_running", true)
                    .apply();

        } else if ("rainbow".equals(command)) {

            queue.add(hex(
                    "7BFF0407FFFFFFFFBF"
            ));

            queue.add(hex(
                    "7BFF0301FFFFFFFFBF"
            ));

            queue.add(speed(
                    app.getInt("mode_speed", 50)
            ));

            widget.edit()
                    .putInt("selected_preset", -1)
                    .putBoolean("power_on", true)
                    .apply();

            app.edit()
                    .putBoolean("mode_running", true)
                    .apply();

        } else if ("power".equals(command)) {

            boolean power =
                    widget.getBoolean("power_on", true);

            boolean newPower = !power;

            queue.add(hex(
                    newPower
                            ? "7BFF0407FFFFFFFFBF"
                            : "7BFF0406FFFFFFFFBF"
            ));

            widget.edit()
                    .putBoolean("power_on", newPower)
                    .apply();

            if (!newPower) {
                app.edit()
                        .putBoolean("mode_running", false)
                        .apply();
            }

        } else {
            return false;
        }

        AmbientWidget.updateAll(this);
        return true;
    }

    private final BluetoothGattCallback callback =
            new BluetoothGattCallback() {

                @Override
                public void onConnectionStateChange(
                        BluetoothGatt current,
                        int status,
                        int newState
                ) {
                    handler.post(() -> {

                        if (finished || current != gatt) {
                            return;
                        }

                        if (status == BluetoothGatt.GATT_SUCCESS &&
                                newState ==
                                        BluetoothProfile.STATE_CONNECTED) {

                            try {
                                if (!current.discoverServices()) {
                                    setStatus("서비스 오류");
                                    finishCommand();
                                }
                            } catch (Exception e) {
                                setStatus("서비스 오류");
                                finishCommand();
                            }

                        } else {
                            setStatus("연결 실패");
                            finishCommand();
                        }
                    });
                }

                @Override
                public void onServicesDiscovered(
                        BluetoothGatt current,
                        int status
                ) {
                    handler.post(() -> {

                        if (finished || current != gatt) {
                            return;
                        }

                        BluetoothGattService service =
                                status == BluetoothGatt.GATT_SUCCESS
                                        ? current.getService(SERVICE_UUID)
                                        : null;

                        characteristic =
                                service == null
                                        ? null
                                        : service.getCharacteristic(
                                                CHAR_UUID
                                        );

                        if (characteristic == null) {
                            setStatus("기기 미지원");
                            finishCommand();
                            return;
                        }

                        handler.removeCallbacks(timeout);

                        setStatus("연결됨");

                        handler.post(writer);
                    });
                }
            };

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(
                    Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED;
        }

        return true;
    }

    private void setStatus(String status) {
        getSharedPreferences(
                AmbientWidget.WIDGET_PREFS,
                MODE_PRIVATE
        ).edit()
                .putString("connection_status", status)
                .apply();

        AmbientWidget.updateAll(this);
    }

    private void finishCommand() {
        if (finished) {
            return;
        }

        finished = true;
        busy = false;

        handler.removeCallbacks(timeout);
        handler.removeCallbacks(writer);

        BluetoothGatt old = gatt;

        gatt = null;
        characteristic = null;
        queue.clear();

        if (old != null) {
            try {
                old.disconnect();
                old.close();
            } catch (Exception ignored) {
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelfResult(startId);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "엠비언트 위젯 연결",
                            NotificationManager.IMPORTANCE_LOW
                    );

            NotificationManager manager =
                    getSystemService(
                            NotificationManager.class
                    );

            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {

        Notification.Builder builder =
                Build.VERSION.SDK_INT >= 26
                        ? new Notification.Builder(
                                this,
                                CHANNEL_ID
                        )
                        : new Notification.Builder(this);

        return builder
                .setContentTitle("엠비언트 설정")
                .setContentText("차량 조명 명령 전송 중")
                .setSmallIcon(
                        android.R.drawable.stat_sys_data_bluetooth
                )
                .setOngoing(true)
                .build();
    }

    private static int ColorRed(int color) {
        return (color >> 16) & 255;
    }

    private static int ColorGreen(int color) {
        return (color >> 8) & 255;
    }

    private static int ColorBlue(int color) {
        return color & 255;
    }

    private static byte[] rgb(
            int r,
            int g,
            int b
    ) {
        return new byte[]{
                0x7B,
                0x01,
                0x07,
                (byte) r,
                (byte) g,
                (byte) b,
                0x00,
                (byte) 0xFF,
                (byte) 0xBF
        };
    }

    private static byte[] speed(int value) {
        value = Math.max(
                1,
                Math.min(100, value)
        );

        return new byte[]{
                0x7B,
                (byte) 0xFF,
                0x02,
                (byte) value,
                (byte) 0xFF,
                0x00,
                (byte) 0xFF,
                (byte) 0xFF,
                (byte) 0xBF
        };
    }

    private static byte[] hex(String value) {
        byte[] bytes =
                new byte[value.length() / 2];

        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(
                    value.substring(
                            i * 2,
                            i * 2 + 2
                    ),
                    16
            );
        }

        return bytes;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {

        handler.removeCallbacksAndMessages(null);

        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) {
            }

            gatt = null;
        }

        super.onDestroy();
    }
}

