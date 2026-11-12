
package com.example.ledcar;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;

import java.util.*;

public class MainActivity extends Activity {

    // ==============================
    // BLE
    // ==============================

    private static final UUID SERVICE_UUID =
        UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");

    private static final UUID WRITE_UUID =
        UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");

    private static final byte[] ON =
        hex("7BFF0407FFFFFFFFBF");

    private static final byte[] OFF =
        hex("7BFF0406FFFFFFFFBF");

    private static final byte[] SEQUENTIAL =
        hex("7BFF0351FFFFFFFFBF");

    private static final byte[] RAINBOW =
        hex("7BFF0301FFFFFFFFBF");

    private static final int REQUEST_BLE = 1001;
    private static final String PASSWORD = "8884";

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;

    private final Handler handler =
        new Handler(Looper.getMainLooper());

    private final ArrayDeque<byte[]> sendQueue =
        new ArrayDeque<>();

    private final LinkedHashMap<String, BluetoothDevice>
        foundDevices = new LinkedHashMap<>();

    private boolean sending = false;
    private boolean scanning = false;
    private boolean connected = false;
    private boolean connecting = false;

    private String deviceAddress = "";
    private String deviceName = "";

    // ==============================
    // APP STATE
    // ==============================

    private android.content.SharedPreferences prefs;

    private int currentPage = 0;
    // 0 color, 1 mode, 2 settings, 3 developer

    private boolean darkTheme = true;
    private boolean autoReconnect = true;
    private boolean powerOn = true;

    private int red = 255;
    private int green = 255;
    private int blue = 255;

    private int brightness = 100;
    private int speed = 50;

    private int selectedMode = 0;
    // 0 sequential, 1 rainbow

    private boolean modeRunning = false;

    private int sequentialEngine = 0;
    // 0 controller, 1 smartphone

    private int developerSpeed = 1;

    private final int[] presets = {
        Color.RED,
        Color.GREEN,
        Color.BLUE,
        Color.YELLOW,
        Color.CYAN,
        Color.MAGENTA
    };

    private LinearLayout root;
    private LinearLayout content;
    private TextView statusText;

    private SeekBar speedBar;
    private TextView speedValue;
    private TextView modeStateText;

    private final ArrayList<String> commandLogs =
        new ArrayList<>();

    private Runnable phoneAnimation;
    private boolean phoneAnimationActive = false;
    private long phoneAnimationStart = 0;

    private int commandGeneration = 0;

