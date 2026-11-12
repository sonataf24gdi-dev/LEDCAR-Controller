
package com.ledcar.controller;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final String DEVICE_ADDRESS = "25:11:07:B0:04:7B";
    private static final UUID SERVICE_UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB");
    private static final UUID WRITE_UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB");

    private static final byte[] SEQUENTIAL = hex("7B FF 03 51 FF FF FF FF BF");
    private static final byte[] RAINBOW = hex("7B FF 03 01 FF FF FF FF BF");
    private static final byte[] POWER_ON = hex("7B FF 04 07 FF FF FF FF BF");
    private static final byte[] POWER_OFF = hex("7B FF 04 06 FF FF FF FF BF");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothAdapter adapter;
    private boolean connecting = false, connected = false, userDisconnect = false;
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private boolean draining = false;

    private TextView status, modeLabel, speedLabel, brightnessLabel, rgbLabel;
    private LinearLayout content;

    private int currentMode = 0;
    private int speed = 50, brightness = 100;
    private int red = 255, green = 255, blue = 255;
    private int accent = Color.rgb(69, 164, 255);

    private final Runnable reconnect = this::connect;

    private final Runnable drainRunnable = new Runnable() {
        @Override public void run() {
            if (!connected || writeCharacteristic == null || gatt == null) {
                queue.clear();
                draining = false;
                return;
            }

            byte[] next = queue.poll();
            if (next == null) {
                draining = false;
                return;
            }

            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeCharacteristic(
                        writeCharacteristic,
                        next,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    );
                } else {
                    writeCharacteristic.setWriteType(
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    );
                    writeCharacteristic.setValue(next);
                    gatt.writeCharacteristic(writeCharacteristic);
                }
            } catch (Exception ignored) {}

            handler.postDelayed(this, 90);
        }
    };

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(
            BluetoothGatt bg, int statusCode, int state
        ) {
            if (state == BluetoothProfile.STATE_CONNECTED
                    && statusCode == BluetoothGatt.GATT_SUCCESS) {
                bg.discoverServices();
            } else if (state == BluetoothProfile.STATE_DISCONNECTED
                    || statusCode != BluetoothGatt.GATT_SUCCESS) {
                if (gatt == bg) {
                    gatt = null;
                    writeCharacteristic = null;
                }
                bg.close();
                connected = false;
                connecting = false;

                handler.post(() -> {
                    updateStatus();
                    if (!userDisconnect) scheduleReconnect();
                });
            }
        }

        @Override public void onServicesDiscovered(
            BluetoothGatt bg, int statusCode
        ) {
            BluetoothGattService service = bg.getService(SERVICE_UUID);
            BluetoothGattCharacteristic c = service == null
                    ? null : service.getCharacteristic(WRITE_UUID);

            if (statusCode == BluetoothGatt.GATT_SUCCESS && c != null) {
                c.setWriteType(
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                );
                writeCharacteristic = c;
                connected = true;
                connecting = false;

                handler.post(() -> {
                    updateStatus();
                    toast("LEDCAR 연결 완료");
                });

                // 재연결 시 모드를 재전송하지 않음
            } else {
                bg.disconnect();
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);

        getWindow().setStatusBarColor(Color.rgb(10, 12, 17));
        getWindow().setNavigationBarColor(Color.rgb(10, 12, 17));

        android.content.SharedPreferences p =
                getSharedPreferences("ledcar", MODE_PRIVATE);

        speed = p.getInt("speed", 50);
        brightness = p.getInt("brightness", 100);
        red = p.getInt("red", 255);
        green = p.getInt("green", 255);
        blue = p.getInt("blue", 255);
        currentMode = p.getInt("mode", 0);

        BluetoothManager manager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);

        adapter = manager == null ? null : manager.getAdapter();

        buildUi();

        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                },
                100
            );
        } else {
            connect();
        }
    }

    @Override public void onRequestPermissionsResult(
        int code, String[] perms, int[] results
    ) {
        super.onRequestPermissionsResult(code, perms, results);

        if (code == 100 && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            connect();
        } else {
            toast("블루투스 권한이 필요합니다");
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(10, 12, 17));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(26), dp(20), dp(28));

        scroll.addView(content);
        setContentView(scroll);

        TextView title = text("엠비언트 설정", 27, Color.WHITE, true);
        content.addView(title);

        TextView sub = text(
            "LEDCAR Controller · v0.8",
            13,
            Color.rgb(150, 158, 173),
            false
        );
        content.addView(sub);
        gap(22);

        status = text("연결 확인 중...", 14, accent, true);
        content.addView(status);

        Button connectButton = button(
            "블루투스 연결 / 재연결",
            () -> {
                userDisconnect = false;
                connect();
            }
        );
        content.addView(connectButton);
        gap(14);

        LinearLayout powerRow = horizontal();
        powerRow.addView(
            button("조명 켜기", () -> send(POWER_ON)),
            weighted()
        );
        powerRow.addView(
            button("조명 끄기", () -> send(POWER_OFF)),
            weighted()
        );
        content.addView(powerRow);
        gap(25);

        section("컨트롤러 내장 모드");

        TextView note = text(
            "모드를 시작하면 컨트롤러가 자체 실행합니다. " +
            "앱 종료나 블루투스 해제 시 종료 명령을 보내지 않습니다.",
            13,
            Color.rgb(172, 183, 199),
            false
        );
        content.addView(note);
        gap(13);

        content.addView(
            button("순차 점등 시작", () -> startMode(1))
        );
        content.addView(
            button("무지개 순환 시작", () -> startMode(2))
        );

        modeLabel = text("", 14, accent, true);
        content.addView(modeLabel);
        updateModeLabel();
        gap(15);

        speedLabel = text(
            "속도: " + speed,
            15,
            Color.WHITE,
            true
        );
        content.addView(speedLabel);

        SeekBar speedBar = new SeekBar(this);
        speedBar.setMax(99);
        speedBar.setProgress(speed - 1);
        speedBar.setProgressTintList(
            android.content.res.ColorStateList.valueOf(accent)
        );
        content.addView(speedBar);

        speedBar.setOnSeekBarChangeListener(
            new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(
                    SeekBar bar, int progress, boolean fromUser
                ) {
                    speed = progress + 1;
                    speedLabel.setText("속도: " + speed);

                    if (fromUser) {
                        save();
                        sendSpeed();
                    }
                }

                @Override public void onStartTrackingTouch(SeekBar bar) {}

                @Override public void onStopTrackingTouch(SeekBar bar) {
                    sendSpeed();
                }
            }
        );

        gap(24);
        section("밝기");

        brightnessLabel = text(
            "밝기: " + brightness + "%",
            15,
            Color.WHITE,
            true
        );
        content.addView(brightnessLabel);

        SeekBar brightBar = new SeekBar(this);
        brightBar.setMax(99);
        brightBar.setProgress(brightness - 1);
        content.addView(brightBar);

        brightBar.setOnSeekBarChangeListener(
            new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(
                    SeekBar bar, int progress, boolean fromUser
                ) {
                    brightness = progress + 1;
                    brightnessLabel.setText(
                        "밝기: " + brightness + "%"
                    );

                    if (fromUser) {
                        save();
                        sendBrightness();
                    }
                }

                @Override public void onStartTrackingTouch(SeekBar bar) {}

                @Override public void onStopTrackingTouch(SeekBar bar) {
                    sendBrightness();
                }
            }
        );

        gap(24);
        section("고정 RGB 색상");

        TextView warning = text(
            "색상을 선택하면 내장 모드가 중단되고 " +
            "고정 색상으로 전환됩니다.",
            12,
            Color.rgb(172, 183, 199),
            false
        );
        content.addView(warning);
        gap(10);

        rgbLabel = text("", 14, Color.WHITE, true);
        content.addView(rgbLabel);
        updateRgbLabel();

        rgbSlider("R", red, 0);
        rgbSlider("G", green, 1);
        rgbSlider("B", blue, 2);

        gap(12);

        int[] colors = {
            Color.RED,
            Color.GREEN,
            Color.BLUE,
            Color.CYAN,
            Color.MAGENTA,
            Color.YELLOW,
            Color.WHITE
        };

        LinearLayout presets = horizontal();

        for (int c : colors) {
            TextView swatch = text("●", 28, c, true);
            swatch.setGravity(Gravity.CENTER);
            swatch.setOnClickListener(v ->
                setRgb(Color.red(c), Color.green(c), Color.blue(c))
            );
            presets.addView(swatch, weighted());
        }

        content.addView(presets);
        gap(16);

        TextView foot = text(
            "참고: 속도 값의 실제 빠르기 방향 및 중간 밝기 패킷은 " +
            "기기 테스트로 최종 확인해야 합니다.",
            12,
            Color.rgb(135, 143, 159),
            false
        );
        content.addView(foot);
    }

    private void startMode(int mode) {
        if (!connected) {
            toast("먼저 블루투스를 연결하세요");
            return;
        }

        currentMode = mode;
        save();

        send(mode == 1 ? SEQUENTIAL : RAINBOW);
        handler.postDelayed(this::sendSpeed, 180);

        updateModeLabel();

        toast(
            mode == 1 ? "순차 점등 실행" : "무지개 순환 실행"
        );
    }

    private void sendSpeed() {
        // 컨트롤러 자체 속도 조절 명령
        // XX = 01~64 (1~100)
        send(new byte[]{
            0x7B,
            (byte) 0xFF,
            0x02,
            (byte) speed,
            (byte) 0xFF,
            0x00,
            (byte) 0xFF,
            (byte) 0xFF,
            (byte) 0xBF
        });
    }

    private void sendBrightness() {
        // 최소/최대 확인, 중간 값은 실기기 검증 필요
        int flag = brightness == 1 ? 0x00 : 0x20;

        send(new byte[]{
            0x7B,
            (byte) 0xFF,
            0x01,
            (byte) flag,
            (byte) brightness,
            0x02,
            (byte) 0xFF,
            (byte) 0xFF,
            (byte) 0xBF
        });
    }

    private void rgbSlider(
        String label, int value, int channel
    ) {
        TextView t = text(
            label,
            13,
            Color.rgb(180, 190, 207),
            true
        );
        content.addView(t);

        SeekBar bar = new SeekBar(this);
        bar.setMax(255);
        bar.setProgress(value);
        content.addView(bar);

        bar.setOnSeekBarChangeListener(
            new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(
                    SeekBar seekBar, int p, boolean user
                ) {
                    if (!user) return;

                    if (channel == 0) {
                        red = p;
                    } else if (channel == 1) {
                        green = p;
                    } else {
                        blue = p;
                    }

                    currentMode = 0;
                    updateModeLabel();
                    updateRgbLabel();
                    save();
                    sendRgb();
                }

                @Override public void onStartTrackingTouch(
                    SeekBar seekBar
                ) {}

                @Override public void onStopTrackingTouch(
                    SeekBar seekBar
                ) {
                    sendRgb();
                }
            }
        );
    }

    private void setRgb(int r, int g, int b) {
        red = r;
        green = g;
        blue = b;
        currentMode = 0;

        save();
        buildUi();
        sendRgb();
    }

    private void sendRgb() {
        send(new byte[]{
            0x7B,
            0x01,
            0x07,
            (byte) red,
            (byte) green,
            (byte) blue,
            0x00,
            (byte) 0xFF,
            (byte) 0xBF
        });
    }

    private void send(byte[] packet) {
        if (!connected || writeCharacteristic == null) return;

        if (queue.size() > 12) {
            queue.poll();
        }

        queue.add(packet);

        if (!draining) {
            draining = true;
            handler.post(drainRunnable);
        }
    }

    private void connect() {
        handler.removeCallbacks(reconnect);

        if (connected || connecting) {
            updateStatus();
            return;
        }

        if (adapter == null || !adapter.isEnabled()) {
            updateStatus();
            return;
        }

        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        try {
            connecting = true;
            updateStatus();

            BluetoothDevice device =
                adapter.getRemoteDevice(DEVICE_ADDRESS);

            gatt = device.connectGatt(
                this,
                false,
                callback,
                BluetoothDevice.TRANSPORT_LE
            );
        } catch (Exception e) {
            connecting = false;
            updateStatus();
            toast("연결 실패: " + e.getMessage());
        }
    }

    private void scheduleReconnect() {
        handler.removeCallbacks(reconnect);
        handler.postDelayed(reconnect, 3500);
    }

    private void updateStatus() {
        if (status == null) return;

        status.setText(
            connected
                ? "● 연결됨 · " + DEVICE_ADDRESS
                : connecting
                    ? "● 연결 중..."
                    : "● 연결 안 됨"
        );

        status.setTextColor(
            connected
                ? Color.rgb(69, 215, 145)
                : accent
        );
    }

    private void updateModeLabel() {
        if (modeLabel != null) {
            modeLabel.setText(
                "현재 선택: " +
                (currentMode == 1
                    ? "순차 점등"
                    : currentMode == 2
                        ? "무지개 순환"
                        : "고정 RGB")
            );
        }
    }

    private void updateRgbLabel() {
        if (rgbLabel != null) {
            rgbLabel.setText(
                String.format(
                    Locale.US,
                    "RGB  %d · %d · %d    #%02X%02X%02X",
                    red, green, blue,
                    red, green, blue
                )
            );
        }
    }

    private void save() {
        getSharedPreferences("ledcar", MODE_PRIVATE)
            .edit()
            .putInt("mode", currentMode)
            .putInt("speed", speed)
            .putInt("brightness", brightness)
            .putInt("red", red)
            .putInt("green", green)
            .putInt("blue", blue)
            .apply();
    }

    private void section(String title) {
        content.addView(text(title, 19, Color.WHITE, true));
        gap(10);
    }

    private void gap(int size) {
        View v = new View(this);
        content.addView(
            v,
            new LinearLayout.LayoutParams(1, dp(size))
        );
    }

    private TextView text(
        String s, int sp, int color, boolean bold
    ) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);

        if (bold) {
            t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }

        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setOnClickListener(v -> action.run());

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(33, 40, 54));
        bg.setCornerRadius(dp(13));
        bg.setStroke(dp(1), Color.rgb(59, 70, 91));

        b.setBackground(bg);

        LinearLayout.LayoutParams p =
            new LinearLayout.LayoutParams(-1, dp(49));

        p.bottomMargin = dp(9);
        b.setLayoutParams(p);

        return b;
    }

    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams p =
            new LinearLayout.LayoutParams(0, dp(49), 1);

        p.setMargins(dp(3), 0, dp(3), dp(8));
        return p;
    }

    private int dp(int d) {
        return (int) (
            getResources().getDisplayMetrics().density * d + 0.5f
        );
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static byte[] hex(String s) {
        String v = s.replace(" ", "");
        byte[] b = new byte[v.length() / 2];

        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(
                v.substring(i * 2, i * 2 + 2),
                16
            );
        }

        return b;
    }

    @Override protected void onDestroy() {
        // 앱 종료 시 모드 종료/전원 끄기 명령 전송하지 않음
        userDisconnect = true;
        handler.removeCallbacksAndMessages(null);

        if (gatt != null) {
            try {
                gatt.close();
            } catch (Exception ignored) {}

            gatt = null;
        }

        super.onDestroy();
    }
}
