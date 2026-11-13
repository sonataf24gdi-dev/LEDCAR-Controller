
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

    private static final int NOTIFICATION_ID = 41;

    private static final byte[] ON =
        hex("7BFF0407FFFFFFFFBF");

    private static final byte[] OFF =
        hex("7BFF0406FFFFFFFFBF");

    private static final byte[] SEQUENTIAL =
        hex("7BFF0351FFFFFFFFBF");

    private static final byte[] RAINBOW =
        hex("7BFF0301FFFFFFFFBF");

    private final Handler handler =
        new Handler(Looper.getMainLooper());

    private final ArrayDeque<Intent> commands =
        new ArrayDeque<>();

    private final ArrayDeque<byte[]> packets =
        new ArrayDeque<>();

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;

    private boolean busy;
    private boolean finished;
    private boolean waitingWrite;
    private boolean lastWriteNoResponse;

    private int latestStartId;
    private int pendingIndex = -1;
    private String pendingCommand = "";

    private final Runnable timeout = () -> {
        setStatus("연결 실패");
        finishCurrent(false);
    };

    private final Runnable writeTimeout = () -> {
        if (waitingWrite) {
            setStatus("전송 시간 초과");
            finishCurrent(false);
        }
    };

    private final Runnable writer = new Runnable() {
        @Override
        public void run() {

            if (finished || !busy ||
                gatt == null || writeChar == null ||
                waitingWrite) {
                return;
            }

            byte[] packet = packets.poll();

            if (packet == null) {
                setStatus("명령 전송 완료");
                finishCurrent(true);
                return;
            }

            int properties = writeChar.getProperties();

            int writeType =
                (properties &
                BluetoothGattCharacteristic
                    .PROPERTY_WRITE_NO_RESPONSE) != 0
                ? BluetoothGattCharacteristic
                    .WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic
                    .WRITE_TYPE_DEFAULT;

            lastWriteNoResponse =
                writeType ==
                BluetoothGattCharacteristic
                    .WRITE_TYPE_NO_RESPONSE;

            try {
                boolean accepted;

                if (Build.VERSION.SDK_INT >= 33) {
                    int result = gatt.writeCharacteristic(
                        writeChar,
                        packet,
                        writeType
                    );

                    accepted =
                        result == BluetoothStatusCodes.SUCCESS;

                } else {
                    writeChar.setWriteType(writeType);
                    writeChar.setValue(packet);

                    accepted =
                        gatt.writeCharacteristic(writeChar);
                }

                if (!accepted) {
                    setStatus("전송 실패");
                    finishCurrent(false);
                    return;
                }

                if (lastWriteNoResponse) {
                    handler.postDelayed(writer, 180);
                } else {
                    waitingWrite = true;
                    handler.postDelayed(
                        writeTimeout,
                        3000
                    );
                }

            } catch (Exception e) {
                setStatus("전송 실패");
                finishCurrent(false);
            }
        }
    };

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
                            setStatus("서비스 검색 실패");
                            finishCurrent(false);
                        }
                    } catch (Exception e) {
                        setStatus("서비스 검색 실패");
                        finishCurrent(false);
                    }

                } else {
                    setStatus("연결 실패");
                    finishCurrent(false);
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

                writeChar = service == null
                    ? null
                    : service.getCharacteristic(CHAR_UUID);

                if (writeChar == null) {
                    setStatus("기기 미지원");
                    finishCurrent(false);
                    return;
                }

                int properties = writeChar.getProperties();

                if ((properties &
                    (BluetoothGattCharacteristic.PROPERTY_WRITE |
                     BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE))
                    == 0) {

                    setStatus("쓰기 미지원");
                    finishCurrent(false);
                    return;
                }

                handler.removeCallbacks(timeout);

                setStatus("전송 중");
                handler.post(writer);
            });
        }

        @Override
        public void onCharacteristicWrite(
            BluetoothGatt current,
            BluetoothGattCharacteristic characteristic,
            int status
        ) {
            handler.post(() -> {

                if (finished || current != gatt ||
                    !waitingWrite) {
                    return;
                }

                waitingWrite = false;
                handler.removeCallbacks(writeTimeout);

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    setStatus("전송 실패");
                    finishCurrent(false);
                    return;
                }

                handler.postDelayed(writer, 110);
            });
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
        latestStartId = startId;

        startForeground(
            NOTIFICATION_ID,
            createNotification()
        );

        if (intent == null) {
            if (!busy && commands.isEmpty()) {
                stopServiceNow();
            }
            return START_NOT_STICKY;
        }

        Intent copy = new Intent(intent);

        // 중복 입력이 많을 때 마지막 명령 우선
        if (commands.size() >= 5) {
            commands.poll();
        }

        commands.offer(copy);

        if (!busy) {
            startNextCommand();
        }

        return START_NOT_STICKY;
    }

    private void startNextCommand() {

        if (busy) {
            return;
        }

        Intent intent = commands.poll();

        if (intent == null) {
            stopServiceNow();
            return;
        }

        busy = true;
        finished = false;
        waitingWrite = false;

        pendingCommand =
            intent.getStringExtra("command");

        pendingIndex =
            intent.getIntExtra("index", -1);

        if (!hasPermission()) {
            setStatus("블루투스 권한 필요");
            finishCurrent(false);
            return;
        }

        SharedPreferences app = appPrefs();

        String address =
            app.getString("last_device", "");

        if (address == null || address.isEmpty()) {
            setStatus("기기 미등록");
            finishCurrent(false);
            return;
        }

        if (!preparePackets(
            pendingCommand,
            pendingIndex
        )) {
            setStatus("명령 확인 필요");
            finishCurrent(false);
            return;
        }

        BluetoothManager manager =
            (BluetoothManager)
            getSystemService(BLUETOOTH_SERVICE);

        BluetoothAdapter adapter =
            manager == null
            ? null
            : manager.getAdapter();

        if (adapter == null || !adapter.isEnabled()) {
            setStatus("블루투스 꺼짐");
            finishCurrent(false);
            return;
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

            if (gatt == null) {
                setStatus("연결 시작 실패");
                finishCurrent(false);
                return;
            }

            handler.postDelayed(timeout, 12000);

        } catch (Exception e) {
            setStatus("연결 실패");
            finishCurrent(false);
        }
    }

    private boolean preparePackets(
        String command,
        int index
    ) {
        packets.clear();

        SharedPreferences app = appPrefs();

        if ("preset".equals(command)) {

            if (index < 0 || index >= 6 ||
                !app.contains("preset_" + index)) {
                return false;
            }

            int color = app.getInt(
                "preset_" + index,
                0xFFFFFFFF
            );

            packets.offer(ON);

            packets.offer(rgb(
                (color >> 16) & 255,
                (color >> 8) & 255,
                color & 255
            ));

        } else if ("sequential".equals(command)) {

            packets.offer(ON);
            packets.offer(SEQUENTIAL);
            packets.offer(speed(
                app.getInt("mode_speed", 50)
            ));

        } else if ("rainbow".equals(command)) {

            packets.offer(ON);
            packets.offer(RAINBOW);
            packets.offer(speed(
                app.getInt("mode_speed", 50)
            ));

        } else if ("power".equals(command)) {

            boolean currentlyOn =
                widgetPrefs().getBoolean(
                    "power_on",
                    true
                );

            packets.offer(
                currentlyOn ? OFF : ON
            );

        } else {
            return false;
        }

        return true;
    }

    private void commitState() {

        SharedPreferences app = appPrefs();

        SharedPreferences widget = widgetPrefs();

        if ("preset".equals(pendingCommand)) {

            int color = app.getInt(
                "preset_" + pendingIndex,
                ColorWhite()
            );

            app.edit()
                .putInt("color_r", (color >> 16) & 255)
                .putInt("color_g", (color >> 8) & 255)
                .putInt("color_b", color & 255)
                .putBoolean("mode_running", false)
                .apply();

            widget.edit()
                .putInt("selected_preset", pendingIndex)
                .putBoolean("power_on", true)
                .apply();

        } else if ("sequential".equals(pendingCommand)) {

            app.edit()
                .putInt("selected_mode", 0)
                .putBoolean("mode_running", true)
                .apply();

            widget.edit()
                .putInt("selected_preset", -1)
                .putBoolean("power_on", true)
                .apply();

        } else if ("rainbow".equals(pendingCommand)) {

            app.edit()
                .putInt("selected_mode", 1)
                .putBoolean("mode_running", true)
                .apply();

            widget.edit()
                .putInt("selected_preset", -1)
                .putBoolean("power_on", true)
                .apply();

        } else if ("power".equals(pendingCommand)) {

            boolean oldPower =
                widget.getBoolean("power_on", true);

            boolean newPower = !oldPower;

            widget.edit()
                .putBoolean("power_on", newPower)
                .apply();

            if (!newPower) {
                app.edit()
                    .putBoolean("mode_running", false)
                    .apply();
            }
        }

        AmbientWidget.updateAll(this);
    }

    private static int ColorWhite() {
        return 0xFFFFFFFF;
    }

    private void finishCurrent(boolean success) {

        if (finished) {
            return;
        }

        finished = true;

        handler.removeCallbacks(timeout);
        handler.removeCallbacks(writer);
        handler.removeCallbacks(writeTimeout);

        if (success) {
            commitState();
        }

        BluetoothGatt old = gatt;

        gatt = null;
        writeChar = null;
        packets.clear();
        waitingWrite = false;

        if (old != null) {
            try {
                old.disconnect();
                old.close();
            } catch (Exception ignored) {}
        }

        busy = false;

        if (!commands.isEmpty()) {
            handler.postDelayed(
                this::startNextCommand,
                300
            );
        } else {
            stopServiceNow();
        }
    }

    private void stopServiceNow() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelfResult(latestStartId);
    }

    private SharedPreferences appPrefs() {
        return getSharedPreferences(
            AmbientWidget.PREFS,
            MODE_PRIVATE
        );
    }

    private SharedPreferences widgetPrefs() {
        return getSharedPreferences(
            AmbientWidget.WIDGET_PREFS,
            MODE_PRIVATE
        );
    }

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED;
        }

        return true;
    }

    private void setStatus(String value) {
        widgetPrefs().edit()
            .putString("connection_status", value)
            .apply();

        AmbientWidget.updateAll(this);
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

            if (manager != null) {
                manager.createNotificationChannel(
                    channel
                );
            }
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

    private static byte[] rgb(
        int r, int g, int b
    ) {
        return new byte[]{
            0x7B, 0x01, 0x07,
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
                value.substring(i * 2, i * 2 + 2),
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
            } catch (Exception ignored) {}

            gatt = null;
        }

        super.onDestroy();
    }
}