    // ==============================
    // LIFECYCLE
    // ==============================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(
            "ledcar_preferences",
            MODE_PRIVATE
        );

        loadSettings();

        BluetoothManager manager =
            (BluetoothManager) getSystemService(
                Context.BLUETOOTH_SERVICE
            );

        if (manager != null) {
            adapter = manager.getAdapter();
        }

        buildPage();

        requestBluetoothPermissions();

        if (autoReconnect && !deviceAddress.isEmpty()) {
            handler.postDelayed(
                () -> reconnectSavedDevice(),
                1200
            );
        }
    }

    @Override
    protected void onDestroy() {
        // Controller-native animations should not
        // be stopped merely because the app closes.
        stopPhoneAnimation();

        stopScan();

        clearSendQueue();

        disconnectGatt();

        handler.removeCallbacksAndMessages(null);

        super.onDestroy();
    }

    // ==============================
    // SETTINGS PERSISTENCE
    // ==============================

    private void loadSettings() {
        darkTheme = prefs.getBoolean("darkTheme", true);
        autoReconnect = prefs.getBoolean(
            "autoReconnect", true
        );
        powerOn = prefs.getBoolean("powerOn", true);

        red = prefs.getInt("red", 255);
        green = prefs.getInt("green", 255);
        blue = prefs.getInt("blue", 255);

        brightness = prefs.getInt("brightness", 100);
        speed = prefs.getInt("speed", 50);

        selectedMode = prefs.getInt("selectedMode", 0);
        sequentialEngine = prefs.getInt(
            "sequentialEngine", 0
        );

        deviceAddress = prefs.getString(
            "deviceAddress", ""
        );

        deviceName = prefs.getString(
            "deviceName", ""
        );

        for (int i = 0; i < presets.length; i++) {
            presets[i] = prefs.getInt(
                "preset_" + i,
                presets[i]
            );
        }

        // Do not assume an effect is still running
        // after a fresh application launch.
        modeRunning = false;
    }

    private void saveSettings() {
        android.content.SharedPreferences.Editor e =
            prefs.edit();

        e.putBoolean("darkTheme", darkTheme);
        e.putBoolean("autoReconnect", autoReconnect);
        e.putBoolean("powerOn", powerOn);

        e.putInt("red", red);
        e.putInt("green", green);
        e.putInt("blue", blue);

        e.putInt("brightness", brightness);
        e.putInt("speed", speed);

        e.putInt("selectedMode", selectedMode);
        e.putInt("sequentialEngine", sequentialEngine);

        e.putString("deviceAddress", deviceAddress);
        e.putString("deviceName", deviceName);

        for (int i = 0; i < presets.length; i++) {
            e.putInt("preset_" + i, presets[i]);
        }

        e.apply();
    }

    // ==============================
    // COLORS / THEME
    // ==============================

    private int backgroundColor() {
        return darkTheme
            ? Color.rgb(16, 17, 20)
            : Color.rgb(247, 248, 250);
    }

    private int surfaceColor() {
        return darkTheme
            ? Color.rgb(31, 33, 38)
            : Color.WHITE;
    }

    private int textColor() {
        return darkTheme
            ? Color.WHITE
            : Color.rgb(25, 27, 32);
    }

    private int secondaryColor() {
        return darkTheme
            ? Color.rgb(170, 174, 185)
            : Color.rgb(100, 105, 115);
    }

    private int accentColor() {
        return Color.rgb(68, 133, 255);
    }

    private int dp(float value) {
        return (int) (
            value * getResources()
                .getDisplayMetrics().density + 0.5f
        );
    }

    private android.graphics.drawable.GradientDrawable
    rounded(int color, int radius) {

        android.graphics.drawable.GradientDrawable d =
            new android.graphics.drawable.GradientDrawable();

        d.setColor(color);
        d.setCornerRadius(dp(radius));

        return d;
    }

    // ==============================
    // UI HELPERS
    // ==============================

    private TextView text(
        String value,
        float size,
        boolean bold
    ) {
        TextView t = new TextView(this);

        t.setText(value);
        t.setTextColor(textColor());
        t.setTextSize(size);

        if (bold) {
            t.setTypeface(
                null,
                android.graphics.Typeface.BOLD
            );
        }

        return t;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private void addSpace(
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

    private void addTitle(
        LinearLayout parent,
        String title
    ) {
        TextView t = text(title, 17, true);
        parent.addView(t);
        addSpace(parent, 12);
    }

    private LinearLayout card() {
        LinearLayout l = column();

        l.setPadding(
            dp(16), dp(16),
            dp(16), dp(16)
        );

        l.setBackground(
            rounded(surfaceColor(), 18)
        );

        return l;
    }

    private void addCard(
        LinearLayout parent,
        LinearLayout child
    ) {
        LinearLayout.LayoutParams p =
            new LinearLayout.LayoutParams(
                -1, -2
            );

        p.bottomMargin = dp(14);

        parent.addView(child, p);
    }

    private Button button(String label) {
        Button b = new Button(this);

        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setTextSize(14);

        b.setBackgroundTintList(
            ColorStateList.valueOf(accentColor())
        );

        return b;
    }

    private Button secondaryButton(String label) {
        Button b = button(label);

        b.setBackgroundTintList(
            ColorStateList.valueOf(
                darkTheme
                    ? Color.rgb(66, 69, 78)
                    : Color.rgb(105, 111, 125)
            )
        );

        return b;
    }

    private void addDescription(
        LinearLayout parent,
        String message
    ) {
        TextView t = text(message, 13, false);
        t.setTextColor(secondaryColor());
        parent.addView(t);
    }

    private void toast(String message) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_SHORT
        ).show();
    }

    // ==============================
    // PAGE STRUCTURE
    // ==============================

    private void buildPage() {
        root = column();
        root.setBackgroundColor(backgroundColor());

        setContentView(root);

        buildHeader();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        content = column();

        content.setPadding(
            dp(18), dp(18),
            dp(18), dp(24)
        );

        scroll.addView(content);

        root.addView(
            scroll,
            new LinearLayout.LayoutParams(
                -1, 0, 1
            )
        );

        switch (currentPage) {
            case 0:
                buildColorPage();
                break;

            case 1:
                buildModePage();
                break;

            case 2:
                buildSettingsPage();
                break;

            case 3:
                buildDeveloperPage();
                break;
        }

        if (currentPage == 0 || currentPage == 1) {
            buildBottomNavigation();
        }
    }

    private void buildHeader() {
        LinearLayout header = row();

        header.setPadding(
            dp(18), dp(16),
            dp(18), dp(12)
        );

        if (currentPage >= 2) {
            TextView back = text("‹", 32, false);
            back.setPadding(0, 0, dp(18), 0);

            back.setOnClickListener(v -> {
                currentPage =
                    currentPage == 3 ? 2 : 0;

                buildPage();
            });

            header.addView(back);
        }

        String title;

        if (currentPage == 2) {
            title = "설정";
        } else if (currentPage == 3) {
            title = "개발자 모드";
        } else {
            title = "엠비언트 설정";
        }

        TextView name = text(title, 22, true);

        header.addView(
            name,
            new LinearLayout.LayoutParams(
                0, -2, 1
            )
        );

        if (currentPage <= 1) {
            TextView settings = text("⚙", 25, false);

            settings.setPadding(
                dp(12), 0, dp(12), 0
            );

            settings.setOnClickListener(v -> {
                currentPage = 2;
                buildPage();
            });

            header.addView(settings);

            TextView power = text(
                powerOn ? "⏻" : "○",
                27,
                true
            );

            power.setTextColor(
                powerOn
                    ? accentColor()
                    : secondaryColor()
            );

            power.setOnClickListener(v -> togglePower());

            header.addView(power);
        }

        root.addView(header);

        statusText = text("", 12, false);
        statusText.setTextColor(secondaryColor());

        statusText.setPadding(
            dp(18), 0, dp(18), dp(10)
        );

        root.addView(statusText);

        updateStatus();
    }

    private void updateStatus() {
        if (statusText == null) return;

        String state;

        if (connected) {
            state = "● 연결됨";
        } else if (connecting) {
            state = "◌ 연결 중";
        } else {
            state = "○ 연결 안 됨";
        }

        if (!deviceName.isEmpty()) {
            state += " · " + deviceName;
        }

        statusText.setText(state);
    }

    private void buildBottomNavigation() {
        LinearLayout nav = row();

        nav.setPadding(
            dp(18), dp(8),
            dp(18), dp(12)
        );

        Button colors = button("색상");
        Button modes = button("모드");

        if (currentPage != 0) {
            colors = secondaryButton("색상");
        }

        if (currentPage != 1) {
            modes = secondaryButton("모드");
        }

        colors.setOnClickListener(v -> {
            currentPage = 0;
            buildPage();
        });

        modes.setOnClickListener(v -> {
            currentPage = 1;
            buildPage();
        });

        LinearLayout.LayoutParams p =
            new LinearLayout.LayoutParams(
                0, dp(50), 1
            );

        p.rightMargin = dp(6);

        nav.addView(colors, p);

        LinearLayout.LayoutParams q =
            new LinearLayout.LayoutParams(
                0, dp(50), 1
            );

        q.leftMargin = dp(6);

        nav.addView(modes, q);

        root.addView(nav);
    }

    // ==============================
    // COLOR PAGE
    // ==============================

    private void buildColorPage() {
        LinearLayout colorCard = card();

        addTitle(colorCard, "색상 선택");

        ColorWheel wheel = new ColorWheel(this);

        LinearLayout.LayoutParams wheelParams =
            new LinearLayout.LayoutParams(
                -1, dp(270)
            );

        colorCard.addView(wheel, wheelParams);

        addSpace(colorCard, 12);

        View preview = new View(this);

        preview.setBackground(
            rounded(
                Color.rgb(red, green, blue),
                12
            )
        );

        colorCard.addView(
            preview,
            new LinearLayout.LayoutParams(
                -1, dp(38)
            )
        );

        wheel.setOnColorSelectedListener(color -> {
            red = Color.red(color);
            green = Color.green(color);
            blue = Color.blue(color);

            saveSettings();
            applyColor();

            preview.setBackground(
                rounded(color, 12)
            );
        });

        addCard(content, colorCard);

        buildPresets();

        LinearLayout rgbCard = card();
        addTitle(rgbCard, "RGB 조절");

        addRgbSlider(rgbCard, "R", red, 0);
        addRgbSlider(rgbCard, "G", green, 1);
        addRgbSlider(rgbCard, "B", blue, 2);

        addCard(content, rgbCard);

        buildBrightnessCard();
    }

    private void buildPresets() {
        LinearLayout presetCard = card();

        addTitle(presetCard, "즐겨찾기 색상");

        LinearLayout items = row();

        for (int i = 0; i < presets.length; i++) {
            final int index = i;

            View swatch = new View(this);

            swatch.setBackground(
                rounded(presets[i], 50)
            );

            LinearLayout.LayoutParams p =
                new LinearLayout.LayoutParams(
                    0, dp(43), 1
                );

            p.leftMargin = dp(3);
            p.rightMargin = dp(3);

            items.addView(swatch, p);

            swatch.setOnClickListener(v -> {
                int c = presets[index];

                red = Color.red(c);
                green = Color.green(c);
                blue = Color.blue(c);

                saveSettings();
                applyColor();

                buildPage();
            });

            swatch.setOnLongClickListener(v -> {
                presets[index] =
                    Color.rgb(red, green, blue);

                saveSettings();

                toast("즐겨찾기 색상이 저장되었습니다.");

                buildPage();

                return true;
            });
        }

        presetCard.addView(items);

        addSpace(presetCard, 10);

        addDescription(
            presetCard,
            "짧게 누르면 적용, 길게 누르면 현재 색상을 저장합니다."
        );

        addCard(content, presetCard);
    }

    private void addRgbSlider(
        LinearLayout parent,
        String label,
        int value,
        int channel
    ) {
        TextView valueText = text(
            label + "  " + value,
            14,
            true
        );

        parent.addView(valueText);

        SeekBar bar = new SeekBar(this);
        bar.setMax(255);
        bar.setProgress(value);

        parent.addView(bar);

        bar.setOnSeekBarChangeListener(
            new SimpleSeekListener() {
                @Override
                public void onProgressChanged(
                    SeekBar seekBar,
                    int progress,
                    boolean fromUser
                ) {
                    if (!fromUser) return;

                    if (channel == 0) red = progress;
                    if (channel == 1) green = progress;
                    if (channel == 2) blue = progress;

                    valueText.setText(
                        label + "  " + progress
                    );

                    saveSettings();
                    applyColor();
                }
            }
        );
    }

    private void buildBrightnessCard() {
        LinearLayout brightnessCard = card();

        addTitle(brightnessCard, "밝기");

        TextView value = text(
            brightness + "%",
            14,
            true
        );

        brightnessCard.addView(value);

        SeekBar bar = new SeekBar(this);

        bar.setMax(100);
        bar.setProgress(brightness);

        brightnessCard.addView(bar);

        bar.setOnSeekBarChangeListener(
            new SimpleSeekListener() {
                @Override
                public void onProgressChanged(
                    SeekBar seekBar,
                    int progress,
                    boolean fromUser
                ) {
                    if (!fromUser) return;

                    brightness = progress;

                    value.setText(
                        brightness + "%"
                    );

                    saveSettings();
                    applyBrightness();
                }
            }
        );

        addCard(content, brightnessCard);
    }

    // ==============================
    // MODE PAGE
    // ==============================

    private void buildModePage() {
        LinearLayout modeCard = card();

        addTitle(modeCard, "조명 효과");

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(
            RadioGroup.VERTICAL
        );

        RadioButton sequential =
            new RadioButton(this);

        sequential.setText("순차 점등");
        sequential.setTextColor(textColor());
        sequential.setTextSize(16);

        RadioButton rainbow =
            new RadioButton(this);

        rainbow.setText("무지개 순환");
        rainbow.setTextColor(textColor());
        rainbow.setTextSize(16);

        group.addView(sequential);
        group.addView(rainbow);

        if (selectedMode == 0) {
            sequential.setChecked(true);
        } else {
            rainbow.setChecked(true);
        }

        group.setOnCheckedChangeListener(
            (g, checkedId) -> {
                int newMode =
                    sequential.isChecked() ? 0 : 1;

                if (newMode == selectedMode) return;

                boolean restart = modeRunning;

                if (restart) {
                    stopMode();
                }

                selectedMode = newMode;
                saveSettings();

                if (restart) {
                    startMode();
                }

                buildPage();
            }
        );

        modeCard.addView(group);

        addSpace(modeCard, 14);

        modeStateText = text(
            modeRunning ? "실행 중" : "중지됨",
            13,
            false
        );

        modeStateText.setTextColor(
            secondaryColor()
        );

        modeCard.addView(modeStateText);

        addSpace(modeCard, 12);

        Button modeButton = button(
            modeRunning
                ? "조명 효과 중지"
                : "조명 효과 시작"
        );

        modeButton.setOnClickListener(v -> {
            if (modeRunning) {
                stopMode();
            } else {
                startMode();
            }

            buildPage();
        });

        modeCard.addView(modeButton);

        addCard(content, modeCard);

        LinearLayout speedCard = card();

        addTitle(speedCard, "효과 속도");

        speedValue = text(
            speed + "%",
            15,
            true
        );

        speedCard.addView(speedValue);

        speedBar = new SeekBar(this);
        speedBar.setMax(99);
        speedBar.setProgress(speed - 1);

        speedCard.addView(speedBar);

        LinearLayout labels = row();

        TextView slow = text(
            "느리게",
            12,
            false
        );

        TextView fast = text(
            "빠르게",
            12,
            false
        );

        slow.setTextColor(secondaryColor());
        fast.setTextColor(secondaryColor());

        labels.addView(
            slow,
            new LinearLayout.LayoutParams(
                0, -2, 1
            )
        );

        labels.addView(fast);

        speedCard.addView(labels);

        speedBar.setOnSeekBarChangeListener(
            new SimpleSeekListener() {
                @Override
                public void onProgressChanged(
                    SeekBar seekBar,
                    int progress,
                    boolean fromUser
                ) {
                    if (!fromUser) return;

                    speed = progress + 1;

                    speedValue.setText(
                        speed + "%"
                    );

                    saveSettings();
                }

                @Override
                public void onStopTrackingTouch(
                    SeekBar seekBar
                ) {
                    if (!modeRunning) return;

                    if (isPhoneSequential()) {
                        restartPhoneAnimation();
                    } else {
                        send(speedPacket(speed));
                    }
                }
            }
        );

        addCard(content, speedCard);

        LinearLayout info = card();

        addTitle(info, "현재 실행 방식");

        if (selectedMode == 1) {
            addDescription(
                info,
                "무지개 순환 · 컨트롤러 자체 제어"
            );
        } else if (sequentialEngine == 0) {
            addDescription(
                info,
                "순차 점등 · 컨트롤러 자체 제어"
            );
        } else {
            addDescription(
                info,
                "순차 점등 · 스마트폰 직접 제어"
            );
        }

        addCard(content, info);

        buildBrightnessCard();
    }

    private boolean isPhoneSequential() {
        return selectedMode == 0
            && sequentialEngine == 1;
    }

    // ==============================
    // EFFECT CONTROL
    // ==============================

    private void startMode() {
        if (!connected) {
            toast("블루투스 장치를 먼저 연결하세요.");
            return;
        }

        stopPhoneAnimation();

        modeRunning = true;

        commandGeneration++;
        clearSendQueue();

        if (isPhoneSequential()) {
            startPhoneAnimation();
        } else {
            sendModeCommand();
        }
    }

    private void stopMode() {
        modeRunning = false;

        commandGeneration++;

        stopPhoneAnimation();
        clearSendQueue();

        // Freeze the light at the selected RGB color.
        if (connected) {
            send(rgbPacket(red, green, blue));
        }
    }

    private void sendModeCommand() {
        if (!connected) return;

        if (selectedMode == 0) {
            send(SEQUENTIAL);
        } else {
            send(RAINBOW);
        }

        final int generation = commandGeneration;

        handler.postDelayed(() -> {
            if (
                generation == commandGeneration
                && modeRunning
                && connected
                && !isPhoneSequential()
            ) {
                send(speedPacket(speed));
            }
        }, 240);
    }

    // ==============================
    // PHONE ENGINE (v0.7 STYLE)
    // ==============================

    private static double smoothCycleSeconds(
        int value
    ) {
        double fraction =
            Math.max(0, Math.min(100, value))
            / 100.0;

        return 3600.0 *
            Math.pow(
                5.0 / 3600.0,
                fraction
            );
    }

    private void startPhoneAnimation() {
        if (!connected) return;

        stopPhoneAnimation();

        phoneAnimationActive = true;
        phoneAnimationStart =
            SystemClock.elapsedRealtime();

        final int generation = commandGeneration;

        phoneAnimation = new Runnable() {
            @Override
            public void run() {
                if (
                    !phoneAnimationActive
                    || !modeRunning
                    || !connected
                    || !isPhoneSequential()
                    || generation != commandGeneration
                ) {
                    return;
                }

                double elapsed =
                    (
                        SystemClock.elapsedRealtime()
                        - phoneAnimationStart
                    ) / 1000.0;

                double cycle =
                    smoothCycleSeconds(speed);

                float hue =
                    (float) (
                        (elapsed / cycle * 360.0)
                        % 360.0
                    );

                int color = Color.HSVToColor(
                    new float[] {
                        hue, 1f, 1f
                    }
                );

                send(
                    rgbPacket(
                        Color.red(color),
                        Color.green(color),
                        Color.blue(color)
                    )
                );

                handler.postDelayed(this, 100);
            }
        };

        handler.post(phoneAnimation);
    }

    private void stopPhoneAnimation() {
        phoneAnimationActive = false;

        if (phoneAnimation != null) {
            handler.removeCallbacks(
                phoneAnimation
            );

            phoneAnimation = null;
        }
    }

    private void restartPhoneAnimation() {
        if (
            modeRunning
            && isPhoneSequential()
            && connected
        ) {
            startPhoneAnimation();
        }
    }

    // ==============================
    // COLOR / POWER COMMANDS
    // ==============================

    private void applyColor() {
        if (!connected) return;

        if (modeRunning) {
            stopMode();
        }

        send(
            rgbPacket(red, green, blue)
        );
    }

    private void togglePower() {
        powerOn = !powerOn;
        saveSettings();

        if (!powerOn) {
            modeRunning = false;
            commandGeneration++;
            stopPhoneAnimation();
            clearSendQueue();
            send(OFF);
        } else {
            send(ON);

            handler.postDelayed(
                () -> {
                    if (powerOn && connected) {
                        send(
                            rgbPacket(
                                red, green, blue
                            )
                        );
                    }
                },
                240
            );
        }

        buildPage();
    }

    private void applyBrightness() {
        if (!connected) return;

        // The exact brightness protocol can differ
        // between LED controllers. This command uses
        // the RGB packet's brightness scaling.
        // Native running modes are left untouched.

        if (!modeRunning) {
            send(
                rgbPacket(
                    red, green, blue
                )
            );
        }
    }

    private byte[] rgbPacket(
        int r,
        int g,
        int b
    ) {
        int rr = Math.round(
            clamp(r, 0, 255)
            * brightness / 100f
        );

        int gg = Math.round(
            clamp(g, 0, 255)
            * brightness / 100f
        );

        int bb = Math.round(
            clamp(b, 0, 255)
            * brightness / 100f
        );

        return new byte[] {
            0x7B,
            (byte) 0xFF,
            0x07,
            0x05,
            (byte) rr,
            (byte) gg,
            (byte) bb,
            (byte) 0xFF,
            (byte) 0xBF
        };
    }

    private static byte[] speedPacket(
        int value
    ) {
        int v = clamp(value, 0, 255);

        return new byte[] {
            0x7B,
            (byte) 0xFF,
            0x02,
            (byte) v,
            (byte) 0xFF,
            0x00,
            (byte) 0xFF,
            (byte) 0xFF,
            (byte) 0xBF
        };
    }

    private static int clamp(
        int v,
        int min,
        int max
    ) {
        return Math.max(
            min,
            Math.min(max, v)
        );
    }

    // ==============================
    // SETTINGS PAGE
    // ==============================

    private void buildSettingsPage() {
        LinearLayout bluetoothCard = card();

        addTitle(
            bluetoothCard,
            "블루투스 연결"
        );

        addDescription(
            bluetoothCard,
            connected
                ? "연결된 장치: " + deviceName
                : "현재 연결된 장치가 없습니다."
        );

        addSpace(bluetoothCard, 12);

        Button scanButton =
            button("블루투스 장치 검색");

        scanButton.setOnClickListener(
            v -> startScan()
        );

        bluetoothCard.addView(scanButton);

        addSpace(bluetoothCard, 8);

        Button disconnectButton =
            secondaryButton("연결 해제");

        disconnectButton.setOnClickListener(v -> {
            deviceAddress = "";
            deviceName = "";

            saveSettings();

            disconnectGatt();

            buildPage();
        });

        bluetoothCard.addView(disconnectButton);

        addSpace(bluetoothCard, 12);

        Switch reconnectSwitch =
            new Switch(this);

        reconnectSwitch.setText("자동 재연결");
        reconnectSwitch.setTextColor(textColor());

        reconnectSwitch.setChecked(
            autoReconnect
        );

        reconnectSwitch.setOnCheckedChangeListener(
            (buttonView, checked) -> {
                autoReconnect = checked;
                saveSettings();
            }
        );

        bluetoothCard.addView(reconnectSwitch);

        addCard(content, bluetoothCard);

        LinearLayout themeCard = card();

        addTitle(themeCard, "화면 설정");

        Switch themeSwitch = new Switch(this);

        themeSwitch.setText("다크 모드");
        themeSwitch.setTextColor(textColor());
        themeSwitch.setChecked(darkTheme);

        themeSwitch.setOnCheckedChangeListener(
            (buttonView, checked) -> {
                darkTheme = checked;
                saveSettings();
                buildPage();
            }
        );

        themeCard.addView(themeSwitch);

        addCard(content, themeCard);

        // Developer mode entry
        LinearLayout developerCard = card();

        addTitle(
            developerCard,
            "개발자 설정"
        );

        addDescription(
            developerCard,
            "BLE 명령 분석, 속도 테스트 및 순차 점등 엔진 설정"
        );

        addSpace(developerCard, 12);

        Button developerButton =
            secondaryButton(
                "🔒 개발자 모드"
            );

        developerButton.setOnClickListener(
            v -> showDeveloperPassword()
        );

        developerCard.addView(
            developerButton
        );

        addCard(content, developerCard);
    }

    // ==============================
    // DEVELOPER AUTHENTICATION
    // ==============================

    private void showDeveloperPassword() {
        final EditText password =
            new EditText(this);

        password.setSingleLine(true);
        password.setHint("비밀번호");

        password.setInputType(
            android.text.InputType
                .TYPE_CLASS_NUMBER
            |
            android.text.InputType
                .TYPE_NUMBER_VARIATION_PASSWORD
        );

        LinearLayout wrapper = column();

        wrapper.setPadding(
            dp(22), dp(8),
            dp(22), 0
        );

        wrapper.addView(password);

        AlertDialog dialog =
            new AlertDialog.Builder(this)
                .setTitle("개발자 모드 인증")
                .setMessage(
                    "비밀번호를 입력하세요."
                )
                .setView(wrapper)
                .setNegativeButton(
                    "취소", null
                )
                .setPositiveButton(
                    "확인", null
                )
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(
                AlertDialog.BUTTON_POSITIVE
            ).setOnClickListener(v -> {
                String input =
                    password.getText()
                        .toString().trim();

                if (PASSWORD.equals(input)) {
                    dialog.dismiss();

                    currentPage = 3;
                    buildPage();
                } else {
                    password.setText("");
                    password.setError(
                        "비밀번호가 올바르지 않습니다."
                    );
                }
            });
        });

        dialog.show();
    }

    // ==============================
    // DEVELOPER PAGE
    // ==============================

    private void buildDeveloperPage() {
        LinearLayout engineCard = card();

        addTitle(
            engineCard,
            "순차 점등 구현 방식"
        );

        addDescription(
            engineCard,
            "일반 모드 페이지의 순차 점등에 적용할 엔진을 선택합니다."
        );

        addSpace(engineCard, 14);

        RadioGroup engineGroup =
            new RadioGroup(this);

        RadioButton controllerRadio =
            new RadioButton(this);

        controllerRadio.setText(
            "컨트롤러 자체 제어 (v0.9)"
        );

        controllerRadio.setTextColor(
            textColor()
        );

        RadioButton phoneRadio =
            new RadioButton(this);

        phoneRadio.setText(
            "스마트폰 직접 제어 (v0.7)"
        );

        phoneRadio.setTextColor(
            textColor()
        );

        engineGroup.addView(controllerRadio);
        engineGroup.addView(phoneRadio);

        if (sequentialEngine == 0) {
            controllerRadio.setChecked(true);
        } else {
            phoneRadio.setChecked(true);
        }

        engineCard.addView(engineGroup);

        addSpace(engineCard, 12);

        TextView engineInfo = text(
            "",
            13,
            false
        );

        engineInfo.setTextColor(
            secondaryColor()
        );

        engineCard.addView(engineInfo);

        updateEngineInfo(engineInfo);

        engineGroup.setOnCheckedChangeListener(
            (group, checkedId) -> {
                int newEngine =
                    controllerRadio.isChecked()
                        ? 0 : 1;

                if (
                    newEngine == sequentialEngine
                ) {
                    return;
                }

                boolean restart =
                    modeRunning
                    && selectedMode == 0;

                if (restart) {
                    stopMode();
                }

                sequentialEngine = newEngine;

                saveSettings();

                updateEngineInfo(engineInfo);

                if (restart) {
                    startMode();
                }

                toast(
                    "순차 점등 방식이 변경되었습니다."
                );
            }
        );

        addCard(content, engineCard);

        buildDeveloperSpeedTest();
        buildDeveloperModeTests();
        buildDeveloperHexSender();
        buildDeveloperLogs();

        LinearLayout warning = card();

        addTitle(
            warning,
            "주의사항"
        );

        addDescription(
            warning,
            "스마트폰 직접 제어는 앱이 실행 중이고 BLE 연결이 유지되어야 작동합니다.\n\n" +
            "원시 속도값 0~255는 모두 지원된다고 확인된 범위가 아닙니다.\n\n" +
            "확인되지 않은 BLE 명령은 조명 동작에 영향을 줄 수 있습니다."
        );

        addCard(content, warning);
    }

    private void updateEngineInfo(
        TextView view
    ) {
        if (sequentialEngine == 0) {
            view.setText(
                "컨트롤러가 직접 그라데이션을 실행합니다.\n" +
                "앱을 종료해도 효과가 유지될 수 있습니다.\n" +
                "최저 속도는 컨트롤러의 지원 범위에 따라 제한됩니다."
            );
        } else {
            view.setText(
                "스마트폰에서 색상을 계산해 BLE로 전송합니다.\n" +
                "매우 느린 속도를 설정할 수 있습니다.\n" +
                "앱 종료 또는 BLE 연결 해제 시 효과가 중단됩니다."
            );
        }
    }

    // ==============================
    // DEVELOPER SPEED TEST
    // ==============================

    private void buildDeveloperSpeedTest() {
        LinearLayout speedCard = card();

        addTitle(
            speedCard,
            "BLE 속도 원시값 테스트"
        );

        addDescription(
            speedCard,
            "컨트롤러 자체 제어 방식에서 사용할 속도 명령을 직접 테스트합니다."
        );

        addSpace(speedCard, 14);

        TextView value = text(
            "원시값: " + developerSpeed,
            16,
            true
        );

        speedCard.addView(value);

        SeekBar bar = new SeekBar(this);

        bar.setMax(255);
        bar.setProgress(developerSpeed);

        speedCard.addView(bar);

        TextView packetPreview = text(
            bytesToHex(
                speedPacket(developerSpeed)
            ),
            13,
            false
        );

        packetPreview.setTextColor(
            secondaryColor()
        );

        speedCard.addView(packetPreview);

        bar.setOnSeekBarChangeListener(
            new SimpleSeekListener() {
                @Override
                public void onProgressChanged(
                    SeekBar seekBar,
                    int progress,
                    boolean fromUser
                ) {
                    developerSpeed = progress;

                    value.setText(
                        "원시값: " + progress
                    );

                    packetPreview.setText(
                        bytesToHex(
                            speedPacket(progress)
                        )
                    );
                }
            }
        );

        addSpace(speedCard, 10);

        LinearLayout quick1 = row();

        int[] firstValues = {
            0, 1, 2, 5
        };

        for (int n : firstValues) {
            Button b = secondaryButton(
                String.valueOf(n)
            );

            quick1.addView(
                b,
                new LinearLayout.LayoutParams(
                    0, dp(45), 1
                )
            );

            b.setOnClickListener(v -> {
                developerSpeed = n;
                bar.setProgress(n);
            });
        }

        speedCard.addView(quick1);

        LinearLayout quick2 = row();

        int[] secondValues = {
            10, 50, 100, 255
        };

        for (int n : secondValues) {
            Button b = secondaryButton(
                String.valueOf(n)
            );

            quick2.addView(
                b,
                new LinearLayout.LayoutParams(
                    0, dp(45), 1
                )
            );

            b.setOnClickListener(v -> {
                developerSpeed = n;
                bar.setProgress(n);
            });
        }

        speedCard.addView(quick2);

        addSpace(speedCard, 12);

        Button sendSpeed =
            button("현재 속도 명령 전송");

        sendSpeed.setOnClickListener(v -> {
            if (!connected) {
                toast("BLE 연결이 필요합니다.");
                return;
            }

            byte[] packet =
                speedPacket(developerSpeed);

            send(packet);

            toast(
                "속도 원시값 "
                + developerSpeed
                + " 전송 요청"
            );
        });

        speedCard.addView(sendSpeed);

        addCard(content, speedCard);
    }

    // ==============================
    // DEVELOPER MODE TESTS
    // ==============================

    private void buildDeveloperModeTests() {
        LinearLayout testCard = card();

        addTitle(
            testCard,
            "조명 효과 테스트"
        );

        Button sequential =
            button("컨트롤러 순차 점등");

        sequential.setOnClickListener(v -> {
            stopPhoneAnimation();
            commandGeneration++;
            clearSendQueue();

            selectedMode = 0;
            sequentialEngine = 0;
            modeRunning = true;

            saveSettings();
            sendModeCommand();

            toast(
                "컨트롤러 순차 점등 실행 요청"
            );
        });

        testCard.addView(sequential);

        addSpace(testCard, 8);

        Button rainbow =
            button("무지개 순환");

        rainbow.setOnClickListener(v -> {
            stopPhoneAnimation();
            commandGeneration++;
            clearSendQueue();

            selectedMode = 1;
            modeRunning = true;

            saveSettings();
            sendModeCommand();

            toast(
                "무지개 순환 실행 요청"
            );
        });

        testCard.addView(rainbow);

        addSpace(testCard, 8);

        Button phone =
            button("스마트폰 순차 점등");

        phone.setOnClickListener(v -> {
            stopPhoneAnimation();
            commandGeneration++;
            clearSendQueue();

            selectedMode = 0;
            sequentialEngine = 1;
            modeRunning = true;

            saveSettings();

            if (connected) {
                startPhoneAnimation();
            } else {
                modeRunning = false;
                toast("BLE 연결이 필요합니다.");
            }
        });

        testCard.addView(phone);

        addSpace(testCard, 8);

        Button stop =
            secondaryButton("조명 효과 중지");

        stop.setOnClickListener(v -> {
            stopMode();
            toast("조명 효과 중지 요청");
        });

        testCard.addView(stop);

        addCard(content, testCard);
    }

    // ==============================
    // DEVELOPER HEX SENDER
    // ==============================

    private void buildDeveloperHexSender() {
        LinearLayout hexCard = card();

        addTitle(
            hexCard,
            "BLE HEX 명령 직접 전송"
        );

        EditText hexInput =
            new EditText(this);

        hexInput.setSingleLine(false);
        hexInput.setMinLines(2);

        hexInput.setTextColor(textColor());

        hexInput.setHint(
            "7B FF 03 51 FF FF FF FF BF"
        );

        hexCard.addView(hexInput);

        addSpace(hexCard, 10);

        Button sendButton =
            button("HEX 명령 전송");

        sendButton.setOnClickListener(v -> {
            String input =
                hexInput.getText()
                    .toString()
                    .replaceAll("\\s+", "")
                    .toUpperCase(Locale.US);

            if (!input.matches(
                "[0-9A-F]{18}"
            )) {
                toast(
                    "9바이트 HEX 명령을 입력하세요."
                );
                return;
            }

            if (
                !input.startsWith("7B")
                || !input.endsWith("BF")
            ) {
                toast(
                    "패킷 시작값 또는 종료값이 올바르지 않습니다."
                );
                return;
            }

            if (!connected) {
                toast("BLE 연결이 필요합니다.");
                return;
            }

            byte[] packet = hex(input);

            new AlertDialog.Builder(this)
                .setTitle("BLE 명령 전송")
                .setMessage(
                    "다음 명령을 전송할까요?\n\n"
                    + bytesToHex(packet)
                )
                .setNegativeButton(
                    "취소", null
                )
                .setPositiveButton(
                    "전송",
                    (dialog, which) -> {
                        send(packet);
                        toast("전송 요청 완료");
                    }
                )
                .show();
        });

        hexCard.addView(sendButton);

        addCard(content, hexCard);
    }

    // ==============================
    // DEVELOPER LOGS
    // ==============================

    private void buildDeveloperLogs() {
        LinearLayout logCard = card();

        addTitle(
            logCard,
            "BLE 명령 기록"
        );

        TextView logs = text(
            getLogText(),
            12,
            false
        );

        logs.setTextColor(
            secondaryColor()
        );

        logCard.addView(logs);

        addSpace(logCard, 12);

        Button refresh =
            secondaryButton("기록 새로고침");

        refresh.setOnClickListener(
            v -> logs.setText(getLogText())
        );

        logCard.addView(refresh);

        addSpace(logCard, 8);

        Button copy =
            secondaryButton("기록 복사");

        copy.setOnClickListener(v -> {
            ClipboardManager clipboard =
                (ClipboardManager)
                    getSystemService(
                        Context.CLIPBOARD_SERVICE
                    );

            if (clipboard != null) {
                clipboard.setPrimaryClip(
                    ClipData.newPlainText(
                        "BLE Log",
                        getLogText()
                    )
                );

                toast("기록을 복사했습니다.");
            }
        });

        logCard.addView(copy);

        addSpace(logCard, 8);

        Button clear =
            secondaryButton("기록 삭제");

        clear.setOnClickListener(v -> {
            commandLogs.clear();
            logs.setText(getLogText());
        });

        logCard.addView(clear);

        addCard(content, logCard);
    }

    private String getLogText() {
        if (commandLogs.isEmpty()) {
            return "기록이 없습니다.";
        }

        StringBuilder sb =
            new StringBuilder();

        for (String log : commandLogs) {
            sb.append(log).append("\n");
        }

        return sb.toString();
    }

    private void addLog(String message) {
        String time =
            new java.text.SimpleDateFormat(
                "HH:mm:ss.SSS",
                Locale.getDefault()
            ).format(new Date());

        commandLogs.add(
            0,
            "[" + time + "] " + message
        );

        while (commandLogs.size() > 100) {
            commandLogs.remove(
                commandLogs.size() - 1
            );
        }
    }

    // ==============================
    // BLE PERMISSIONS
    // ==============================

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            &&
            checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED;
        }

        if (Build.VERSION.SDK_INT >= 23) {
            return checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED;
        }

        return true;
    }

    private void requestBluetoothPermissions() {
        if (hasBluetoothPermissions()) {
            return;
        }

        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(
                new String[] {
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                },
                REQUEST_BLE
            );
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(
                new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION
                },
                REQUEST_BLE
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode,
        String[] permissions,
        int[] grantResults
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        );

        if (requestCode == REQUEST_BLE) {
            if (hasBluetoothPermissions()) {
                toast(
                    "블루투스 권한이 허용되었습니다."
                );
            } else {
                toast(
                    "블루투스 권한이 필요합니다."
                );
            }
        }
    }

    // ==============================
    // BLE SCANNING
    // ==============================

    private final ScanCallback scanCallback =
        new ScanCallback() {
            @Override
            public void onScanResult(
                int callbackType,
                ScanResult result
            ) {
                BluetoothDevice device =
                    result.getDevice();

                if (device == null) return;

                String address;

                try {
                    address = device.getAddress();
                } catch (SecurityException e) {
                    return;
                }

                if (address == null) return;

                if (!foundDevices.containsKey(address)) {
                    foundDevices.put(
                        address,
                        device
                    );

                    handler.post(
                        () -> updateDeviceDialog()
                    );
                }
            }

            @Override
            public void onScanFailed(
                int errorCode
            ) {
                handler.post(() -> {
                    scanning = false;

                    addLog(
                        "SCAN ERROR " + errorCode
                    );

                    toast(
                        "BLE 검색 실패: " + errorCode
                    );
                });
            }
        };

    private AlertDialog deviceDialog;
    private LinearLayout deviceList;

    private void startScan() {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions();
            return;
        }

        if (
            adapter == null
            || !adapter.isEnabled()
        ) {
            toast(
                "블루투스를 켜주세요."
            );
            return;
        }

        stopScan();

        foundDevices.clear();

        scanner =
            adapter.getBluetoothLeScanner();

        if (scanner == null) {
            toast(
                "BLE 스캐너를 사용할 수 없습니다."
            );
            return;
        }

        deviceList = column();

        ScrollView scroll =
            new ScrollView(this);

        scroll.addView(deviceList);

        deviceDialog =
            new AlertDialog.Builder(this)
                .setTitle("BLE 장치 검색")
                .setView(scroll)
                .setNegativeButton(
                    "닫기",
                    (dialog, which) -> stopScan()
                )
                .create();

        deviceDialog.setOnDismissListener(
            dialog -> stopScan()
        );

        deviceDialog.show();

        try {
            scanner.startScan(scanCallback);
            scanning = true;

            addLog("SCAN START");

            updateDeviceDialog();

            handler.postDelayed(
                () -> stopScan(),
                12000
            );
        } catch (SecurityException e) {
            toast(
                "블루투스 권한 오류"
            );
        }
    }

    private void updateDeviceDialog() {
        if (
            deviceList == null
            || deviceDialog == null
            || !deviceDialog.isShowing()
        ) {
            return;
        }

        deviceList.removeAllViews();

        if (foundDevices.isEmpty()) {
            TextView waiting = text(
                "장치를 검색하고 있습니다...",
                14,
                false
            );

            waiting.setPadding(
                dp(18), dp(18),
                dp(18), dp(18)
            );

            deviceList.addView(waiting);
            return;
        }

        for (
            Map.Entry<String, BluetoothDevice> entry
            : foundDevices.entrySet()
        ) {
            BluetoothDevice device =
                entry.getValue();

            String address =
                entry.getKey();

            String name = address;

            try {
                String n = device.getName();

                if (
                    n != null
                    && !n.trim().isEmpty()
                ) {
                    name = n;
                }
            } catch (SecurityException ignored) {
            }

            final String finalName = name;

            Button item =
                secondaryButton(
                    name + "\n" + address
                );

            item.setOnClickListener(v -> {
                if (
                    deviceDialog != null
                    && deviceDialog.isShowing()
                ) {
                    deviceDialog.dismiss();
                }

                connectDevice(
                    device,
                    finalName
                );
            });

            deviceList.addView(item);
        }
    }

    private void stopScan() {
        if (!scanning) return;

        scanning = false;

        if (scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
        }

        addLog("SCAN STOP");
    }

    // ==============================
    // BLE CONNECTION
    // ==============================

    private void reconnectSavedDevice() {
        if (
            !autoReconnect
            || deviceAddress.isEmpty()
            || adapter == null
            || !adapter.isEnabled()
            || !hasBluetoothPermissions()
            || connected
            || connecting
        ) {
            return;
        }

        try {
            BluetoothDevice device =
                adapter.getRemoteDevice(
                    deviceAddress
                );

            connectDevice(
                device,
                deviceName
            );
        } catch (Exception e) {
            addLog(
                "RECONNECT ERROR "
                + e.getMessage()
            );
        }
    }

    private void connectDevice(
        BluetoothDevice device,
        String name
    ) {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions();
            return;
        }

        disconnectGatt();

        deviceAddress = device.getAddress();
        deviceName = name;

        saveSettings();

        connecting = true;
        connected = false;

        updateStatus();

        try {
            if (Build.VERSION.SDK_INT >= 23) {
                gatt = device.connectGatt(
                    this,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                );
            } else {
                gatt = device.connectGatt(
                    this,
                    false,
                    gattCallback
                );
            }

            addLog(
                "CONNECT " + deviceAddress
            );
        } catch (Exception e) {
            connecting = false;

            addLog(
                "CONNECT ERROR "
                + e.getMessage()
            );

            updateStatus();
        }
    }

    private final BluetoothGattCallback gattCallback =
        new BluetoothGattCallback() {

            @Override
            public void onConnectionStateChange(
                BluetoothGatt bluetoothGatt,
                int status,
                int newState
            ) {
                if (bluetoothGatt != gatt) {
                    try {
                        bluetoothGatt.close();
                    } catch (Exception ignored) {
                    }
                    return;
                }

                if (
                    status == BluetoothGatt.GATT_SUCCESS
                    &&
                    newState ==
                        BluetoothProfile.STATE_CONNECTED
                ) {
                    try {
                        bluetoothGatt.discoverServices();
                    } catch (Exception e) {
                        addLog(
                            "DISCOVERY ERROR"
                        );
                    }
                } else {
                    connected = false;
                    connecting = false;
                    writeChar = null;

                    modeRunning = false;

                    stopPhoneAnimation();
                    clearSendQueue();

                    handler.post(
                        () -> updateStatus()
                    );

                    addLog(
                        "DISCONNECTED " + status
                    );
                }
            }

            @Override
            public void onServicesDiscovered(
                BluetoothGatt bluetoothGatt,
                int status
            ) {
                if (
                    bluetoothGatt != gatt
                    || status !=
                        BluetoothGatt.GATT_SUCCESS
                ) {
                    return;
                }

                BluetoothGattService service =
                    bluetoothGatt.getService(
                        SERVICE_UUID
                    );

                if (service == null) {
                    addLog(
                        "SERVICE NOT FOUND"
                    );
                    return;
                }

                writeChar =
                    service.getCharacteristic(
                        WRITE_UUID
                    );

                if (writeChar == null) {
                    addLog(
                        "WRITE CHARACTERISTIC NOT FOUND"
                    );
                    return;
                }

                int properties =
                    writeChar.getProperties();

                if (
                    (properties &
                        BluetoothGattCharacteristic
                            .PROPERTY_WRITE_NO_RESPONSE)
                    != 0
                ) {
                    writeChar.setWriteType(
                        BluetoothGattCharacteristic
                            .WRITE_TYPE_NO_RESPONSE
                    );
                } else {
                    writeChar.setWriteType(
                        BluetoothGattCharacteristic
                            .WRITE_TYPE_DEFAULT
                    );
                }

                connected = true;
                connecting = false;

                addLog(
                    "BLE READY"
                );

                handler.post(() -> {
                    updateStatus();
                    toast(
                        "블루투스 연결 완료"
                    );
                });
            }

            @Override
            public void onCharacteristicWrite(
                BluetoothGatt bluetoothGatt,
                BluetoothGattCharacteristic characteristic,
                int status
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    addLog(
                        "WRITE CALLBACK ERROR " + status
                    );
                }
            }
        };

    private void disconnectGatt() {
        stopPhoneAnimation();

        connected = false;
        connecting = false;

        writeChar = null;

        clearSendQueue();

        BluetoothGatt old = gatt;
        gatt = null;

        if (old != null) {
            try {
                old.disconnect();
                old.close();
            } catch (Exception ignored) {
            }
        }

        updateStatus();
    }

    // ==============================
    // BLE COMMAND QUEUE
    // ==============================

    private void send(byte[] data) {
        if (
            !connected
            || gatt == null
            || writeChar == null
        ) {
            return;
        }

        byte[] copy =
            Arrays.copyOf(
                data,
                data.length
            );

        if (sendQueue.size() >= 15) {
            sendQueue.poll();
        }

        sendQueue.offer(copy);

        if (!sending) {
            sending = true;
            handler.post(sendTask);
        }
    }

    private final Runnable sendTask =
        new Runnable() {
            @Override
            public void run() {
                if (
                    !connected
                    || gatt == null
                    || writeChar == null
                ) {
                    sendQueue.clear();
                    sending = false;
                    return;
                }

                byte[] data =
                    sendQueue.poll();

                if (data == null) {
                    sending = false;
                    return;
                }

                try {
                    int result;

                    if (Build.VERSION.SDK_INT >= 33) {
                        result =
                            gatt.writeCharacteristic(
                                writeChar,
                                data,
                                writeChar.getWriteType()
                            );
                    } else {
                        writeChar.setValue(data);

                        boolean success =
                            gatt.writeCharacteristic(
                                writeChar
                            );

                        result = success
                            ? BluetoothStatusCodes.SUCCESS
                            : -1;
                    }

                    addLog(
                        (result ==
                            BluetoothStatusCodes.SUCCESS
                            ? "TX "
                            : "TX ERROR(" + result + ") ")
                        + bytesToHex(data)
                    );

                } catch (Exception e) {
                    addLog(
                        "TX EXCEPTION "
                        + e.getMessage()
                    );
                }

                handler.postDelayed(
                    this,
                    110
                );
            }
        };

    private void clearSendQueue() {
        sendQueue.clear();

        handler.removeCallbacks(sendTask);

        sending = false;
    }

    // ==============================
    // HEX HELPERS
    // ==============================

    private static byte[] hex(String input) {
        String clean =
            input.replaceAll(
                "\\s+", ""
            );

        if (
            clean.length() % 2 != 0
        ) {
            throw new IllegalArgumentException(
                "Invalid HEX length"
            );
        }

        byte[] result =
            new byte[clean.length() / 2];

        for (
            int i = 0;
            i < result.length;
            i++
        ) {
            int index = i * 2;

            result[i] =
                (byte) Integer.parseInt(
                    clean.substring(
                        index,
                        index + 2
                    ),
                    16
                );
        }

        return result;
    }

    private static String bytesToHex(
        byte[] bytes
    ) {
        StringBuilder sb =
            new StringBuilder();

        for (byte b : bytes) {
            if (sb.length() > 0) {
                sb.append(" ");
            }

            sb.append(
                String.format(
                    Locale.US,
                    "%02X",
                    b & 0xFF
                )
            );
        }

        return sb.toString();
    }

    // ==============================
    // SEEK LISTENER
    // ==============================

    private abstract static class SimpleSeekListener
        implements SeekBar.OnSeekBarChangeListener {

        @Override
        public void onProgressChanged(
            SeekBar seekBar,
            int progress,
            boolean fromUser
        ) {
        }

        @Override
        public void onStartTrackingTouch(
            SeekBar seekBar
        ) {
        }

        @Override
        public void onStopTrackingTouch(
            SeekBar seekBar
        ) {
        }
    }

    // ==============================
    // COLOR WHEEL
    // ==============================

    private class ColorWheel extends View {

        private Bitmap bitmap;
        private Paint paint =
            new Paint(Paint.ANTI_ALIAS_FLAG);

        private int lastSize = 0;

        private ColorSelectedListener listener;

        ColorWheel(Context context) {
            super(context);
        }

        void setOnColorSelectedListener(
            ColorSelectedListener l
        ) {
            listener = l;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            int size = Math.min(
                getWidth(),
                getHeight()
            );

            if (size <= 0) return;

            if (
                bitmap == null
                || lastSize != size
            ) {
                generateBitmap(size);
            }

            float left =
                (getWidth() - size) / 2f;

            float top =
                (getHeight() - size) / 2f;

            canvas.drawBitmap(
                bitmap,
                left,
                top,
                paint
            );
        }

        private void generateBitmap(
            int size
        ) {
            if (
                bitmap != null
                && !bitmap.isRecycled()
            ) {
                bitmap.recycle();
            }

            bitmap = Bitmap.createBitmap(
                size,
                size,
                Bitmap.Config.ARGB_8888
            );

            int[] pixels =
                new int[size * size];

            float center = size / 2f;
            float radius = center - 2;

            float[] hsv = {
                0f, 0f, 1f
            };

            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    float dx =
                        x - center;

                    float dy =
                        y - center;

                    float distance =
                        (float) Math.sqrt(
                            dx * dx + dy * dy
                        );

                    if (distance > radius) {
                        pixels[y * size + x] =
                            Color.TRANSPARENT;

                        continue;
                    }

                    float angle =
                        (float) Math.toDegrees(
                            Math.atan2(dy, dx)
                        );

                    if (angle < 0) {
                        angle += 360f;
                    }

                    hsv[0] = angle;
                    hsv[1] =
                        Math.min(
                            1f,
                            distance / radius
                        );

                    pixels[y * size + x] =
                        Color.HSVToColor(hsv);
                }
            }

            bitmap.setPixels(
                pixels,
                0,
                size,
                0,
                0,
                size,
                size
            );

            lastSize = size;
        }

        @Override
        public boolean onTouchEvent(
            android.view.MotionEvent event
        ) {
            if (
                event.getAction()
                    != MotionEvent.ACTION_DOWN
                &&
                event.getAction()
                    != MotionEvent.ACTION_MOVE
            ) {
                return true;
            }

            float size =
                Math.min(
                    getWidth(),
                    getHeight()
                );

            float left =
                (getWidth() - size) / 2f;

            float top =
                (getHeight() - size) / 2f;

            float dx =
                event.getX()
                - left
                - size / 2f;

            float dy =
                event.getY()
                - top
                - size / 2f;

            float radius =
                size / 2f - 2f;

            float distance =
                (float) Math.sqrt(
                    dx * dx + dy * dy
                );

            if (distance > radius) {
                return true;
            }

            float hue =
                (float) Math.toDegrees(
                    Math.atan2(dy, dx)
                );

            if (hue < 0) {
                hue += 360f;
            }

            float saturation =
                Math.min(
                    1f,
                    distance / radius
                );

            int color =
                Color.HSVToColor(
                    new float[] {
                        hue,
                        saturation,
                        1f
                    }
                );

            if (listener != null) {
                listener.onColorSelected(
                    color
                );
            }

            return true;
        }
    }

    private interface ColorSelectedListener {
        void onColorSelected(int color);
    }
}
