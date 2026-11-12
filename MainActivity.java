
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
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
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

    private static final String ADDRESS = "25:11:07:B0:04:7B";

    private static final UUID SERVICE = UUID.fromString(
        "0000FFE0-0000-1000-8000-00805F9B34FB"
    );

    private static final UUID WRITE = UUID.fromString(
        "0000FFE1-0000-1000-8000-00805F9B34FB"
    );

    private static final byte[] POWER_ON =
        hex("7B FF 04 07 FF FF FF FF BF");

    private static final byte[] POWER_OFF =
        hex("7B FF 04 06 FF FF FF FF BF");

    private static final byte[] SEQUENTIAL =
        hex("7B FF 03 51 FF FF FF FF BF");

    private static final byte[] RAINBOW =
        hex("7B FF 03 01 FF FF FF FF BF");

    private final Handler handler =
        new Handler(Looper.getMainLooper());

    private final ArrayDeque<byte[]> outbound =
        new ArrayDeque<>();

    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic characteristic;

    private boolean connected = false;
    private boolean connecting = false;
    private boolean foreground = false;
    private boolean power = true;
    private boolean sending = false;
    private boolean lightTheme = false;

    private int tab = 0;
    private int mode = 0;
    private int brightness = 100;
    private int speed = 50;
    private int color = Color.RED;

    private int themeBg = 0xFF101116;
    private int themePanel = 0xFF202127;
    private int themeText = Color.WHITE;
    private int themeMuted = 0xFFB8BAC5;

    private long lastConnectAttempt = 0;

    private LinearLayout root;
    private LinearLayout content;
    private TextView status;
    private SharedPreferences prefs;

    private final Runnable reconnect = new Runnable() {
        @Override
        public void run() {
            if (foreground && !connected && !connecting) {
                connect();
            }

            if (foreground) {
                handler.postDelayed(this, 3500);
            }
        }
    };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        prefs = getSharedPreferences(
            "ledcar_v08",
            MODE_PRIVATE
        );

        brightness = prefs.getInt("brightness", 100);
        speed = prefs.getInt("speed", 50);
        color = prefs.getInt("color", Color.RED);
        mode = prefs.getInt("mode", 0);
        lightTheme = prefs.getBoolean("light", false);

        BluetoothManager manager =
            (BluetoothManager) getSystemService(
                Context.BLUETOOTH_SERVICE
            );

        adapter = manager == null
            ? null
            : manager.getAdapter();

        render();
        ensurePermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();

        foreground = true;
        handler.removeCallbacks(reconnect);
        handler.post(reconnect);
    }

    @Override
    protected void onPause() {
        foreground = false;
        handler.removeCallbacks(reconnect);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        foreground = false;

        handler.removeCallbacksAndMessages(null);

        // 앱 종료 시 조명 OFF 명령을 보내지 않음.
        // 컨트롤러 자체 모드가 유지되도록 처리.

        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) {
            }
        }

        gatt = null;
        characteristic = null;

        super.onDestroy();
    }

    private void ensurePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (
                checkSelfPermission(
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
                ||
                checkSelfPermission(
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                    },
                    101
                );
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode,
        String[] permissions,
        int[] results
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            results
        );

        if (requestCode == 101) {
            connect();
        }
    }

    private boolean permitted() {
        return Build.VERSION.SDK_INT < 31
            ||
            checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED;
    }

    private void connect() {
        if (
            !permitted()
            || adapter == null
            || !adapter.isEnabled()
            || connected
            || connecting
        ) {
            return;
        }

        long now =
            android.os.SystemClock.elapsedRealtime();

        if (now - lastConnectAttempt < 3000) {
            return;
        }

        lastConnectAttempt = now;
        connecting = true;

        setStatus("연결 중…");

        try {
            BluetoothDevice device =
                adapter.getRemoteDevice(ADDRESS);

            if (gatt != null) {
                try {
                    gatt.close();
                } catch (Exception ignored) {
                }

                gatt = null;
            }

            gatt = device.connectGatt(
                this,
                false,
                callback,
                BluetoothDevice.TRANSPORT_LE
            );

        } catch (Exception e) {
            connecting = false;
            setStatus("연결 실패");
        }
    }

    private final BluetoothGattCallback callback =
        new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(
            BluetoothGatt source,
            int statusCode,
            int newState
        ) {
            if (
                newState == BluetoothProfile.STATE_CONNECTED
                &&
                statusCode == BluetoothGatt.GATT_SUCCESS
            ) {
                try {
                    source.discoverServices();
                } catch (SecurityException e) {
                    connectionLost(source);
                }
            } else {
                connectionLost(source);
            }
        }

        @Override
        public void onServicesDiscovered(
            BluetoothGatt source,
            int statusCode
        ) {
            if (
                statusCode != BluetoothGatt.GATT_SUCCESS
            ) {
                connectionLost(source);
                return;
            }

            BluetoothGattService service =
                source.getService(SERVICE);

            BluetoothGattCharacteristic c =
                service == null
                    ? null
                    : service.getCharacteristic(WRITE);

            if (c == null) {
                connectionLost(source);
                return;
            }

            handler.post(() -> {
                if (source != gatt) {
                    return;
                }

                characteristic = c;
                connecting = false;
                connected = true;
                sending = false;

                setStatus("연결됨");
                drain();

                // 재연결 시 기존 조명 모드를 덮어쓰지 않음.
            });
        }

        @Override
        public void onCharacteristicWrite(
            BluetoothGatt source,
            BluetoothGattCharacteristic c,
            int statusCode
        ) {
            handler.post(() -> {
                if (source != gatt) {
                    return;
                }

                sending = false;

                handler.postDelayed(
                    () -> drain(),
                    65
                );
            });
        }
    };

    private void connectionLost(
        BluetoothGatt source
    ) {
        handler.post(() -> {
            if (source != gatt) {
                return;
            }

            connected = false;
            connecting = false;
            characteristic = null;
            sending = false;

            outbound.clear();

            setStatus("연결 끊김 · 자동 재연결");

            try {
                source.close();
            } catch (Exception ignored) {
            }

            if (gatt == source) {
                gatt = null;
            }
        });
    }

    private void send(byte[] bytes) {
        if (
            !connected
            || characteristic == null
        ) {
            toast("블루투스 연결을 확인해 주세요");
            connect();
            return;
        }

        outbound.offer(bytes);
        drain();
    }

    private void drain() {
        if (
            !connected
            || sending
            || characteristic == null
            || gatt == null
            || outbound.isEmpty()
        ) {
            return;
        }

        byte[] packet = outbound.poll();

        sending = true;

        try {
            int result;

            if (Build.VERSION.SDK_INT >= 33) {
                result = gatt.writeCharacteristic(
                    characteristic,
                    packet,
                    BluetoothGattCharacteristic
                        .WRITE_TYPE_NO_RESPONSE
                );
            } else {
                characteristic.setWriteType(
                    BluetoothGattCharacteristic
                        .WRITE_TYPE_NO_RESPONSE
                );

                characteristic.setValue(packet);

                result = gatt.writeCharacteristic(
                    characteristic
                )
                    ? BluetoothGatt.GATT_SUCCESS
                    : -1;
            }

            if (
                result != BluetoothGatt.GATT_SUCCESS
            ) {
                sending = false;

                handler.postDelayed(
                    () -> drain(),
                    130
                );
            } else {
                handler.postDelayed(() -> {
                    if (sending) {
                        sending = false;
                        drain();
                    }
                }, 130);
            }

        } catch (Exception e) {
            sending = false;

            handler.postDelayed(
                () -> drain(),
                150
            );
        }
    }

    private void setStatus(String text) {
        handler.post(() -> {
            if (status != null) {
                status.setText("● " + text);
            }
        });
    }

    private void toast(String text) {
        handler.post(() ->
            Toast.makeText(
                this,
                text,
                Toast.LENGTH_SHORT
            ).show()
        );
    }

    private static byte[] hex(String input) {
        String[] parts = input.split(" ");

        byte[] b = new byte[parts.length];

        for (int i = 0; i < parts.length; i++) {
            b[i] = (byte) Integer.parseInt(
                parts[i],
                16
            );
        }

        return b;
    }

    private byte[] rgbPacket() {
        return new byte[]{
            0x7B,
            0x01,
            0x07,
            (byte) Color.red(color),
            (byte) Color.green(color),
            (byte) Color.blue(color),
            0x00,
            (byte) 0xFF,
            (byte) 0xBF
        };
    }

    private byte[] speedPacket() {
        return new byte[]{
            0x7B,
            (byte) 0xFF,
            0x02,
            (byte) Math.max(
                1,
                Math.min(100, speed)
            ),
            (byte) 0xFF,
            0x00,
            (byte) 0xFF,
            (byte) 0xFF,
            (byte) 0xBF
        };
    }

    private byte[] brightnessPacket() {
        int b = Math.max(
            1,
            Math.min(100, brightness)
        );

        return new byte[]{
            0x7B,
            (byte) 0xFF,
            0x01,
            (byte) (b == 1 ? 0x00 : 0x20),
            (byte) b,
            0x02,
            (byte) 0xFF,
            (byte) 0xFF,
            (byte) 0xBF
        };
    }

    private void setBrightness(int v) {
        brightness = Math.max(1, v);

        prefs.edit()
            .putInt("brightness", brightness)
            .apply();

        send(brightnessPacket());
    }

    private void setSpeed(int v) {
        speed = Math.max(1, v);

        prefs.edit()
            .putInt("speed", speed)
            .apply();

        send(speedPacket());
    }

    private void selectColor(int c) {
        color = Color.rgb(
            Color.red(c),
            Color.green(c),
            Color.blue(c)
        );

        prefs.edit()
            .putInt("color", color)
            .apply();

        send(rgbPacket());
    }

    private void startMode() {
        send(
            mode == 0
                ? SEQUENTIAL
                : RAINBOW
        );

        handler.postDelayed(() -> {
            if (connected) {
                send(speedPacket());
            }
        }, 230);
    }

    private void stopMode() {
        send(rgbPacket());
    }

    private void render() {
        if (lightTheme) {
            themeBg = 0xFFF6F7FA;
            themePanel = Color.WHITE;
            themeText = 0xFF15161A;
            themeMuted = 0xFF616572;
        } else {
            themeBg = 0xFF101116;
            themePanel = 0xFF202127;
            themeText = Color.WHITE;
            themeMuted = 0xFFB8BAC5;
        }

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        root.setPadding(
            dp(18),
            dp(18),
            dp(18),
            dp(12)
        );

        root.setBackgroundColor(themeBg);

        setContentView(root);

        LinearLayout heading = row();

        TextView title = label(
            "엠비언트 설정",
            23,
            true
        );

        heading.addView(
            title,
            new LinearLayout.LayoutParams(
                0,
                dp(44),
                1
            )
        );

        Button powerButton = button(
            power ? "전원 ON" : "전원 OFF"
        );

        heading.addView(powerButton);

        powerButton.setOnClickListener(v -> {
            power = !power;

            send(
                power
                    ? POWER_ON
                    : POWER_OFF
            );

            render();
        });

        root.addView(heading);

        status = label(
            "● " + (
                connected
                    ? "연결됨"
                    : "연결 대기 중"
            ),
            13,
            false
        );

        status.setTextColor(
            connected
                ? 0xFF4BD69B
                : themeMuted
        );

        root.addView(status);

        space(root, 14);

        LinearLayout nav = row();

        Button colors = button("색상");
        Button modes = button("모드");
        Button settings = button("설정");

        nav.addView(
            colors,
            new LinearLayout.LayoutParams(
                0,
                dp(48),
                1
            )
        );

        nav.addView(
            modes,
            new LinearLayout.LayoutParams(
                0,
                dp(48),
                1
            )
        );

        nav.addView(
            settings,
            new LinearLayout.LayoutParams(
                0,
                dp(48),
                1
            )
        );

        colors.setAlpha(
            tab == 0 ? 1f : .55f
        );

        modes.setAlpha(
            tab == 1 ? 1f : .55f
        );

        settings.setAlpha(
            tab == 2 ? 1f : .55f
        );

        colors.setOnClickListener(v -> {
            tab = 0;
            render();
        });

        modes.setOnClickListener(v -> {
            tab = 1;
            render();
        });

        settings.setOnClickListener(v -> {
            tab = 2;
            render();
        });

        root.addView(nav);

        space(root, 12);

        ScrollView scroll =
            new ScrollView(this);

        scroll.setFillViewport(false);

        root.addView(
            scroll,
            new LinearLayout.LayoutParams(
                -1,
                0,
                1
            )
        );

        content = new LinearLayout(this);

        content.setOrientation(
            LinearLayout.VERTICAL
        );

        content.setPadding(
            0,
            dp(8),
            0,
            dp(24)
        );

        scroll.addView(content);

        if (tab == 0) {
            colorPage();
        } else if (tab == 1) {
            modePage();
        } else {
            settingsPage();
        }
    }

    private void colorPage() {
        content.addView(
            label(
                "원하는 색상을 선택하세요",
                18,
                true
            )
        );

        space(content, 12);

        ColorWheel wheel =
            new ColorWheel(this);

        content.addView(
            wheel,
            new LinearLayout.LayoutParams(
                -1,
                dp(270)
            )
        );

        space(content, 10);

        TextView hexLabel = label(
            String.format(
                Locale.US,
                "#%06X",
                0xFFFFFF & color
            ),
            16,
            true
        );

        hexLabel.setGravity(
            Gravity.CENTER
        );

        content.addView(hexLabel);

        wheel.listener = c -> {
            selectColor(c);

            hexLabel.setText(
                String.format(
                    Locale.US,
                    "#%06X",
                    0xFFFFFF & color
                )
            );
        };

        space(content, 18);

        int[] palette = {
            0xFFFF3030,
            0xFFFF9C22,
            0xFFFFEB3B,
            0xFF37D76B,
            0xFF2D8CFF,
            0xFFA84AFF
        };

        LinearLayout swatches = row();

        for (int c : palette) {
            Button b = button("●");

            b.setTextColor(c);

            swatches.addView(
                b,
                new LinearLayout.LayoutParams(
                    0,
                    dp(52),
                    1
                )
            );

            b.setOnClickListener(v -> {
                selectColor(c);
                render();
            });
        }

        content.addView(swatches);

        space(content, 12);

        colorSlider(
            "R",
            Color.red(color),
            0xFFFF6666,
            0
        );

        colorSlider(
            "G",
            Color.green(color),
            0xFF55D18C,
            1
        );

        colorSlider(
            "B",
            Color.blue(color),
            0xFF5D9BFF,
            2
        );

        brightnessControl();
    }

    private void colorSlider(
        String name,
        int initial,
        int tint,
        int component
    ) {
        TextView title = label(
            name + "  " + initial,
            14,
            true
        );

        content.addView(title);

        SeekBar bar = new SeekBar(this);

        bar.setMax(255);
        bar.setProgress(initial);

        bar.setProgressTintList(
            android.content.res.ColorStateList
                .valueOf(tint)
        );

        content.addView(bar);

        bar.setOnSeekBarChangeListener(
            new SimpleSeek() {
                @Override
                public void onProgressChanged(
                    SeekBar s,
                    int p,
                    boolean user
                ) {
                    if (!user) {
                        return;
                    }

                    int r = Color.red(color);
                    int g = Color.green(color);
                    int b = Color.blue(color);

                    if (component == 0) {
                        r = p;
                    } else if (component == 1) {
                        g = p;
                    } else {
                        b = p;
                    }

                    color = Color.rgb(r, g, b);

                    title.setText(
                        name + "  " + p
                    );
                }

                @Override
                public void onStopTrackingTouch(
                    SeekBar s
                ) {
                    selectColor(color);
                }
            }
        );
    }

    private void modePage() {
        content.addView(
            label(
                "컨트롤러 자체 조명 효과",
                19,
                true
            )
        );

        space(content, 8);

        TextView explanation = label(
            "한 번 실행하면 휴대폰 연결이 끊겨도 " +
            "컨트롤러가 효과를 계속 실행할 수 있습니다.",
            14,
            false
        );

        explanation.setTextColor(
            themeMuted
        );

        content.addView(explanation);

        space(content, 18);

        Button sequential =
            button("순차 점등");

        Button rainbow =
            button("무지개 순환");

        sequential.setAlpha(
            mode == 0 ? 1f : .55f
        );

        rainbow.setAlpha(
            mode == 1 ? 1f : .55f
        );

        content.addView(
            sequential,
            new LinearLayout.LayoutParams(
                -1,
                dp(54)
            )
        );

        space(content, 8);

        content.addView(
            rainbow,
            new LinearLayout.LayoutParams(
                -1,
                dp(54)
            )
        );

        sequential.setOnClickListener(v -> {
            mode = 0;

            prefs.edit()
                .putInt("mode", mode)
                .apply();

            render();
        });

        rainbow.setOnClickListener(v -> {
            mode = 1;

            prefs.edit()
                .putInt("mode", mode)
                .apply();

            render();
        });

        space(content, 22);

        LinearLayout actions = row();

        Button start =
            button("▶  효과 시작");

        Button stop =
            button("■  효과 종료");

        actions.addView(
            start,
            new LinearLayout.LayoutParams(
                0,
                dp(58),
                1
            )
        );

        actions.addView(
            stop,
            new LinearLayout.LayoutParams(
                0,
                dp(58),
                1
            )
        );

        content.addView(actions);

        start.setOnClickListener(
            v -> startMode()
        );

        stop.setOnClickListener(
            v -> stopMode()
        );

        space(content, 22);

        TextView speedLabel = label(
            "효과 속도  ·  " + speed,
            16,
            true
        );

        content.addView(speedLabel);

        SeekBar speedBar =
            new SeekBar(this);

        speedBar.setMax(99);
        speedBar.setProgress(speed - 1);

        content.addView(speedBar);

        LinearLayout extremes = row();

        TextView slow = label(
            "느리게",
            12,
            false
        );

        TextView fast = label(
            "빠르게",
            12,
            false
        );

        extremes.addView(
            slow,
            new LinearLayout.LayoutParams(
                0,
                -2,
                1
            )
        );

        extremes.addView(fast);

        content.addView(extremes);

        speedBar.setOnSeekBarChangeListener(
            new SimpleSeek() {
                @Override
                public void onProgressChanged(
                    SeekBar s,
                    int p,
                    boolean user
                ) {
                    if (user) {
                        speed = p + 1;

                        speedLabel.setText(
                            "효과 속도  ·  " + speed
                        );
                    }
                }

                @Override
                public void onStopTrackingTouch(
                    SeekBar s
                ) {
                    setSpeed(speed);
                }
            }
        );

        space(content, 24);

        brightnessControl();

        space(content, 20);

        TextView tip = label(
            "효과 종료 시 마지막으로 선택한 " +
            "고정 RGB 색상으로 돌아갑니다. " +
            "속도 값의 빠름/느림 방향은 " +
            "실차에서 확인해 주세요.",
            13,
            false
        );

        tip.setTextColor(themeMuted);

        content.addView(tip);
    }

    private void brightnessControl() {
        space(content, 14);

        TextView title = label(
            "밝기  ·  " + brightness + "%",
            16,
            true
        );

        content.addView(title);

        SeekBar bar = new SeekBar(this);

        bar.setMax(99);
        bar.setProgress(brightness - 1);

        content.addView(bar);

        bar.setOnSeekBarChangeListener(
            new SimpleSeek() {
                @Override
                public void onProgressChanged(
                    SeekBar s,
                    int p,
                    boolean user
                ) {
                    if (user) {
                        brightness = p + 1;

                        title.setText(
                            "밝기  ·  " +
                            brightness + "%"
                        );
                    }
                }

                @Override
                public void onStopTrackingTouch(
                    SeekBar s
                ) {
                    setBrightness(brightness);
                }
            }
        );
    }

    private void settingsPage() {
        content.addView(
            label(
                "연결 및 화면 설정",
                19,
                true
            )
        );

        space(content, 16);

        content.addView(
            label(
                "컨트롤러: LEDCAR-01-047B",
                15,
                false
            )
        );

        space(content, 8);

        content.addView(
            label(
                "BLE 주소: " + ADDRESS,
                13,
                false
            )
        );

        space(content, 20);

        Button retry =
            button("블루투스 다시 연결");

        content.addView(
            retry,
            new LinearLayout.LayoutParams(
                -1,
                dp(54)
            )
        );

        retry.setOnClickListener(v -> {
            if (gatt != null) {
                try {
                    gatt.disconnect();
                    gatt.close();
                } catch (Exception ignored) {
                }

                gatt = null;
            }

            connected = false;
            connecting = false;
            characteristic = null;
            lastConnectAttempt = 0;

            connect();
        });

        space(content, 12);

        Button theme = button(
            lightTheme
                ? "다크 모드로 변경"
                : "라이트 모드로 변경"
        );

        content.addView(
            theme,
            new LinearLayout.LayoutParams(
                -1,
                dp(54)
            )
        );

        theme.setOnClickListener(v -> {
            lightTheme = !lightTheme;

            prefs.edit()
                .putBoolean(
                    "light",
                    lightTheme
                )
                .apply();

            render();
        });

        space(content, 24);

        TextView warning = label(
            "주의: 앱을 종료하거나 블루투스를 끊어도 " +
            "컨트롤러의 모드가 유지되는지는 " +
            "장치 펌웨어 동작에 따라 달라집니다. " +
            "앱 종료 시 OFF 명령은 전송하지 않습니다.",
            13,
            false
        );

        warning.setTextColor(
            themeMuted
        );

        content.addView(warning);
    }

    private LinearLayout row() {
        LinearLayout l =
            new LinearLayout(this);

        l.setOrientation(
            LinearLayout.HORIZONTAL
        );

        l.setGravity(
            Gravity.CENTER_VERTICAL
        );

        return l;
    }

    private TextView label(
        String s,
        int size,
        boolean bold
    ) {
        TextView t = new TextView(this);

        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(themeText);

        t.setGravity(
            Gravity.CENTER_VERTICAL
        );

        if (bold) {
            t.setTypeface(null, 1);
        }

        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);

        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(themeText);

        b.setBackgroundTintList(
            android.content.res.ColorStateList
                .valueOf(themePanel)
        );

        b.setPadding(
            dp(6),
            0,
            dp(6),
            0
        );

        return b;
    }

    private void space(
        LinearLayout parent,
        int height
    ) {
        View v = new View(this);

        parent.addView(
            v,
            new LinearLayout.LayoutParams(
                1,
                dp(height)
            )
        );
    }

    private int dp(float v) {
        return (int) (
            getResources()
                .getDisplayMetrics()
                .density * v + .5f
        );
    }

    private static abstract class SimpleSeek
        implements SeekBar.OnSeekBarChangeListener {

        @Override
        public void onStartTrackingTouch(
            SeekBar s
        ) {
        }

        @Override
        public void onStopTrackingTouch(
            SeekBar s
        ) {
        }

        @Override
        public void onProgressChanged(
            SeekBar s,
            int progress,
            boolean user
        ) {
        }
    }

    private class ColorWheel extends View {

        final Paint p = new Paint(3);

        final float[] hsv = {
            0,
            1,
            1
        };

        java.util.function.Consumer<Integer> listener;

        ColorWheel(Context c) {
            super(c);

            setLayerType(
                View.LAYER_TYPE_SOFTWARE,
                null
            );
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;

            float r = Math.min(
                getWidth(),
                getHeight()
            ) * .43f;

            p.setStyle(
                Paint.Style.STROKE
            );

            p.setStrokeWidth(dp(28));

            for (int i = 0; i < 360; i += 2) {
                hsv[0] = i;

                p.setColor(
                    Color.HSVToColor(hsv)
                );

                canvas.drawArc(
                    new RectF(
                        cx - r,
                        cy - r,
                        cx + r,
                        cy + r
                    ),
                    i - 90,
                    2.7f,
                    false,
                    p
                );
            }

            p.setStyle(
                Paint.Style.FILL
            );

            p.setColor(color);

            canvas.drawCircle(
                cx,
                cy,
                r * .53f,
                p
            );

            p.setColor(Color.WHITE);

            p.setStyle(
                Paint.Style.STROKE
            );

            p.setStrokeWidth(dp(3));

            canvas.drawCircle(
                cx,
                cy,
                r * .54f,
                p
            );

            p.setStyle(
                Paint.Style.FILL
            );
        }

        @Override
        public boolean onTouchEvent(
            MotionEvent e
        ) {
            if (
                e.getAction() == MotionEvent.ACTION_DOWN
                ||
                e.getAction() == MotionEvent.ACTION_MOVE
                ||
                e.getAction() == MotionEvent.ACTION_UP
            ) {
                float a = (float) Math.toDegrees(
                    Math.atan2(
                        e.getY() - getHeight() / 2f,
                        e.getX() - getWidth() / 2f
                    )
                ) + 90;

                if (a < 0) {
                    a += 360;
                }

                hsv[0] = a;
                hsv[1] = 1;
                hsv[2] = 1;

                int c = Color.HSVToColor(hsv);

                color = c;

                invalidate();

                if (
                    e.getAction() == MotionEvent.ACTION_UP
                    &&
                    listener != null
                ) {
                    listener.accept(c);
                }

                return true;
            }

            return true;
        }
    }
}
