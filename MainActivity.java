package com.example.ledcar;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
    private static final UUID SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final byte[] OFF = hex("7BFF0406FFFFFFFFBF"), ON = hex("7BFF0407FFFFFFFFBF");
    private static final byte[] BRIGHT_MAX = hex("7BFF01206402FFFFBF"), BRIGHT_MIN = hex("7BFF01000102FFFFBF");
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic characteristic;
    private TextView status;
    private LinearLayout root;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, BluetoothDevice> found = new LinkedHashMap<>();
    private boolean scanning = false;
    private int lastR=255,lastG=0,lastB=0;
    private final ScanCallback callback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
            if (name == null) name = device.getName();
            if (name == null || !name.toUpperCase(Locale.ROOT).contains("LEDCAR")) return;
            if (!found.containsKey(device.getAddress())) {
                found.put(device.getAddress(), device);
                final String title = name + " (" + device.getAddress() + ")";
                runOnUiThread(() -> button(title, () -> connect(device)));
            }
        }
        @Override public void onScanFailed(int code) { show("검색 실패: " + code); scanning=false; }
    };
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int statusCode, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) { show("연결됨, 서비스 확인 중"); g.discoverServices(); }
            else { characteristic=null; show("연결 해제 (상태 " + statusCode + ")"); }
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int statusCode) {
            BluetoothGattService service = g.getService(SERVICE);
            characteristic = service == null ? null : service.getCharacteristic(WRITE);
            show(characteristic == null ? "FFE1 특성을 찾지 못함" : "연결 준비 완료: FFE1");
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int statusCode) {
            if(statusCode != BluetoothGatt.GATT_SUCCESS) show("쓰기 실패: " + statusCode);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll=new ScrollView(this); root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(30,32,30,40); scroll.addView(root); setContentView(scroll);
        TextView title=new TextView(this); title.setText("LEDCAR BLE Controller"); title.setTextSize(24); root.addView(title);
        status=new TextView(this); status.setText("연결되지 않음"); status.setPadding(0,16,0,16); root.addView(status);
        button("LEDCAR 기기 검색", this::scan);
        button("검색 중지", this::stopScan);
        button("연결 해제", this::disconnect);
        section("전원"); button("켜기 (ON)", () -> send(ON)); button("끄기 (OFF)", () -> send(OFF));
        section("RGB 색상");
        button("빨강", () -> color(255,0,0)); button("초록", () -> color(0,255,0)); button("파랑", () -> color(0,0,255));
        button("흰색 (RGB 혼합)", () -> color(255,255,255));
        section("사용자 지정 RGB (0~255)");
        SeekBar red=seek("R",255), green=seek("G",0), blue=seek("B",0);
        button("선택한 RGB 적용", () -> color(red.getProgress(),green.getProgress(),blue.getProgress()));
        section("밝기 (검증된 2단계)");
        button("밝기 최대", () -> send(BRIGHT_MAX)); button("밝기 최소", () -> send(BRIGHT_MIN));
        TextView hint=new TextView(this); hint.setText("RGB 혼합색과 중간 밝기는 추가 검증 필요. 블루투스 연결 시 위치가 아닌 근처 기기 권한을 허용하세요."); root.addView(hint);
        BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE); adapter=manager.getAdapter();
        if(Build.VERSION.SDK_INT>=31 && (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT},101);
        } else if(Build.VERSION.SDK_INT<31 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},102);
        }
    }
    private void section(String s) { TextView t=new TextView(this);t.setText(s);t.setTextSize(19);t.setPadding(0,22,0,8);root.addView(t); }
    private void button(String s,Runnable action) { Button b=new Button(this);b.setText(s);root.addView(b);b.setOnClickListener(v->action.run()); }
    private SeekBar seek(String label,int initial) { TextView t=new TextView(this); t.setText(label+": "+initial);root.addView(t); SeekBar bar=new SeekBar(this);bar.setMax(255);bar.setProgress(initial);root.addView(bar);bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){t.setText(label+": "+p);}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});return bar; }
    private boolean allowed() { if(adapter==null || !adapter.isEnabled()){show("블루투스를 켜세요");return false;} if(Build.VERSION.SDK_INT>=31 && (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)){show("근처 기기 권한을 허용하세요");return false;} return true; }
    private void scan(){ if(!allowed())return; stopScan();found.clear();scanner=adapter.getBluetoothLeScanner();if(scanner==null){show("BLE 스캐너 없음");return;}scanner.startScan(callback);scanning=true;show("LEDCAR 검색 중...");handler.postDelayed(this::stopScan,12000); }
    private void stopScan(){if(scanning && scanner!=null){try{scanner.stopScan(callback);}catch(Exception ignored){}}scanning=false;}
    private void connect(BluetoothDevice device){if(!allowed())return;stopScan();disconnect();show("연결 중: "+device.getAddress());gatt=device.connectGatt(this,false,gattCallback,BluetoothDevice.TRANSPORT_LE);}
    private void disconnect(){characteristic=null;if(gatt!=null){gatt.disconnect();gatt.close();gatt=null;}}
    private void color(int r,int g,int b){lastR=r;lastG=g;lastB=b;send(new byte[]{0x7B,0x01,0x07,(byte)r,(byte)g,(byte)b,0x00,(byte)0xFF,(byte)0xBF});}
    private void send(byte[] data){if(gatt==null || characteristic==null){show("먼저 LEDCAR에 연결하세요");return;}try{
        int props=characteristic.getProperties();int type=(props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0 ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        if(Build.VERSION.SDK_INT>=33){int result=gatt.writeCharacteristic(characteristic,data,type);if(result!=BluetoothStatusCodes.SUCCESS)show("쓰기 요청 실패: "+result);}
        else{characteristic.setWriteType(type);characteristic.setValue(data);if(!gatt.writeCharacteristic(characteristic))show("쓰기 요청 실패");}
    }catch(SecurityException e){show("블루투스 권한 오류");} }
    private void show(String s){runOnUiThread(()->status.setText(s));}
    private static byte[] hex(String s){byte[] out=new byte[s.length()/2];for(int i=0;i<out.length;i++)out[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return out;}
    @Override protected void onDestroy(){stopScan();disconnect();super.onDestroy();}
}
