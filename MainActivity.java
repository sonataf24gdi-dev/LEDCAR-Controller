
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

    private static final UUID SERVICE = UUID.fromString(
        "0000ffe0-0000-1000-8000-00805f9b34fb"
    );

    private static final UUID WRITE = UUID.fromString(
        "0000ffe1-0000-1000-8000-00805f9b34fb"
    );

    private static final byte[] ON =
        hex("7BFF0407FFFFFFFFBF");

    private static final byte[] OFF =
        hex("7BFF0406FFFFFFFFBF");

    // 컨트롤러 자체 실행 모드
    private static final byte[] SEQUENTIAL =
        hex("7BFF0351FFFFFFFFBF");

    private static final byte[] RAINBOW =
        hex("7BFF0301FFFFFFFFBF");

    private final Handler handler =
        new Handler(Looper.getMainLooper());

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;

    private final LinkedHashMap<String, BluetoothDevice> candidates =
        new LinkedHashMap<>();

    private String savedAddress = "";
    private String deviceName = "";
    private String connectionText = "기기 미등록";

    private boolean active = false;
    private boolean scanning = false;
    private boolean connecting = false;
    private boolean connected = false;
    private boolean choosing = false;
    private boolean updating = false;
    private boolean powerOn = true;
    private boolean dark = false;
    private boolean settingsVisible = false;

    // 0 = 색상, 1 = 모드
    private int currentTab = 0;

    // RGB
    private int r = 255;
    private int g = 0;
    private int b = 0;

    // 공통 밝기
    private int brightness = 100;

    // 내장 모드
    // 0 = 순차 점등, 1 = 무지개 순환
    private int selectedMode = 0;
    private boolean modeRunning = false;
    private int modeSpeed = 50;

    private final int[] presets = new int[6];
    private final boolean[] presetSaved = new boolean[6];

    private LinearLayout mainRoot;
    private LinearLayout settingsPage;
    private LinearLayout contentContainer;
    private LinearLayout presetRow;

    private TextView mainStatus;
    private TextView settingsStatus;
    private TextView settingsDevice;
    private TextView colorLabel;
    private TextView brightnessLabel;
    private TextView speedLabel;
    private TextView modeStateLabel;

    private SeekBar redBar;
    private SeekBar greenBar;
    private SeekBar blueBar;
    private SeekBar brightnessBar;
    private SeekBar speedBar;

    private ColorWheel wheel;
    private IconView powerIcon;
    private Button modeButton;

    // BLE 전송 간격 제어
    private final ArrayDeque<byte[]> sendQueue =
        new ArrayDeque<>();

    private boolean sending = false;

    private final Runnable sendTask = new Runnable() {
        @Override
        public void run() {
            if (!connected || gatt == null || writeChar == null) {
                sendQueue.clear();
                sending = false;
                return;
            }

            byte[] packet = sendQueue.poll();

            if (packet == null) {
                sending = false;
                return;
            }

            try {
                int props = writeChar.getProperties();

                int type =
                    (props &
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
                    != 0
                    ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;

                if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeCharacteristic(
                        writeChar,
                        packet,
                        type
                    );
                } else {
                    writeChar.setWriteType(type);
                    writeChar.setValue(packet);
                    gatt.writeCharacteristic(writeChar);
                }

            } catch (Exception ignored) {
            }

            handler.postDelayed(this, 110);
        }
    };

    private final Runnable colorTask = () -> {
        if (connected && !modeRunning && powerOn) {
            send(rgbPacket(r, g, b));
        }
    };

    private final Runnable brightnessTask = () -> {
        if (connected) {
            send(brightnessPacket(brightness));
        }
    };

    private final Runnable speedTask = () -> {
        if (connected) {
            send(speedPacket(modeSpeed));
        }
    };

    private final Runnable retryTask = () -> {
        if (active && !connected && !connecting && !choosing) {
            scanSaved();
        }
    };

    private final Runnable timeoutTask = () -> {
        stopScan();

        if (choosing) {
            choosing = false;
            showCandidates();
        } else if (active && !connected && !connecting) {
            setStatus("차량 검색 대기 중");
            scheduleRetry();
        }
    };

    // BLE 검색
    private final ScanCallback scanCallback =
        new ScanCallback() {

        @Override
        public void onScanResult(
            int type,
            ScanResult result
        ) {
            BluetoothDevice device = result.getDevice();

            if (device == null) return;

            String address;

            try {
                address = device.getAddress();
            } catch (SecurityException e) {
                return;
            }

            if (address == null) return;

            String name = null;

            if (result.getScanRecord() != null) {
                name = result.getScanRecord().getDeviceName();
            }

            if (name == null) {
                try {
                    name = device.getName();
                } catch (SecurityException ignored) {}
            }

            final String foundName = name;

            handler.post(() -> {
                if (!active || connected || connecting) return;

                if (!choosing &&
                    !savedAddress.isEmpty() &&
                    savedAddress.equalsIgnoreCase(address)) {

                    connect(device, foundName);

                } else if (choosing) {
                    candidates.put(address, device);
                }
            });
        }

        @Override
        public void onScanFailed(int error) {
            handler.post(() -> {
                scanning = false;
                handler.removeCallbacks(timeoutTask);

                setStatus("검색 실패 (" + error + ")");

                if (choosing) {
                    choosing = false;
                    showCandidates();
                } else {
                    scheduleRetry();
                }
            });
        }
    };

    // GATT 연결
    private final BluetoothGattCallback gattCallback =
        new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(
            BluetoothGatt current,
            int code,
            int state
        ) {
            handler.post(() -> {
                if (current != gatt) {
                    try {
                        current.close();
                    } catch (Exception ignored) {}
                    return;
                }

                if (code == BluetoothGatt.GATT_SUCCESS &&
                    state == BluetoothProfile.STATE_CONNECTED) {

                    setStatus("서비스 확인 중...");

                    try {
                        if (!current.discoverServices()) {
                            lost(current, "서비스 검색 실패");
                        }
                    } catch (SecurityException e) {
                        lost(current, "블루투스 권한 오류");
                    }

                } else {
                    lost(current, "연결 끊김 · 재연결 대기");
                }
            });
        }

        @Override
        public void onServicesDiscovered(
            BluetoothGatt current,
            int code
        ) {
            handler.post(() -> {
                if (current != gatt) return;

                BluetoothGattService service =
                    code == BluetoothGatt.GATT_SUCCESS
                    ? current.getService(SERVICE)
                    : null;

                writeChar = service == null
                    ? null
                    : service.getCharacteristic(WRITE);

                if (writeChar == null ||
                    (writeChar.getProperties() &
                    (BluetoothGattCharacteristic.PROPERTY_WRITE |
                     BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE))
                    == 0) {

                    lost(
                        current,
                        "호환되지 않는 기기 (FFE0/FFE1 없음)"
                    );
                    return;
                }

                connected = true;
                connecting = false;

                savedAddress =
                    current.getDevice().getAddress();

                try {
                    String name = current.getDevice().getName();

                    if (name != null && !name.isEmpty()) {
                        deviceName = name;
                    }
                } catch (SecurityException ignored) {}

                getPreferences(MODE_PRIVATE)
                    .edit()
                    .putString("last_device", savedAddress)
                    .putString("last_name", deviceName)
                    .apply();

                setStatus("연결 완료");

                // 재연결 시 기존 컨트롤러 모드를
                // 덮어쓰지 않음.
            });
        }
    };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        SharedPreferences prefs =
            getPreferences(MODE_PRIVATE);

        savedAddress =
            prefs.getString("last_device", "");

        deviceName =
            prefs.getString("last_name", "");

        dark =
            prefs.getBoolean("dark_theme", false);

        brightness =
            prefs.getInt("brightness", 100);

        modeSpeed =
            prefs.getInt("mode_speed", 50);

        selectedMode =
            prefs.getInt("selected_mode", 0);

        modeRunning =
            prefs.getBoolean("mode_running", false);

        r = prefs.getInt("color_r", 255);
        g = prefs.getInt("color_g", 0);
        b = prefs.getInt("color_b", 0);

        for (int i = 0; i < 6; i++) {
            presetSaved[i] =
                prefs.contains("preset_" + i);

            presets[i] =
                prefs.getInt("preset_" + i, Color.WHITE);
        }

        BluetoothManager manager =
            (BluetoothManager)
            getSystemService(BLUETOOTH_SERVICE);

        adapter =
            manager == null ? null : manager.getAdapter();

        buildMainPage();
        showMainPage();

        if (hasPermissions()) {
            startAuto();
        } else {
            requestNeededPermissions();
        }
    }

    // 색상 테마
    private int fg() {
        return dark
            ? Color.WHITE
            : Color.rgb(40, 43, 49);
    }

    private int muted() {
        return dark
            ? Color.rgb(174, 179, 189)
            : Color.rgb(112, 117, 125);
    }

    private int bg() {
        return dark
            ? Color.rgb(17, 19, 24)
            : Color.rgb(250, 250, 251);
    }

    private int surface() {
        return dark
            ? Color.rgb(37, 40, 48)
            : Color.rgb(233, 235, 239);
    }

    private int accent() {
        return Color.rgb(0, 165, 139);
    }

    private int dp(int value) {
        return (int)(
            value *
            getResources().getDisplayMetrics().density
            + 0.5f
        );
    }

    private TextView text(
        String value,
        int size,
        int color
    ) {
        TextView view = new TextView(this);

        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);

        return view;
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);

        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(
            dp(18), dp(18), dp(18), dp(18)
        );
        view.setBackgroundColor(bg());

        return view;
    }

    private void section(
        LinearLayout parent,
        String title
    ) {
        TextView view = text(title, 19, fg());

        view.setPadding(
            0, dp(22), 0, dp(10)
        );

        parent.addView(view);
    }

    private Button button(
        LinearLayout parent,
        String label,
        Runnable action
    ) {
        Button view = new Button(this);

        view.setText(label);
        view.setTextColor(fg());
        view.setAllCaps(false);

        view.setBackgroundTintList(
            ColorStateList.valueOf(surface())
        );

        LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(
                -1, dp(52)
            );

        params.bottomMargin = dp(9);

        parent.addView(view, params);

        view.setOnClickListener(v -> action.run());

        return view;
    }

    // 메인 페이지
    private void buildMainPage() {
        mainRoot = column();

        LinearLayout header =
            new LinearLayout(this);

        header.setGravity(Gravity.CENTER_VERTICAL);
        mainRoot.addView(header);

        TextView title =
            text("엠비언트 설정", 25, fg());

        header.addView(
            title,
            new LinearLayout.LayoutParams(
                0, dp(56), 1
            )
        );

        IconView settingsIcon =
            new IconView(this, false);

        settingsIcon.setContentDescription(
            "차량 연결 설정"
        );

        header.addView(
            settingsIcon,
            new LinearLayout.LayoutParams(
                dp(48), dp(52)
            )
        );

        settingsIcon.setOnClickListener(
            v -> showSettingsPage()
        );

        powerIcon = new IconView(this, true);

        header.addView(
            powerIcon,
            new LinearLayout.LayoutParams(
                dp(48), dp(52)
            )
        );

        powerIcon.setContentDescription(
            powerOn ? "전원 끄기" : "전원 켜기"
        );

        powerIcon.setOnClickListener(v -> {
            if (!connected) {
                Toast.makeText(
                    this,
                    "차량 연결을 확인하세요",
                    Toast.LENGTH_SHORT
                ).show();
                return;
            }

            powerOn = !powerOn;

            if (!powerOn) {
                stopModeLocal();
            }

            powerIcon.invalidate();

            powerIcon.setContentDescription(
                powerOn ? "전원 끄기" : "전원 켜기"
            );

            send(powerOn ? ON : OFF);
        });

        mainStatus =
            text("연결 상태 확인 중", 14, muted());

        mainStatus.setPadding(
            0, dp(9), 0, dp(12)
        );

        mainRoot.addView(mainStatus);

        // 페이지 탭
        LinearLayout tabs =
            new LinearLayout(this);

        mainRoot.addView(tabs);

        Button colorTab =
            new Button(this);

        colorTab.setText("색상");
        colorTab.setAllCaps(false);

        Button modeTab =
            new Button(this);

        modeTab.setText("모드");
        modeTab.setAllCaps(false);

        tabs.addView(
            colorTab,
            new LinearLayout.LayoutParams(
                0, dp(52), 1
            )
        );

        tabs.addView(
            modeTab,
            new LinearLayout.LayoutParams(
                0, dp(52), 1
            )
        );

        colorTab.setOnClickListener(
            v -> {
                currentTab = 0;
                buildMainPage();
                showMainPage();
            }
        );

        modeTab.setOnClickListener(
            v -> {
                currentTab = 1;
                buildMainPage();
                showMainPage();
            }
        );

        colorTab.setTextColor(
            currentTab == 0 ? Color.WHITE : fg()
        );

        modeTab.setTextColor(
            currentTab == 1 ? Color.WHITE : fg()
        );

        colorTab.setBackgroundTintList(
            ColorStateList.valueOf(
                currentTab == 0 ? accent() : surface()
            )
        );

        modeTab.setBackgroundTintList(
            ColorStateList.valueOf(
                currentTab == 1 ? accent() : surface()
            )
        );

        contentContainer =
            new LinearLayout(this);

        contentContainer.setOrientation(
            LinearLayout.VERTICAL
        );

        mainRoot.addView(contentContainer);

        if (currentTab == 0) {
            buildColorPage();
        } else {
            buildModePage();
        }

        // 양쪽 페이지 공통 밝기 슬라이더
        buildBrightnessControl(contentContainer);

        updateStatusViews();
    }

    // 색상 페이지
    private void buildColorPage() {
        section(contentContainer, "색상표");

        wheel = new ColorWheel(this);

        contentContainer.addView(
            wheel,
            new LinearLayout.LayoutParams(
                -1, dp(290)
            )
        );

        wheel.setOnColorChange(
            (nr, ng, nb) ->
                setColor(nr, ng, nb, true, true)
        );

        colorLabel =
            text("", 17, fg());

        colorLabel.setPadding(
            0, dp(10), 0, 0
        );

        contentContainer.addView(colorLabel);

        section(contentContainer, "색상 프리셋");

        TextView hint = text(
            "짧게 누르면 적용 · 길게 누르면 현재 색상 저장",
            12,
            muted()
        );

        contentContainer.addView(hint);

        presetRow =
            new LinearLayout(this);

        presetRow.setPadding(
            0, dp(9), 0, 0
        );

        contentContainer.addView(presetRow);

        refreshPresets();

        section(contentContainer, "RGB");

        redBar = rgbSeek(
            contentContainer, "R", r
        );

        greenBar = rgbSeek(
            contentContainer, "G", g
        );

        blueBar = rgbSeek(
            contentContainer, "B", b
        );

        SeekBar.OnSeekBarChangeListener listener =
            new SeekBar.OnSeekBarChangeListener() {

            @Override
            public void onProgressChanged(
                SeekBar bar,
                int progress,
                boolean fromUser
            ) {
                if (fromUser && !updating) {
                    setColor(
                        redBar.getProgress(),
                        greenBar.getProgress(),
                        blueBar.getProgress(),
                        false,
                        true
                    );
                }
            }

            @Override
            public void onStartTrackingTouch(
                SeekBar bar
            ) {}

            @Override
            public void onStopTrackingTouch(
                SeekBar bar
            ) {
                queueColor();
            }
        };

        redBar.setOnSeekBarChangeListener(listener);
        greenBar.setOnSeekBarChangeListener(listener);
        blueBar.setOnSeekBarChangeListener(listener);

        updateColorLabel();

        wheel.setSelectedColor(
            Color.rgb(r, g, b)
        );
    }

    // 모드 페이지
    private void buildModePage() {
        section(contentContainer, "조명 효과");

        TextView description = text(
            "컨트롤러가 직접 실행하는 조명 효과",
            14,
            muted()
        );

        contentContainer.addView(description);

        RadioGroup modeGroup =
            new RadioGroup(this);

        modeGroup.setOrientation(
            RadioGroup.VERTICAL
        );

        modeGroup.setPadding(
            0, dp(14), 0, dp(8)
        );

        contentContainer.addView(modeGroup);

        RadioButton sequential =
            new RadioButton(this);

        sequential.setText("순차 점등");
        sequential.setTextColor(fg());
        sequential.setId(2001);

        RadioButton rainbow =
            new RadioButton(this);

        rainbow.setText("무지개 순환");
        rainbow.setTextColor(fg());
        rainbow.setId(2002);

        modeGroup.addView(sequential);
        modeGroup.addView(rainbow);

        if (selectedMode == 0) {
            sequential.setChecked(true);
        } else {
            rainbow.setChecked(true);
        }

        modeGroup.setOnCheckedChangeListener(
            (group, id) -> {
                int nextMode =
                    id == 2001 ? 0 : 1;

                if (selectedMode == nextMode) return;

                selectedMode = nextMode;

                getPreferences(MODE_PRIVATE)
                    .edit()
                    .putInt(
                        "selected_mode",
                        selectedMode
                    )
                    .apply();

                // 실행 중 다른 모드를 선택하면
                // 새 모드 명령 전송
                if (modeRunning && connected) {
                    sendModeCommand();
                }

                updateModeUI();
            }
        );

        section(contentContainer, "전환 속도");

        speedLabel =
            text(modeSpeed + "%", 15, muted());

        contentContainer.addView(speedLabel);

        speedBar =
            new SeekBar(this);

        speedBar.setMax(99);

        speedBar.setProgress(
            Math.max(1, Math.min(100, modeSpeed)) - 1
        );

        contentContainer.addView(speedBar);

        LinearLayout speedHints =
            new LinearLayout(this);

        speedHints.setGravity(
            Gravity.CENTER_VERTICAL
        );

        TextView slow =
            text("느리게", 13, muted());

        TextView fast =
            text("빠르게", 13, muted());

        speedHints.addView(
            slow,
            new LinearLayout.LayoutParams(
                0, -2, 1
            )
        );

        fast.setGravity(Gravity.RIGHT);

        speedHints.addView(
            fast,
            new LinearLayout.LayoutParams(
                0, -2, 1
            )
        );

        contentContainer.addView(speedHints);

        speedBar.setOnSeekBarChangeListener(
            new SeekBar.OnSeekBarChangeListener() {

            @Override
            public void onProgressChanged(
                SeekBar bar,
                int progress,
                boolean fromUser
            ) {
                if (!fromUser) return;

                modeSpeed = progress + 1;

                speedLabel.setText(
                    modeSpeed + "%"
                );

                getPreferences(MODE_PRIVATE)
                    .edit()
                    .putInt(
                        "mode_speed", modeSpeed
                    )
                    .apply();

                // 슬라이더 이동 중 전송량 제한
                handler.removeCallbacks(speedTask);

                handler.postDelayed(
                    speedTask,
                    140
                );
            }

            @Override
            public void onStartTrackingTouch(
                SeekBar bar
            ) {}

            @Override
            public void onStopTrackingTouch(
                SeekBar bar
            ) {
                handler.removeCallbacks(speedTask);
                handler.post(speedTask);
            }
        });

        modeStateLabel = text(
            modeRunning
                ? "조명 효과 실행 중"
                : "조명 효과 중지됨",
            14,
            modeRunning ? accent() : muted()
        );

        modeStateLabel.setPadding(
            0, dp(22), 0, dp(10)
        );

        contentContainer.addView(modeStateLabel);

        modeButton = button(
            contentContainer,
            modeRunning
                ? "조명 효과 중지"
                : "조명 효과 시작",
            () -> {
                if (modeRunning) {
                    stopMode();
                } else {
                    startMode();
                }
            }
        );

        TextView note = text(
            "효과 시작 명령을 컨트롤러에 전송합니다. "
            + "앱 종료 또는 블루투스 연결 해제 시 "
            + "효과 종료 명령은 전송하지 않습니다.",
            12,
            muted()
        );

        note.setPadding(
            0, dp(12), 0, dp(8)
        );

        contentContainer.addView(note);
    }

    // 두 페이지 공통 밝기 조절
    private void buildBrightnessControl(
        LinearLayout parent
    ) {
        section(parent, "밝기 조절");

        brightnessLabel = text(
            brightness + "%",
            15,
            muted()
        );

        parent.addView(brightnessLabel);

        brightnessBar =
            new SeekBar(this);

        brightnessBar.setMax(99);

        brightnessBar.setProgress(
            Math.max(1, Math.min(100, brightness)) - 1
        );

        parent.addView(brightnessBar);

        brightnessBar.setOnSeekBarChangeListener(
            new SeekBar.OnSeekBarChangeListener() {

            @Override
            public void onProgressChanged(
                SeekBar bar,
                int progress,
                boolean fromUser
            ) {
                if (!fromUser) return;

                brightness = progress + 1;

                brightnessLabel.setText(
                    brightness + "%"
                );

                getPreferences(MODE_PRIVATE)
                    .edit()
                    .putInt(
                        "brightness", brightness
                    )
                    .apply();

                handler.removeCallbacks(
                    brightnessTask
                );

                handler.postDelayed(
                    brightnessTask, 120
                );
            }

            @Override
            public void onStartTrackingTouch(
                SeekBar bar
            ) {}

            @Override
            public void onStopTrackingTouch(
                SeekBar bar
            ) {
                handler.removeCallbacks(
                    brightnessTask
                );

                handler.post(brightnessTask);
            }
        });
    }

    // 컨트롤러 내장 모드 시작
    private void startMode() {
        if (!connected) {
            Toast.makeText(
                this,
                "차량을 먼저 연결하세요",
                Toast.LENGTH_SHORT
            ).show();
            return;
        }

        if (!powerOn) {
            Toast.makeText(
                this,
                "조명 전원을 먼저 켜세요",
                Toast.LENGTH_SHORT
            ).show();
            return;
        }

        modeRunning = true;

        saveModeRunning();

        handler.removeCallbacks(colorTask);

        sendModeCommand();

        updateModeUI();
    }

    // 내장 모드 명령 전송
    private void sendModeCommand() {
        if (!connected) return;

        send(
            selectedMode == 0
                ? SEQUENTIAL
                : RAINBOW
        );

        handler.removeCallbacks(speedTask);

        handler.postDelayed(
            speedTask,
            240
        );
    }

    // 사용자가 명시적으로 효과 종료
    private void stopMode() {
        if (!modeRunning) return;

        stopModeLocal();

        // 고정 RGB 명령으로 내장 효과 종료
        if (connected) {
            send(rgbPacket(r, g, b));
        }
    }

    // 내부 상태만 변경
    // 블루투스 명령을 보내지 않음
    private void stopModeLocal() {
        modeRunning = false;

        saveModeRunning();

        handler.removeCallbacks(speedTask);

        updateModeUI();
    }

    private void saveModeRunning() {
        getPreferences(MODE_PRIVATE)
            .edit()
            .putBoolean(
                "mode_running",
                modeRunning
            )
            .apply();
    }

    private void updateModeUI() {
        if (modeButton != null) {
            modeButton.setText(
                modeRunning
                    ? "조명 효과 중지"
                    : "조명 효과 시작"
            );
        }

        if (modeStateLabel != null) {
            String name =
                selectedMode == 0
                    ? "순차 점등"
                    : "무지개 순환";

            modeStateLabel.setText(
                modeRunning
                    ? name + " 실행 중"
                    : name + " 중지됨"
            );

            modeStateLabel.setTextColor(
                modeRunning ? accent() : muted()
            );
        }
    }

    // RGB 슬라이더
    private SeekBar rgbSeek(
        LinearLayout parent,
        String name,
        int value
    ) {
        TextView label =
            text(name, 13, muted());

        parent.addView(label);

        SeekBar bar = new SeekBar(this);

        bar.setMax(255);
        bar.setProgress(value);

        parent.addView(bar);

        return bar;
    }

    // 수동 색상 변경
    private void setColor(
        int nr,
        int ng,
        int nb,
        boolean fromWheel,
        boolean manual
    ) {
        if (manual && modeRunning) {
            stopModeLocal();
        }

        r = nr;
        g = ng;
        b = nb;

        if (fromWheel && redBar != null) {
            updating = true;

            redBar.setProgress(r);
            greenBar.setProgress(g);
            blueBar.setProgress(b);

            updating = false;
        }

        if (!fromWheel && wheel != null) {
            wheel.setSelectedColor(
                Color.rgb(r, g, b)
            );
        }

        updateColorLabel();

        if (manual) {
            getPreferences(MODE_PRIVATE)
                .edit()
                .putInt("color_r", r)
                .putInt("color_g", g)
                .putInt("color_b", b)
                .apply();
        }

        queueColor();
    }

    private void updateColorLabel() {
        if (colorLabel == null) return;

        colorLabel.setText(
            String.format(
                Locale.US,
                "#%02X%02X%02X  ·  R %d  G %d  B %d",
                r, g, b, r, g, b
            )
        );

        colorLabel.setTextColor(
            Color.rgb(r, g, b)
        );
    }

    private void queueColor() {
        handler.removeCallbacks(colorTask);

        if (!modeRunning) {
            handler.postDelayed(colorTask, 100);
        }
    }

    // 프리셋
    private void refreshPresets() {
        if (presetRow == null) return;

        presetRow.removeAllViews();

        for (int i = 0; i < 6; i++) {
            final int index = i;

            TextView swatch = text(
                presetSaved[i] ? "●" : "+",
                28,
                presetSaved[i]
                    ? presets[i]
                    : muted()
            );

            swatch.setGravity(Gravity.CENTER);

            LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                    0, dp(55), 1
                );

            params.setMargins(
                dp(2), 0, dp(2), 0
            );

            presetRow.addView(swatch, params);

            swatch.setOnClickListener(v -> {
                if (!presetSaved[index]) {
                    Toast.makeText(
                        this,
                        "길게 눌러 색상을 저장하세요",
                        Toast.LENGTH_SHORT
                    ).show();
                    return;
                }

                int color = presets[index];

                setColor(
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color),
                    false,
                    true
                );
            });

            swatch.setOnLongClickListener(v -> {
                int color =
                    Color.rgb(r, g, b);

                new AlertDialog.Builder(this)
                    .setTitle(
                        "프리셋 " + (index + 1) + " 저장"
                    )
                    .setMessage(
                        String.format(
                            Locale.US,
                            "현재 색상 #%06X을 저장할까요?",
                            color & 0xffffff
                        )
                    )
                    .setPositiveButton(
                        "저장",
                        (dialog, which) -> {
                            presets[index] = color;
                            presetSaved[index] = true;

                            getPreferences(MODE_PRIVATE)
                                .edit()
                                .putInt(
                                    "preset_" + index,
                                    color
                                )
                                .apply();

                            refreshPresets();
                        }
                    )
                    .setNegativeButton(
                        "취소", null
                    )
                    .show();

                return true;
            });
        }
    }

    // 화면 표시
    private void showMainPage() {
        settingsVisible = false;

        if (mainRoot.getParent() != null) {
            ((ViewGroup)mainRoot.getParent())
                .removeView(mainRoot);
        }

        ScrollView scroll =
            new ScrollView(this);

        scroll.setFillViewport(true);
        scroll.addView(mainRoot);

        setContentView(scroll);

        updateStatusViews();
    }

    private void showSettingsPage() {
        settingsVisible = true;

        buildSettingsPage();

        ScrollView scroll =
            new ScrollView(this);

        scroll.setFillViewport(true);
        scroll.addView(settingsPage);

        setContentView(scroll);

        updateStatusViews();
    }

    // 설정 페이지
    private void buildSettingsPage() {
        settingsPage = column();

        LinearLayout header =
            new LinearLayout(this);

        header.setGravity(Gravity.CENTER_VERTICAL);

        settingsPage.addView(header);

        IconView back =
            new IconView(this, false, true);

        header.addView(
            back,
            new LinearLayout.LayoutParams(
                dp(42), dp(50)
            )
        );

        back.setContentDescription(
            "메인 화면으로 돌아가기"
        );

        back.setOnClickListener(
            v -> showMainPage()
        );

        TextView title =
            text("차량 연결 설정", 24, fg());

        header.addView(title);

        section(
            settingsPage,
            "블루투스 연결 상태"
        );

        settingsStatus =
            text("", 19, accent());

        settingsStatus.setPadding(
            0, dp(6), 0, dp(8)
        );

        settingsPage.addView(settingsStatus);

        settingsDevice =
            text("", 14, muted());

        settingsDevice.setPadding(
            0, 0, 0, dp(14)
        );

        settingsPage.addView(settingsDevice);

        button(
            settingsPage,
            "차량 연결",
            () -> {
                if (connected) {
                    Toast.makeText(
                        this,
                        "이미 연결되어 있습니다",
                        Toast.LENGTH_SHORT
                    ).show();
                    return;
                }

                if (savedAddress.isEmpty()) {
                    startDevicePicker();
                } else {
                    active = true;
                    scanSaved();
                }
            }
        );

        button(
            settingsPage,
            "다른 차량 선택",
            this::startDevicePicker
        );

        section(settingsPage, "화면 테마");

        RadioGroup themes =
            new RadioGroup(this);

        themes.setOrientation(
            RadioGroup.VERTICAL
        );

        settingsPage.addView(themes);

        RadioButton light =
            new RadioButton(this);

        light.setText("라이트 모드");
        light.setTextColor(fg());
        light.setId(1001);

        RadioButton darkButton =
            new RadioButton(this);

        darkButton.setText("다크 모드");
        darkButton.setTextColor(fg());
        darkButton.setId(1002);

        themes.addView(light);
        themes.addView(darkButton);

        if (dark) {
            darkButton.setChecked(true);
        } else {
            light.setChecked(true);
        }

        themes.setOnCheckedChangeListener(
            (group, id) -> {
                boolean next = id == 1002;

                if (next == dark) return;

                dark = next;

                getPreferences(MODE_PRIVATE)
                    .edit()
                    .putBoolean(
                        "dark_theme", dark
                    )
                    .apply();

                buildMainPage();
                showSettingsPage();
            }
        );

        TextView note = text(
            "선택한 테마, 조명 색상, 밝기와 "
            + "모드 속도는 앱을 종료해도 저장됩니다. "
            + "이전에 등록한 차량은 앱 실행 시 "
            + "자동 연결합니다.",
            13,
            muted()
        );

        note.setPadding(
            0, dp(14), 0, 0
        );

        settingsPage.addView(note);

        updateStatusViews();
    }

    private void setStatus(String value) {
        connectionText = value;
        updateStatusViews();
    }

    private void updateStatusViews() {
        if (mainStatus != null) {
            mainStatus.setText(
                connectionText +
                (connected && !savedAddress.isEmpty()
                    ? " · " + savedAddress
                    : "")
            );
        }

        if (settingsStatus != null) {
            settingsStatus.setText(connectionText);

            settingsStatus.setTextColor(
                connected ? accent() : muted()
            );
        }

        if (settingsDevice != null) {
            settingsDevice.setText(
                "기기: " +
                (deviceName.isEmpty()
                    ? "이름 확인 안 됨"
                    : deviceName)
                + "\n주소: " +
                (savedAddress.isEmpty()
                    ? "등록되지 않음"
                    : savedAddress)
            );
        }
    }

    // 블루투스 권한
    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return
                checkSelfPermission(
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
                &&
                checkSelfPermission(
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED;
        }

        return checkSelfPermission(
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(
                new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                },
                1
            );
        } else {
            requestPermissions(
                new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
                },
                1
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(
        int code,
        String[] permissions,
        int[] results
    ) {
        super.onRequestPermissionsResult(
            code, permissions, results
        );

        if (hasPermissions()) {
            startAuto();
        } else {
            setStatus(
                "근처 기기 권한을 허용하세요"
            );
        }
    }

    // 자동 연결
    private void startAuto() {
        active = true;

        if (savedAddress.isEmpty()) {
            setStatus(
                "기기 미등록 · 설정에서 차량을 선택하세요"
            );
        } else {
            scanSaved();
        }
    }

    private void scanSaved() {
        if (!active ||
            choosing ||
            scanning ||
            connected ||
            connecting ||
            !hasPermissions()) return;

        if (savedAddress.isEmpty()) {
            setStatus("기기 미등록");
            return;
        }

        if (adapter == null ||
            !adapter.isEnabled()) {

            setStatus("블루투스를 켜 주세요");
            scheduleRetry();
            return;
        }

        try {
            scanner =
                adapter.getBluetoothLeScanner();

            if (scanner == null) {
                scheduleRetry();
                return;
            }

            scanner.startScan(scanCallback);
            scanning = true;

            setStatus("이전 차량 검색 중...");

            handler.removeCallbacks(timeoutTask);

            handler.postDelayed(
                timeoutTask, 10000
            );

        } catch (SecurityException e) {
            setStatus("블루투스 권한 오류");
        }
    }

    // 다른 차량 선택
    private void startDevicePicker() {
        if (!hasPermissions()) {
            requestNeededPermissions();
            return;
        }

        if (adapter == null ||
            !adapter.isEnabled()) {

            setStatus("블루투스를 켜 주세요");
            return;
        }

        // 기존 조명 효과에는 영향을 주지 않음.
        disconnect();

        active = true;
        choosing = true;

        candidates.clear();

        try {
            scanner =
                adapter.getBluetoothLeScanner();

            if (scanner == null) {
                choosing = false;
                setStatus("검색 불가");
                return;
            }

            scanner.startScan(scanCallback);
            scanning = true;

            setStatus("주변 BLE 기기 검색 중...");

            handler.removeCallbacks(timeoutTask);

            handler.postDelayed(
                timeoutTask, 8000
            );

        } catch (SecurityException e) {
            choosing = false;
            setStatus("권한 오류");
        }
    }

    private void showCandidates() {
        if (isFinishing() || isDestroyed()) return;

        if (candidates.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("기기를 찾지 못했어요")
                .setMessage(
                    "차량 LED 전원을 확인하고 다시 검색하세요."
                )
                .setPositiveButton(
                    "다시 검색",
                    (d, w) -> startDevicePicker()
                )
                .setNegativeButton(
                    "닫기", null
                )
                .show();

            return;
        }

        ArrayList<BluetoothDevice> devices =
            new ArrayList<>(
                candidates.values()
            );

        String[] names =
            new String[devices.size()];

        for (int i = 0; i < devices.size(); i++) {
            BluetoothDevice device = devices.get(i);

            String name = null;

            try {
                name = device.getName();
            } catch (SecurityException ignored) {}

            names[i] =
                (name == null || name.isEmpty()
                    ? "이름 없는 BLE 기기"
                    : name)
                + " · " + device.getAddress();
        }

        new AlertDialog.Builder(this)
            .setTitle("차량 기기 선택")
            .setItems(
                names,
                (dialog, index) -> {
                    BluetoothDevice device =
                        devices.get(index);

                    String name = names[index];

                    int separator =
                        name.lastIndexOf(" · ");

                    if (separator >= 0) {
                        name = name.substring(
                            0, separator
                        );
                    }

                    connect(device, name);
                }
            )
            .setNegativeButton(
                "취소", null
            )
            .setNeutralButton(
                "다시 검색",
                (d, w) -> startDevicePicker()
            )
            .show();
    }

    private void connect(
        BluetoothDevice device,
        String name
    ) {
        if (connected || connecting) return;

        stopScan();

        choosing = false;

        disconnect();

        connecting = true;

        if (name != null) {
            deviceName = name;
        }

        setStatus("연결 중...");

        try {
            gatt = device.connectGatt(
                this,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            );

            if (gatt == null) {
                connecting = false;
                setStatus("연결 시작 실패");
                scheduleRetry();
            }

        } catch (SecurityException e) {
            connecting = false;
            setStatus("연결 권한 오류");
            scheduleRetry();
        }
    }

    private void lost(
        BluetoothGatt current,
        String reason
    ) {
        if (current != gatt) return;

        // 연결이 끊겨도 내장 모드를
        // 종료하는 명령은 보내지 않음.

        gatt = null;
        writeChar = null;

        connected = false;
        connecting = false;

        sendQueue.clear();
        sending = false;

        handler.removeCallbacks(sendTask);

        try {
            current.close();
        } catch (Exception ignored) {}

        setStatus(reason);

        scheduleRetry();
    }

    private void stopScan() {
        handler.removeCallbacks(timeoutTask);

        if (scanning && scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {}
        }

        scanning = false;
    }

    private void disconnect() {
        stopScan();

        handler.removeCallbacks(retryTask);
        handler.removeCallbacks(colorTask);
        handler.removeCallbacks(brightnessTask);
        handler.removeCallbacks(speedTask);
        handler.removeCallbacks(sendTask);

        sendQueue.clear();
        sending = false;

        BluetoothGatt old = gatt;

        gatt = null;
        writeChar = null;

        connected = false;
        connecting = false;

        if (old != null) {
            try {
                old.disconnect();
                old.close();
            } catch (Exception ignored) {}
        }
    }

    private void scheduleRetry() {
        handler.removeCallbacks(retryTask);

        if (active &&
            !choosing &&
            !savedAddress.isEmpty() &&
            !connected &&
            !connecting) {

            handler.postDelayed(
                retryTask, 4000
            );
        }
    }

    // BLE 명령 전송
    private void send(byte[] bytes) {
        if (!connected ||
            gatt == null ||
            writeChar == null) return;

        // 지나친 명령 누적 방지
        if (sendQueue.size() >= 15) {
            sendQueue.poll();
        }

        sendQueue.offer(bytes);

        if (!sending) {
            sending = true;
            handler.post(sendTask);
        }
    }

    // 확인된 RGB 패킷
    private static byte[] rgbPacket(
        int red,
        int green,
        int blue
    ) {
        return new byte[]{
            0x7B,
            0x01,
            0x07,
            (byte)red,
            (byte)green,
            (byte)blue,
            0x00,
            (byte)0xFF,
            (byte)0xBF
        };
    }

    // 내장 모드 속도 패킷
    private static byte[] speedPacket(
        int percent
    ) {
        int value =
            Math.max(1, Math.min(100, percent));

        return new byte[]{
            0x7B,
            (byte)0xFF,
            0x02,
            (byte)value,
            (byte)0xFF,
            0x00,
            (byte)0xFF,
            (byte)0xFF,
            (byte)0xBF
        };
    }

    // 기존 밝기 명령 형식 유지
    private static byte[] brightnessPacket(
        int percent
    ) {
        int value =
            Math.max(1, Math.min(100, percent));

        return new byte[]{
            0x7B,
            (byte)0xFF,
            0x01,
            (byte)(value == 1 ? 0x00 : 0x20),
            (byte)value,
            0x02,
            (byte)0xFF,
            (byte)0xFF,
            (byte)0xBF
        };
    }

    private static byte[] hex(String value) {
        byte[] bytes =
            new byte[value.length() / 2];

        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte)
                Integer.parseInt(
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
    public void onBackPressed() {
        if (settingsVisible) {
            showMainPage();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        active = false;

        // 중요:
        // 앱 종료 시 stopMode() 호출 금지
        // RGB 또는 OFF 명령 전송 금지
        // 컨트롤러 자체 실행 상태 유지

        disconnect();

        handler.removeCallbacksAndMessages(null);

        super.onDestroy();
    }

    // 전원 / 설정 / 뒤로 가기 아이콘
    private class IconView extends View {
        private final Paint paint =
            new Paint(Paint.ANTI_ALIAS_FLAG);

        private final boolean isPower;
        private final boolean isBack;

        IconView(
            Context context,
            boolean power
        ) {
            this(context, power, false);
        }

        IconView(
            Context context,
            boolean power,
            boolean back
        ) {
            super(context);

            isPower = power;
            isBack = back;

            setClickable(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;

            float radius =
                Math.min(
                    getWidth(),
                    getHeight()
                ) * 0.22f;

            paint.setColor(
                isPower
                    ? (powerOn ? accent() : muted())
                    : fg()
            );

            paint.setStyle(Paint.Style.STROKE);

            paint.setStrokeWidth(
                getResources()
                    .getDisplayMetrics()
                    .density * 2.4f
            );

            paint.setStrokeCap(
                Paint.Cap.ROUND
            );

            paint.setStrokeJoin(
                Paint.Join.ROUND
            );

            if (isBack) {
                canvas.drawLine(
                    cx + radius, cy,
                    cx - radius, cy,
                    paint
                );

                canvas.drawLine(
                    cx - radius, cy,
                    cx, cy - radius,
                    paint
                );

                canvas.drawLine(
                    cx - radius, cy,
                    cx, cy + radius,
                    paint
                );

            } else if (isPower) {
                canvas.drawArc(
                    new RectF(
                        cx - radius,
                        cy - radius,
                        cx + radius,
                        cy + radius
                    ),
                    -48,
                    276,
                    false,
                    paint
                );

                canvas.drawLine(
                    cx,
                    cy - radius * 1.3f,
                    cx,
                    cy + radius * 0.08f,
                    paint
                );

            } else {
                canvas.drawCircle(
                    cx, cy,
                    radius * 0.86f,
                    paint
                );

                canvas.drawCircle(
                    cx, cy,
                    radius * 0.31f,
                    paint
                );

                for (int i = 0; i < 8; i++) {
                    double angle =
                        Math.PI * i / 4;

                    canvas.drawLine(
                        cx + (float)Math.cos(angle)
                            * radius * 0.91f,
                        cy + (float)Math.sin(angle)
                            * radius * 0.91f,
                        cx + (float)Math.cos(angle)
                            * radius * 1.24f,
                        cy + (float)Math.sin(angle)
                            * radius * 1.24f,
                        paint
                    );
                }
            }
        }
    }

    // HSV 컬러휠
    private static class ColorWheel extends View {

        interface Listener {
            void onColorChange(
                int r,
                int g,
                int b
            );
        }

        private Listener listener;
        private Bitmap bitmap;

        private final Paint paint =
            new Paint(Paint.ANTI_ALIAS_FLAG);

        private float hue = 0f;
        private float saturation = 1f;

        ColorWheel(Context context) {
            super(context);
        }

        void setOnColorChange(
            Listener value
        ) {
            listener = value;
        }

        void setSelectedColor(int color) {
            float[] hsv = new float[3];

            Color.colorToHSV(color, hsv);

            hue = hsv[0];
            saturation = hsv[1];

            invalidate();
        }

        @Override
        protected void onSizeChanged(
            int width,
            int height,
            int oldWidth,
            int oldHeight
        ) {
            super.onSizeChanged(
                width,
                height,
                oldWidth,
                oldHeight
            );

            if (bitmap != null) {
                bitmap.recycle();
            }

            int size =
                Math.min(width, height);

            if (size <= 0) return;

            bitmap = Bitmap.createBitmap(
                size,
                size,
                Bitmap.Config.ARGB_8888
            );

            int[] pixels =
                new int[size * size];

            float radius =
                size / 2f;

            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {

                    float dx = x - radius;
                    float dy = y - radius;

                    float distance =
                        (float)Math.sqrt(
                            dx * dx + dy * dy
                        );

                    if (distance > radius - 2) {
                        pixels[y * size + x] =
                            Color.TRANSPARENT;

                    } else {
                        float angle =
                            (float)Math.toDegrees(
                                Math.atan2(dy, dx)
                            );

                        if (angle < 0) {
                            angle += 360;
                        }

                        pixels[y * size + x] =
                            Color.HSVToColor(
                                new float[]{
                                    angle,
                                    Math.min(
                                        1f,
                                        distance /
                                        (radius - 2)
                                    ),
                                    1f
                                }
                            );
                    }
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
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            if (bitmap == null) return;

            float size =
                bitmap.getWidth();

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

            float radians =
                (float)Math.toRadians(hue);

            float distance =
                (size / 2f - 2f) *
                saturation;

            float x =
                left + size / 2f +
                (float)Math.cos(radians) *
                distance;

            float y =
                top + size / 2f +
                (float)Math.sin(radians) *
                distance;

            float density =
                getResources()
                    .getDisplayMetrics()
                    .density;

            paint.setStyle(
                Paint.Style.STROKE
            );

            paint.setStrokeWidth(5f);
            paint.setColor(Color.BLACK);

            canvas.drawCircle(
                x, y,
                12f * density,
                paint
            );

            paint.setStrokeWidth(3f);
            paint.setColor(Color.WHITE);

            canvas.drawCircle(
                x, y,
                14f * density,
                paint
            );

            paint.setStyle(
                Paint.Style.FILL
            );
        }

        @Override
        public boolean onTouchEvent(
            MotionEvent event
        ) {
            int action =
                event.getAction();

            if (action != MotionEvent.ACTION_DOWN &&
                action != MotionEvent.ACTION_MOVE) {
                return true;
            }

            float size =
                Math.min(
                    getWidth(),
                    getHeight()
                );

            float dx =
                event.getX() -
                getWidth() / 2f;

            float dy =
                event.getY() -
                getHeight() / 2f;

            hue =
                (float)Math.toDegrees(
                    Math.atan2(dy, dx)
                );

            if (hue < 0) {
                hue += 360;
            }

            saturation =
                Math.min(
                    1f,
                    (float)Math.sqrt(
                        dx * dx + dy * dy
                    ) / (size / 2f - 2f)
                );

            int color =
                Color.HSVToColor(
                    new float[]{
                        hue,
                        saturation,
                        1f
                    }
                );

            invalidate();

            if (listener != null) {
                listener.onColorChange(
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
                );
            }

            return true;
        }
    }
}
