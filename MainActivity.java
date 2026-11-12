package com.example.ledcar;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
    private static final UUID SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final byte[] ON = hex("7BFF0407FFFFFFFFBF");
    private static final byte[] OFF = hex("7BFF0406FFFFFFFFBF");
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private final LinkedHashMap<String,BluetoothDevice> candidates = new LinkedHashMap<>();
    private String savedAddress = "", deviceName = "", connectionText = "기기 미등록";
    private boolean active, scanning, connecting, connected, choosing, updating, powerOn = true, dark;
    private int r = 255, g = 0, b = 0, brightness = 100;
    private final int[] presets = new int[6];
    private final boolean[] presetSaved = new boolean[6];
    private LinearLayout mainPage, settingsPage, presetRow;
    private TextView mainStatus, settingsStatus, settingsDevice, colorLabel, brightnessLabel;
    private SeekBar redBar, greenBar, blueBar;
    private ColorWheel wheel;
    private IconView powerIcon;
    private boolean settingsVisible;

    private final Runnable colorTask = () -> { if (connected) send(rgbPacket(r,g,b)); };
    private final Runnable brightnessTask = () -> { if (connected) send(brightnessPacket(brightness)); };
    private final Runnable retryTask = () -> { if (active && !connected && !connecting && !choosing) scanSaved(); };
    private final Runnable timeoutTask = () -> {
        stopScan();
        if (choosing) { choosing = false; showCandidates(); }
        else if (active && !connected && !connecting) { setStatus("차량 검색 대기 중"); scheduleRetry(); }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null || device.getAddress() == null) return;
            String address = device.getAddress();
            String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
            if (name == null) { try { name = device.getName(); } catch (SecurityException ignored) {} }
            final String foundName = name;
            handler.post(() -> {
                if (!active || connected || connecting) return;
                if (!choosing && !savedAddress.isEmpty() && savedAddress.equalsIgnoreCase(address)) {
                    connect(device, foundName);
                } else if (choosing) {
                    candidates.put(address, device);
                }
            });
        }
        @Override public void onScanFailed(int error) {
            handler.post(() -> {
                scanning = false;
                handler.removeCallbacks(timeoutTask);
                setStatus("검색 실패 (" + error + ")");
                if (choosing) { choosing = false; showCandidates(); }
                else scheduleRetry();
            });
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt current, int code, int state) {
            handler.post(() -> {
                if (current != gatt) { try { current.close(); } catch (Exception ignored) {} return; }
                if (code == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                    setStatus("서비스 확인 중...");
                    try { if (!current.discoverServices()) lost(current, "서비스 검색 실패"); }
                    catch (SecurityException e) { lost(current, "블루투스 권한 오류"); }
                } else lost(current, "연결 끊김 · 재연결 대기");
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt current, int code) {
            handler.post(() -> {
                if (current != gatt) return;
                BluetoothGattService service = code == BluetoothGatt.GATT_SUCCESS ? current.getService(SERVICE) : null;
                writeChar = service == null ? null : service.getCharacteristic(WRITE);
                if (writeChar == null || (writeChar.getProperties() & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) {
                    lost(current, "호환되지 않는 기기 (FFE0/FFE1 없음)");
                    return;
                }
                connected = true; connecting = false;
                savedAddress = current.getDevice().getAddress();
                try { String n = current.getDevice().getName(); if (n != null && !n.isEmpty()) deviceName = n; }
                catch (SecurityException ignored) {}
                getPreferences(MODE_PRIVATE).edit().putString("last_device",savedAddress).putString("last_name",deviceName).apply();
                setStatus("연결 완료");
            });
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        savedAddress = getPreferences(MODE_PRIVATE).getString("last_device", "");
        deviceName = getPreferences(MODE_PRIVATE).getString("last_name", "");
        dark = getPreferences(MODE_PRIVATE).getBoolean("dark_theme", false);
        for (int i=0; i<6; i++) {
            presetSaved[i] = getPreferences(MODE_PRIVATE).contains("preset_"+i);
            presets[i] = getPreferences(MODE_PRIVATE).getInt("preset_"+i, Color.WHITE);
        }
        BluetoothManager manager = (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        buildPages(false);
        if (hasPermissions()) startAuto(); else requestNeededPermissions();
    }

    private int fg() { return dark ? Color.WHITE : Color.rgb(40,43,49); }
    private int muted() { return dark ? Color.rgb(174,179,189) : Color.rgb(112,117,125); }
    private int bg() { return dark ? Color.rgb(17,19,24) : Color.rgb(250,250,251); }
    private int surface() { return dark ? Color.rgb(37,40,48) : Color.rgb(233,235,239); }
    private int accent() { return Color.rgb(0,165,139); }
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }
    private TextView text(String value, int size, int color) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); return v;
    }
    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(18),dp(18),dp(18),dp(18)); v.setBackgroundColor(bg()); return v;
    }
    private void section(LinearLayout parent, String title) {
        TextView t = text(title,19,fg()); t.setPadding(0,dp(22),0,dp(10)); parent.addView(t);
    }
    private void button(LinearLayout parent,String label,Runnable action) {
        Button v = new Button(this); v.setText(label); v.setTextColor(fg());
        v.setAllCaps(false); v.setBackgroundTintList(android.content.res.ColorStateList.valueOf(surface()));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,dp(52)); p.bottomMargin=dp(9);
        parent.addView(v,p); v.setOnClickListener(w->action.run());
    }
    private void buildPages(boolean showSettings) {
        settingsVisible = showSettings;
        mainPage = column(); settingsPage = column();
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); mainPage.addView(header);
        TextView title = text("엠비언트 설정",25,fg()); header.addView(title,new LinearLayout.LayoutParams(0,dp(56),1));
        IconView settingsIcon = new IconView(this,false); settingsIcon.setContentDescription("차량 연결 설정");
        header.addView(settingsIcon,new LinearLayout.LayoutParams(dp(48),dp(52)));
        settingsIcon.setOnClickListener(v->showPage(true));
        powerIcon = new IconView(this,true); header.addView(powerIcon,new LinearLayout.LayoutParams(dp(48),dp(52)));
        powerIcon.setContentDescription(powerOn?"전원 끄기":"전원 켜기");
        powerIcon.setOnClickListener(v->{ if (!connected) { Toast.makeText(this,"차량 연결을 확인하세요",Toast.LENGTH_SHORT).show(); return; }
            powerOn=!powerOn; powerIcon.invalidate(); powerIcon.setContentDescription(powerOn?"전원 끄기":"전원 켜기"); send(powerOn?ON:OFF);
        });
        mainStatus = text("연결 상태 확인 중",14,muted()); mainStatus.setPadding(0,dp(9),0,dp(5)); mainPage.addView(mainStatus);
        section(mainPage,"색상표");
        wheel = new ColorWheel(this); mainPage.addView(wheel,new LinearLayout.LayoutParams(-1,dp(290)));
        wheel.setOnColorChange((nr,ng,nb)->setColor(nr,ng,nb,true));
        colorLabel = text("",17,fg()); colorLabel.setPadding(0,dp(10),0,0); mainPage.addView(colorLabel);
        section(mainPage,"색상 프리셋");
        TextView hint = text("짧게 누르면 적용 · 길게 누르면 현재 색상 저장",12,muted()); mainPage.addView(hint);
        presetRow = new LinearLayout(this); presetRow.setPadding(0,dp(9),0,0); mainPage.addView(presetRow); refreshPresets();
        section(mainPage,"RGB");
        redBar = rgbSeek(mainPage,"R",r); greenBar = rgbSeek(mainPage,"G",g); blueBar = rgbSeek(mainPage,"B",b);
        SeekBar.OnSeekBarChangeListener rgbListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser) {
                if (fromUser && !updating) setColor(redBar.getProgress(),greenBar.getProgress(),blueBar.getProgress(),false);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) { queueColor(); }
        };
        redBar.setOnSeekBarChangeListener(rgbListener); greenBar.setOnSeekBarChangeListener(rgbListener); blueBar.setOnSeekBarChangeListener(rgbListener);
        section(mainPage,"밝기 조절");
        brightnessLabel = text(brightness+"%",15,muted()); mainPage.addView(brightnessLabel);
        SeekBar brightBar = new SeekBar(this); brightBar.setMax(99); brightBar.setProgress(brightness-1); mainPage.addView(brightBar);
        brightBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser) {
                if (!fromUser) return; brightness=progress+1; brightnessLabel.setText(brightness+"%");
                handler.removeCallbacks(brightnessTask); handler.postDelayed(brightnessTask,100);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) { handler.removeCallbacks(brightnessTask); handler.post(brightnessTask); }
        });
        setColor(r,g,b,false);

        LinearLayout settingsHeader = new LinearLayout(this); settingsHeader.setGravity(Gravity.CENTER_VERTICAL); settingsPage.addView(settingsHeader);
        IconView back = new IconView(this,false,true); settingsHeader.addView(back,new LinearLayout.LayoutParams(dp(42),dp(50)));
        back.setOnClickListener(v->showPage(false)); back.setContentDescription("뒤로 가기");
        TextView settingsTitle = text("차량 연결 설정",24,fg()); settingsHeader.addView(settingsTitle);
        section(settingsPage,"블루투스 연결 상태");
        settingsStatus = text("",19,accent()); settingsStatus.setPadding(0,dp(6),0,dp(8)); settingsPage.addView(settingsStatus);
        settingsDevice = text("",14,muted()); settingsDevice.setPadding(0,0,0,dp(14)); settingsPage.addView(settingsDevice);
        button(settingsPage,"차량 연결",()->{
            if (connected) { Toast.makeText(this,"이미 연결되어 있습니다",Toast.LENGTH_SHORT).show(); return; }
            if (savedAddress.isEmpty()) startDevicePicker(); else { active=true; scanSaved(); }
        });
        button(settingsPage,"다른 차량 선택",this::startDevicePicker);
        section(settingsPage,"화면 테마");
        RadioGroup themes = new RadioGroup(this); themes.setOrientation(RadioGroup.VERTICAL); settingsPage.addView(themes);
        RadioButton lightButton = new RadioButton(this); lightButton.setText("라이트 모드"); lightButton.setTextColor(fg()); themes.addView(lightButton);
        RadioButton darkButton = new RadioButton(this); darkButton.setText("다크 모드"); darkButton.setTextColor(fg()); themes.addView(darkButton);
        if (dark) darkButton.setChecked(true); else lightButton.setChecked(true);
        lightButton.setId(1001); darkButton.setId(1002);
        themes.setOnCheckedChangeListener((group,id)->{
            boolean next = id==1002;
            if (next==dark) return;
            dark=next; getPreferences(MODE_PRIVATE).edit().putBoolean("dark_theme",dark).apply();
            buildPages(true);
        });
        TextView note = text("선택한 테마는 앱을 종료해도 유지됩니다. 이전에 등록한 차량은 앱 실행 시 자동 연결합니다.",13,muted());
        note.setPadding(0,dp(14),0,0); settingsPage.addView(note);
        updateStatusViews(); showPage(showSettings);
    }
    private SeekBar rgbSeek(LinearLayout parent,String name,int value) {
        TextView label=text(name,13,muted()); parent.addView(label);
        SeekBar bar=new SeekBar(this); bar.setMax(255); bar.setProgress(value); parent.addView(bar); return bar;
    }
    private void showPage(boolean settings) {
        settingsVisible=settings; LinearLayout page=settings?settingsPage:mainPage;
        if (page.getParent()!=null) ((ViewGroup)page.getParent()).removeView(page);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.addView(page); setContentView(scroll);
        updateStatusViews();
    }
    private void setColor(int nr,int ng,int nb,boolean fromWheel) {
        r=nr;g=ng;b=nb;
        if (colorLabel!=null) { colorLabel.setText(String.format(Locale.US,"#%02X%02X%02X  ·  R %d  G %d  B %d",r,g,b,r,g,b));
            colorLabel.setTextColor(Color.rgb(r,g,b)); }
        if (fromWheel && redBar!=null) { updating=true;redBar.setProgress(r);greenBar.setProgress(g);blueBar.setProgress(b);updating=false; }
        if (!fromWheel && wheel!=null) wheel.setSelectedColor(Color.rgb(r,g,b));
        queueColor();
    }
    private void queueColor() { handler.removeCallbacks(colorTask); handler.postDelayed(colorTask,100); }
    private void refreshPresets() {
        if (presetRow==null) return; presetRow.removeAllViews();
        for (int i=0;i<6;i++) {
            final int index=i;
            TextView swatch=text(presetSaved[i]?"●":"+",28,presetSaved[i]?presets[i]:muted()); swatch.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(55),1); p.setMargins(dp(2),0,dp(2),0); presetRow.addView(swatch,p);
            swatch.setOnClickListener(v->{
                if (!presetSaved[index]) { Toast.makeText(this,"길게 눌러 색상을 저장하세요",Toast.LENGTH_SHORT).show(); return; }
                int c=presets[index];setColor(Color.red(c),Color.green(c),Color.blue(c),false);
            });
            swatch.setOnLongClickListener(v->{
                int c=Color.rgb(r,g,b);
                new AlertDialog.Builder(this).setTitle("프리셋 "+(index+1)+" 저장")
                    .setMessage(String.format(Locale.US,"현재 색상 #%06X을 저장할까요?",c&0xffffff))
                    .setPositiveButton("저장",(dialog,which)->{
                        presets[index]=c;presetSaved[index]=true;
                        getPreferences(MODE_PRIVATE).edit().putInt("preset_"+index,c).apply();refreshPresets();
                    }).setNegativeButton("취소",null).show();return true;
            });
        }
    }
    private void setStatus(String value) { connectionText=value; updateStatusViews(); }
    private void updateStatusViews() {
        if (mainStatus!=null) mainStatus.setText(connectionText+(connected && !savedAddress.isEmpty()?" · "+savedAddress:""));
        if (settingsStatus!=null) { settingsStatus.setText(connectionText);settingsStatus.setTextColor(connected?accent():muted()); }
        if (settingsDevice!=null) settingsDevice.setText("기기: "+(deviceName.isEmpty()?"이름 확인 안 됨":deviceName)+"\n주소: "+(savedAddress.isEmpty()?"등록되지 않음":savedAddress));
    }
    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT>=31) return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }
    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT>=31) requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},1);
        else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},1);
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(code,permissions,results);
        if (hasPermissions()) startAuto(); else setStatus("근처 기기 권한을 허용하세요");
    }
    private void startAuto() {
        active=true;
        if (savedAddress.isEmpty()) setStatus("기기 미등록 · 설정에서 차량을 선택하세요");
        else scanSaved();
    }
    private void scanSaved() {
        if (!active || choosing || scanning || connected || connecting || !hasPermissions()) return;
        if (savedAddress.isEmpty()) {setStatus("기기 미등록");return;}
        if (adapter==null || !adapter.isEnabled()) {setStatus("블루투스를 켜 주세요");scheduleRetry();return;}
        try {
            scanner=adapter.getBluetoothLeScanner();if(scanner==null){scheduleRetry();return;}
            scanner.startScan(scanCallback);scanning=true;setStatus("이전 차량 검색 중...");
            handler.removeCallbacks(timeoutTask);handler.postDelayed(timeoutTask,10000);
        } catch (SecurityException e) {setStatus("블루투스 권한 오류");}
    }
    private void startDevicePicker() {
        if (!hasPermissions()) {requestNeededPermissions();return;}
        if (adapter==null || !adapter.isEnabled()) {setStatus("블루투스를 켜 주세요");return;}
        disconnect();active=true;choosing=true;candidates.clear();
        try {
            scanner=adapter.getBluetoothLeScanner();if(scanner==null){choosing=false;setStatus("검색 불가");return;}
            scanner.startScan(scanCallback);scanning=true;setStatus("주변 BLE 기기 검색 중...");
            handler.removeCallbacks(timeoutTask);handler.postDelayed(timeoutTask,8000);
        } catch (SecurityException e) {choosing=false;setStatus("권한 오류");}
    }
    private void showCandidates() {
        if (isFinishing() || isDestroyed()) return;
        if (candidates.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("기기를 찾지 못했어요").setMessage("차량 LED 전원을 확인하고 다시 검색하세요.")
                .setPositiveButton("다시 검색",(d,w)->startDevicePicker()).setNegativeButton("닫기",null).show();return;
        }
        ArrayList<BluetoothDevice> devices=new ArrayList<>(candidates.values());String[] names=new String[devices.size()];
        for(int i=0;i<devices.size();i++) {
            BluetoothDevice dev=devices.get(i);String name=null;
            try{name=dev.getName();}catch(SecurityException ignored){}
            names[i]=(name==null||name.isEmpty()?"이름 없는 BLE 기기":name)+" · "+dev.getAddress();
        }
        new AlertDialog.Builder(this).setTitle("차량 기기 선택").setItems(names,(d,index)->connect(devices.get(index),names[index].split(" · ")[0]))
            .setNegativeButton("취소",null).setNeutralButton("다시 검색",(d,w)->startDevicePicker()).show();
    }
    private void connect(BluetoothDevice device,String name) {
        if (connected || connecting) return;
        stopScan();choosing=false;disconnect();connecting=true;
        if (name!=null) deviceName=name;
        setStatus("연결 중...");
        try {gatt=device.connectGatt(this,false,gattCallback,BluetoothDevice.TRANSPORT_LE);}
        catch (SecurityException e) {connecting=false;setStatus("연결 권한 오류");scheduleRetry();}
    }
    private void lost(BluetoothGatt current,String reason) {
        if (current!=gatt) return;
        gatt=null;writeChar=null;connected=false;connecting=false;
        try {current.close();}catch(Exception ignored){}
        setStatus(reason);scheduleRetry();
    }
    private void stopScan() {
        handler.removeCallbacks(timeoutTask);
        if (scanning && scanner!=null) {try {scanner.stopScan(scanCallback);}catch(Exception ignored){}}
        scanning=false;
    }
    private void disconnect() {
        stopScan();handler.removeCallbacks(retryTask);handler.removeCallbacks(colorTask);handler.removeCallbacks(brightnessTask);
        BluetoothGatt old=gatt;gatt=null;writeChar=null;connected=false;connecting=false;
        if (old!=null) {try {old.disconnect();old.close();}catch(Exception ignored){}}
    }
    private void scheduleRetry() {
        handler.removeCallbacks(retryTask);
        if (active && !choosing && !savedAddress.isEmpty() && !connected && !connecting) handler.postDelayed(retryTask,4000);
    }
    private void send(byte[] bytes) {
        if (!connected || gatt==null || writeChar==null) {setStatus("차량 연결 대기 중");return;}
        try {
            int props=writeChar.getProperties();
            int type=(props&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0
                ?BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE:BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
            if (Build.VERSION.SDK_INT>=33) {
                int result=gatt.writeCharacteristic(writeChar,bytes,type);
                if (result!=BluetoothStatusCodes.SUCCESS) setStatus("명령 전송 실패 ("+result+")");
            } else {
                writeChar.setWriteType(type);writeChar.setValue(bytes);
                if (!gatt.writeCharacteristic(writeChar)) setStatus("명령 전송 실패");
            }
        } catch (SecurityException e) {setStatus("블루투스 권한 오류");}
    }
    private static byte[] rgbPacket(int r,int g,int b) {
        return new byte[]{0x7B,0x01,0x07,(byte)r,(byte)g,(byte)b,0x00,(byte)0xFF,(byte)0xBF};
    }
    // Intermediate brightness values follow the previous unverified v0.5 format.
    private static byte[] brightnessPacket(int percent) {
        int v=Math.max(1,Math.min(100,percent));
        return new byte[]{0x7B,(byte)0xFF,0x01,(byte)(v==1?0x00:0x20),(byte)v,0x02,(byte)0xFF,(byte)0xFF,(byte)0xBF};
    }
    private static byte[] hex(String value) {
        byte[] bytes=new byte[value.length()/2];
        for(int i=0;i<bytes.length;i++)bytes[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);
        return bytes;
    }
    @Override public void onBackPressed() {if(settingsVisible)showPage(false);else super.onBackPressed();}
    @Override protected void onDestroy() {active=false;disconnect();super.onDestroy();}

    private class IconView extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean isPower, isBack;
        IconView(Context c,boolean power) {this(c,power,false);}
        IconView(Context c,boolean power,boolean back) {super(c);isPower=power;isBack=back;setClickable(true);}
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);float cx=getWidth()/2f,cy=getHeight()/2f;
            float radius=Math.min(getWidth(),getHeight())*.22f;
            p.setColor(isPower?(powerOn?accent():muted()):fg());p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(getResources().getDisplayMetrics().density * 2.4f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            if (isBack) {
                canvas.drawLine(cx+radius,cy,cx-radius,cy,p);
                canvas.drawLine(cx-radius,cy,cx,cy-radius,p);
                canvas.drawLine(cx-radius,cy,cx,cy+radius,p);
            } else if (isPower) {
                canvas.drawArc(new RectF(cx-radius,cy-radius,cx+radius,cy+radius),-48,276,false,p);
                canvas.drawLine(cx,cy-radius*1.3f,cx,cy+radius*.08f,p);
            } else {
                canvas.drawCircle(cx,cy,radius*.86f,p);
                canvas.drawCircle(cx,cy,radius*.31f,p);
                for(int i=0;i<8;i++) {
                    double a=Math.PI*i/4;
                    canvas.drawLine(cx+(float)Math.cos(a)*radius*.91f,cy+(float)Math.sin(a)*radius*.91f,
                        cx+(float)Math.cos(a)*radius*1.24f,cy+(float)Math.sin(a)*radius*1.24f,p);
                }
            }
        }
    }
    private static class ColorWheel extends View {
        interface Listener {void onColorChange(int r,int g,int b);}
        private Listener listener;
        private Bitmap bitmap;
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private float hue=0,saturation=1;
        ColorWheel(Context c) {super(c);}
        void setOnColorChange(Listener value) {listener=value;}
        void setSelectedColor(int color) {
            float[] hsv=new float[3];Color.colorToHSV(color,hsv);hue=hsv[0];saturation=hsv[1];invalidate();
        }
        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) {
            super.onSizeChanged(w,h,oldw,oldh);
            if(bitmap!=null)bitmap.recycle();
            int size=Math.min(w,h);if(size<=0)return;
            bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);
            int[] pixels=new int[size*size];float radius=size/2f;
            for(int y=0;y<size;y++)for(int x=0;x<size;x++) {
                float dx=x-radius,dy=y-radius,dist=(float)Math.sqrt(dx*dx+dy*dy);
                if(dist>radius-2)pixels[y*size+x]=Color.TRANSPARENT;
                else {float angle=(float)Math.toDegrees(Math.atan2(dy,dx));if(angle<0)angle+=360;
                    pixels[y*size+x]=Color.HSVToColor(new float[]{angle,Math.min(1,dist/(radius-2)),1});}
            }
            bitmap.setPixels(pixels,0,size,0,0,size,size);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);if(bitmap==null)return;
            float size=bitmap.getWidth(),left=(getWidth()-size)/2f,top=(getHeight()-size)/2f;
            canvas.drawBitmap(bitmap,left,top,p);
            float rad=(float)Math.toRadians(hue),distance=(size/2-2)*saturation;
            float x=left+size/2+(float)Math.cos(rad)*distance,y=top+size/2+(float)Math.sin(rad)*distance;
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(5);p.setColor(Color.BLACK);canvas.drawCircle(x,y,dpStatic(12),p);
            p.setStrokeWidth(3);p.setColor(Color.WHITE);canvas.drawCircle(x,y,dpStatic(14),p);p.setStyle(Paint.Style.FILL);
        }
        private float dpStatic(int n) {return n*getResources().getDisplayMetrics().density;}
        @Override public boolean onTouchEvent(MotionEvent event) {
            if(event.getAction()!=MotionEvent.ACTION_DOWN && event.getAction()!=MotionEvent.ACTION_MOVE)return true;
            float size=Math.min(getWidth(),getHeight()),dx=event.getX()-getWidth()/2f,dy=event.getY()-getHeight()/2f;
            hue=(float)Math.toDegrees(Math.atan2(dy,dx));if(hue<0)hue+=360;
            saturation=Math.min(1,(float)Math.sqrt(dx*dx+dy*dy)/(size/2-2));
            int c=Color.HSVToColor(new float[]{hue,saturation,1});invalidate();
            if(listener!=null)listener.onColorChange(Color.red(c),Color.green(c),Color.blue(c));return true;
        }
    }
}
