
package com.ledcar.ambient;

import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;

import java.util.UUID;

public class SequentialBleService extends Service {

    public static final String ACTION_START =
        "com.ledcar.ambient.SEQ_START";

    public static final String ACTION_STOP =
        "com.ledcar.ambient.SEQ_STOP";

    public static final String ACTION_STATUS =
        "com.ledcar.ambient.SEQ_STATUS";

    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_CONNECTED = "connected";

    private static final String CHANNEL_ID =
        "sequential_ble_channel";

    private static final int NOTIFICATION_ID = 4102;

    private static final UUID SERVICE_UUID =
        UUID.fromString(
            "0000ffe0-0000-1000-8000-00805f9b34fb"
        );

    private static final UUID WRITE_UUID =
        UUID.fromString(
            "0000ffe1-0000-1000-8000-00805f9b34fb"
        );

    private static final byte[] CONTROLLER_MODE =
        hex("7BFF0351FFFFFFFFBF");

    private final Handler handler =
        new Handler(Looper.getMainLooper());

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic characteristic;

    private boolean running;
    private boolean connected;
    private boolean stopping;
    private boolean writing;
    private boolean awaitingControllerWrite;

    private int speed = 50;
    private float hue;
    private long lastTick;
    private int generation;

    private byte[] pendingPacket;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running || stopping) return;

            if (connected && !writing) {
                long now = SystemClock.uptimeMillis();

                if (lastTick == 0L) {
                    lastTick = now;
                }

                long elapsed = Math.min(
                    1000L,
                    Math.max(0L, now - lastTick)
                );

                lastTick = now;

                speed = Math.max(
                    1,
                    Math.min(
                        100,
                        preferences().getInt(
                            "mode_speed", 50
                        )
                    )
                );

                double fraction =
                    (speed - 1) / 99.0;

                double cycleMillis =
                    120000.0 *
                    Math.pow(
                        8000.0 / 120000.0,
                        fraction
                    );

                hue = (float)(
                    (hue + elapsed * 360.0 / cycleMillis)
                    % 360.0
                );

                int color = Color.HSVToColor(
                    new float[]{hue, 1f, 1f}
                );

                write(rgbPacket(
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
                ), false);
            } else {
                lastTick = SystemClock.uptimeMillis();
            }

            handler.postDelayed(this, 140);
        }
    };

    private final Runnable reconnectTask =
        new Runnable() {
            @Override
            public void run() {
                if (running && !stopping && !connected) {
                    connect();
                }
            }
        };

    private final Runnable writeTimeout =
        new Runnable() {
            @Override
            public void run() {
                if (awaitingControllerWrite) {
                    awaitingControllerWrite = false;
                    finishService(false);
                } else {
                    writing = false;
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
                if (current != gatt) {
                    closeGatt(current);
                    return;
                }

                if (!running || stopping) {
                    closeConnection();
                    return;
                }

                if (status == BluetoothGatt.GATT_SUCCESS &&
                    newState ==
                    BluetoothProfile.STATE_CONNECTED) {

                    try {
                        if (!current.discoverServices()) {
                            connectionLost();
                        }
                    } catch (Exception e) {
                        connectionLost();
                    }
                } else {
                    connectionLost();
                }
            });
        }

        @Override
        public void onServicesDiscovered(
            BluetoothGatt current,
            int status
        ) {
            handler.post(() -> {
                if (current != gatt ||
                    !running || stopping) {
                    return;
                }

                BluetoothGattService service =
                    status == BluetoothGatt.GATT_SUCCESS
                    ? current.getService(SERVICE_UUID)
                    : null;

                characteristic = service == null
                    ? null
                    : service.getCharacteristic(WRITE_UUID);

                if (characteristic == null) {
                    connectionLost();
                    return;
                }

                int properties =
                    characteristic.getProperties();

                if ((properties &
                    (BluetoothGattCharacteristic.PROPERTY_WRITE |
                     BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE))
                    == 0) {
                    connectionLost();
                    return;
                }

                connected = true;
                writing = false;
                lastTick = 0L;

                publishStatus();
                updateNotification(
                    "스마트폰 순차점등 실행 중"
                );
            });
        }

        @Override
        public void onCharacteristicWrite(
            BluetoothGatt current,
            BluetoothGattCharacteristic c,
            int status
        ) {
            handler.post(() -> {
                if (current != gatt) return;

                handler.removeCallbacks(writeTimeout);

                boolean controllerWrite =
                    awaitingControllerWrite;

                writing = false;
                awaitingControllerWrite = false;

                if (controllerWrite) {
                    finishService(
                        status ==
                        BluetoothGatt.GATT_SUCCESS
                    );
                }
            });
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(
        Intent intent,
        int flags,
        int startId
    ) {
        String action = intent == null
            ? null
            : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopAndTransfer();
            return START_NOT_STICKY;
        }

        if (!ACTION_START.equals(action) &&
            !running) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!running) {
            startForeground(
                NOTIFICATION_ID,
                notification("BLE 연결 준비 중")
            );

            running = true;
            stopping = false;
            connected = false;
            writing = false;
            awaitingControllerWrite = false;

            hue = 0f;
            lastTick = 0L;

            preferences().edit()
                .putBoolean("phone_service_active", true)
                .putBoolean("mode_running", true)
                .apply();

            publishStatus();
            connect();
            handler.post(tick);
        }

        return START_NOT_STICKY;
    }

    private android.content.SharedPreferences preferences() {
        return getSharedPreferences(
            "MainActivity",
            MODE_PRIVATE
        );
    }

    private void connect() {
        if (!running || stopping || gatt != null) {
            return;
        }

        String address = preferences().getString(
            "last_device", ""
        );

        if (address.isEmpty()) {
            updateNotification("등록된 차량이 없습니다");
            scheduleReconnect();
            return;
        }

        BluetoothManager manager =
            (BluetoothManager)
            getSystemService(BLUETOOTH_SERVICE);

        BluetoothAdapter adapter =
            manager == null
            ? null
            : manager.getAdapter();

        if (adapter == null ||
            !adapter.isEnabled()) {
            updateNotification("블루투스 연결 대기 중");
            scheduleReconnect();
            return;
        }

        try {
            BluetoothDevice device =
                adapter.getRemoteDevice(address);

            gatt = device.connectGatt(
                this,
                false,
                callback,
                BluetoothDevice.TRANSPORT_LE
            );

            if (gatt == null) {
                scheduleReconnect();
            }
        } catch (Exception e) {
            updateNotification("차량 재연결 대기 중");
            scheduleReconnect();
        }
    }

    private void connectionLost() {
        connected = false;
        writing = false;
        awaitingControllerWrite = false;

        closeConnection();
        publishStatus();

        if (running && !stopping) {
            updateNotification("차량 재연결 대기 중");
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        handler.removeCallbacks(reconnectTask);

        if (running && !stopping) {
            handler.postDelayed(
                reconnectTask, 4000
            );
        }
    }

    private void write(
        byte[] packet,
        boolean controllerCommand
    ) {
        if (!connected ||
            gatt == null ||
            characteristic == null ||
            writing) {
            return;
        }

        writing = true;
        awaitingControllerWrite = controllerCommand;

        int properties =
            characteristic.getProperties();

        int type =
            (properties &
             BluetoothGattCharacteristic.PROPERTY_WRITE)
            != 0
            ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;

        boolean accepted = false;

        try {
            if (Build.VERSION.SDK_INT >= 33) {
                int result = gatt.writeCharacteristic(
                    characteristic,
                    packet,
                    type
                );

                accepted =
                    result == BluetoothStatusCodes.SUCCESS;
            } else {
                characteristic.setWriteType(type);
                characteristic.setValue(packet);

                accepted =
                    gatt.writeCharacteristic(characteristic);
            }
        } catch (Exception ignored) {}

        if (!accepted) {
            writing = false;
            awaitingControllerWrite = false;

            if (controllerCommand) {
                finishService(false);
            }
            return;
        }

        handler.removeCallbacks(writeTimeout);

        handler.postDelayed(
            writeTimeout,
            controllerCommand ? 2500 : 1200
        );

        if (type ==
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE &&
            !controllerCommand) {

            handler.postDelayed(() -> {
                if (!stopping) {
                    writing = false;
                    handler.removeCallbacks(writeTimeout);
                }
            }, 110);
        }
    }

    private void stopAndTransfer() {
        if (stopping) return;

        if (!running) {
            stopSelf();
            return;
        }

        stopping = true;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(reconnectTask);

        updateNotification(
            "컨트롤러 제어로 전환 중..."
        );

        if (!connected) {
            finishService(false);
            return;
        }

        // 진행 중인 RGB 전송이 끝날 시간을 확보한다.
        handler.postDelayed(() -> {
            if (!stopping) return;

            writing = false;
            awaitingControllerWrite = false;
            handler.removeCallbacks(writeTimeout);

            write(CONTROLLER_MODE, true);
        }, 250);
    }

    private void finishService(boolean transferred) {
        if (transferred) {
            preferences().edit()
                .putInt("sequential_engine", 0)
                .putInt("selected_mode", 0)
                .putBoolean("mode_running", true)
                .putBoolean("phone_service_active", false)
                .apply();
        } else {
            preferences().edit()
                .putBoolean("mode_running", false)
                .putBoolean("phone_service_active", false)
                .apply();
        }

        running = false;
        stopping = false;
        connected = false;

        publishStatus();

        handler.removeCallbacksAndMessages(null);
        closeConnection();

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void publishStatus() {
        Intent status = new Intent(ACTION_STATUS);
        status.setPackage(getPackageName());
        status.putExtra(EXTRA_RUNNING, running);
        status.putExtra(EXTRA_CONNECTED, connected);
        sendBroadcast(status);
    }

    private void closeConnection() {
        BluetoothGatt old = gatt;

        gatt = null;
        characteristic = null;
        connected = false;

        if (old != null) {
            try {
                old.disconnect();
            } catch (Exception ignored) {}

            closeGatt(old);
        }
    }

    private void closeGatt(BluetoothGatt old) {
        try {
            old.close();
        } catch (Exception ignored) {}
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel =
                new NotificationChannel(
                    CHANNEL_ID,
                    "스마트폰 순차점등",
                    NotificationManager.IMPORTANCE_LOW
                );

            channel.setDescription(
                "블루투스 순차점등 백그라운드 제어"
            );

            NotificationManager manager =
                (NotificationManager)
                getSystemService(
                    NOTIFICATION_SERVICE
                );

            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification notification(String message) {
        Intent openIntent =
            new Intent(this, MainActivity.class);

        openIntent.setFlags(
            Intent.FLAG_ACTIVITY_SINGLE_TOP |
            Intent.FLAG_ACTIVITY_CLEAR_TOP
        );

        PendingIntent openPending =
            PendingIntent.getActivity(
                this,
                4103,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT |
                PendingIntent.FLAG_IMMUTABLE
            );

        Intent stopIntent =
            new Intent(this, SequentialBleService.class);

        stopIntent.setAction(ACTION_STOP);

        PendingIntent stopPending =
            PendingIntent.getService(
                this,
                4104,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT |
                PendingIntent.FLAG_IMMUTABLE
            );

        Notification.Builder builder =
            Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);

        return builder
            .setSmallIcon(
                android.R.drawable.stat_sys_data_bluetooth
            )
            .setContentTitle("엠비언트 설정")
            .setContentText(message)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "컨트롤러 제어로 전환 후 중지",
                stopPending
            )
            .build();
    }

    private void updateNotification(String message) {
        NotificationManager manager =
            (NotificationManager)
            getSystemService(NOTIFICATION_SERVICE);

        if (manager != null) {
            manager.notify(
                NOTIFICATION_ID,
                notification(message)
            );
        }
    }

    private static byte[] rgbPacket(
        int r, int g, int b
    ) {
        return new byte[]{
            0x7B,
            0x01,
            0x07,
            (byte)r,
            (byte)g,
            (byte)b,
            0x00,
            (byte)0xFF,
            (byte)0xBF
        };
    }

    private static byte[] hex(String value) {
        byte[] bytes =
            new byte[value.length() / 2];

        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte)Integer.parseInt(
                value.substring(
                    i * 2,
                    i * 2 + 2
                ), 16
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
        running = false;

        handler.removeCallbacksAndMessages(null);
        closeConnection();

        preferences().edit()
            .putBoolean("phone_service_active", false)
            .apply();

        super.onDestroy();
    }
}

