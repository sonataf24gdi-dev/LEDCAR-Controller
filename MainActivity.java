package com.example.ledcar;

import android.Manifest;
import android.app.Activity;
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
    private static final UUID SERVICE=UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE=UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final byte[] OFF=hex("7BFF0406FFFFFFFFBF"), ON=hex("7BFF0407FFFFFFFFBF");
    private static final byte[] BRIGHT_MAX=hex("7BFF01206402FFFFBF"), BRIGHT_MIN=hex("7BFF01000102FFFFBF");
    private final Handler handler=new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter; private BluetoothLeScanner scanner; private BluetoothGatt gatt;
    private BluetoothGattCharacteristic characteristic; private TextView status, colorLabel, brightnessLabel; private TextView powerButton; private LinearLayout mainPage, settingsPage; private boolean powerOn=true; private int brightness=100;
    private SeekBar red,green,blue; private ColorWheel wheel;
    private LinearLayout presetRow; private final int[] presets=new int[6]; private final boolean[] presetSaved=new boolean[6];
    private boolean scanning=false, active=false, connecting=false, connected=false, updating=false, choosing=false;
    private final LinkedHashMap<String,BluetoothDevice> candidates=new LinkedHashMap<>();
    private String savedAddress=""; private int r=255,g=0,b=0;
    private final Runnable sendPending=()->{if(connected) send(rgbPacket(r,g,b));};
    private final Runnable sendBrightness=()->{if(connected)send(brightnessPacket(brightness));};
    private final Runnable retry=()->{if(active&&!connected&&!connecting) scan();};
    private final Runnable scanTimeout=()->{stopScan(); if(choosing){choosing=false;showCandidates();}else if(active&&!connected&&!connecting) scheduleRetry();};

    private final ScanCallback scanCallback=new ScanCallback(){
        @Override public void onScanResult(int type, ScanResult result){
            BluetoothDevice device=result.getDevice();
            if(device==null)return;
            final String address=device.getAddress();
            if(address==null)return;
            String name=result.getScanRecord()==null?null:result.getScanRecord().getDeviceName();
            if(name==null){try{name=device.getName();}catch(SecurityException ignored){}}
            final String displayName=name;
            boolean advertisesService=false;
            if(result.getScanRecord()!=null && result.getScanRecord().getServiceUuids()!=null){
                for(android.os.ParcelUuid uuid:result.getScanRecord().getServiceUuids()){
                    if(SERVICE.equals(uuid.getUuid())){advertisesService=true;break;}
                }
            }
            final boolean likelyCompatible=advertisesService || (name!=null && name.toUpperCase(Locale.ROOT).contains("LEDCAR"));
            handler.post(()->{
                if(!active || connecting || connected)return;
                if(!choosing && !savedAddress.isEmpty() && savedAddress.equalsIgnoreCase(address)){
                    choosing=false;connect(device);return;
                }
                if(choosing && ((displayName!=null && !displayName.trim().isEmpty()) || likelyCompatible)){
                    candidates.put(address,device);
                }
            });
        }
        @Override public void onScanFailed(int error){handler.post(()->{
            scanning=false;handler.removeCallbacks(scanTimeout);
            show("검색 실패 ("+error+")");
            if(choosing){choosing=false;showCandidates();}else scheduleRetry();
        });}
    };
    private final BluetoothGattCallback gattCallback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt current,int code,int state){handler.post(()->{
            if(current!=gatt) {try{current.close();}catch(Exception ignored){}return;}
            if(state==BluetoothProfile.STATE_CONNECTED && code==BluetoothGatt.GATT_SUCCESS){show("연결됨 · 서비스 확인 중");
                try{current.discoverServices();}catch(SecurityException e){lost(current);} }
            else lost(current);
        });}
        @Override public void onServicesDiscovered(BluetoothGatt current,int code){handler.post(()->{
            if(current!=gatt)return;
            BluetoothGattService service=current.getService(SERVICE);
            characteristic=(code==BluetoothGatt.GATT_SUCCESS&&service!=null)?service.getCharacteristic(WRITE):null;
            if(characteristic!=null){
                connected=true;connecting=false;
                savedAddress=current.getDevice().getAddress();getPreferences(MODE_PRIVATE).edit().putString("last_device",savedAddress).apply();
                show("연결 완료 · "+savedAddress);
            }else{show("호환되지 않는 기기 · FFE0/FFE1 없음");lost(current);}
        });}
        @Override public void onCharacteristicWrite(BluetoothGatt current,BluetoothGattCharacteristic c,int code){
            if(code!=BluetoothGatt.GATT_SUCCESS)show("명령 전송 오류: "+code);
        }
    };
    @Override public void onCreate(Bundle state){super.onCreate(state);
        savedAddress=getPreferences(MODE_PRIVATE).getString("last_device","");
        for(int i=0;i<6;i++){presetSaved[i]=getPreferences(MODE_PRIVATE).contains("preset_"+i);presets[i]=getPreferences(MODE_PRIVATE).getInt("preset_"+i,Color.WHITE);}
        BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);adapter=manager.getAdapter();
        buildPages();
        if(hasPermissions())startAuto();else requestNeededPermissions();
    }
    private void buildPages(){
        mainPage=new LinearLayout(this);mainPage.setOrientation(LinearLayout.VERTICAL);mainPage.setPadding(dp(18),dp(18),dp(18),dp(18));
        settingsPage=new LinearLayout(this);settingsPage.setOrientation(LinearLayout.VERTICAL);settingsPage.setPadding(dp(18),dp(18),dp(18),dp(18));
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);mainPage.addView(header);
        TextView title=new TextView(this);title.setText("엠비언트 설정");title.setTextSize(26);header.addView(title,new LinearLayout.LayoutParams(0,dp(60),1));
        powerButton=new TextView(this);powerButton.setText("⏻");powerButton.setTextSize(37);powerButton.setGravity(Gravity.CENTER);
        TextView settingsIcon=new TextView(this);settingsIcon.setText("⚙");settingsIcon.setTextSize(27);settingsIcon.setGravity(Gravity.CENTER);
        settingsIcon.setContentDescription("차량 연결 설정");header.addView(settingsIcon,new LinearLayout.LayoutParams(dp(48),dp(60)));
        settingsIcon.setOnClickListener(v->openSettings());
        header.addView(powerButton,new LinearLayout.LayoutParams(dp(52),dp(60)));powerButton.setContentDescription("전원 켜기 또는 끄기");
        powerButton.setOnClickListener(v->{powerOn=!powerOn;refreshPower();send(powerOn?ON:OFF);});refreshPower();
        status=new TextView(this);status.setText("자동 연결 준비 중");status.setPadding(0,dp(10),0,dp(10));mainPage.addView(status);
        section(mainPage,"색상표 · 터치하거나 드래그하세요");
        wheel=new ColorWheel(this);mainPage.addView(wheel,new LinearLayout.LayoutParams(-1,dp(290)));
        wheel.setOnColorChange((nr,ng,nb)->setColor(nr,ng,nb,true));
        colorLabel=new TextView(this);colorLabel.setTextSize(18);colorLabel.setPadding(0,dp(8),0,dp(8));mainPage.addView(colorLabel);
        section(mainPage,"색상 프리셋");
        TextView presetHelp=new TextView(this);presetHelp.setText("짧게 누르면 적용 · 길게 누르면 현재 색상 저장");presetHelp.setTextSize(13);mainPage.addView(presetHelp);
        presetRow=new LinearLayout(this);presetRow.setOrientation(LinearLayout.HORIZONTAL);presetRow.setPadding(0,dp(8),0,0);mainPage.addView(presetRow);refreshPresets();
        section(mainPage,"RGB");red=seek(mainPage,"R",255);green=seek(mainPage,"G",0);blue=seek(mainPage,"B",0);
        SeekBar.OnSeekBarChangeListener rgbListener=new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar s,int value,boolean user){if(user&&!updating)setColor(red.getProgress(),green.getProgress(),blue.getProgress(),false);}
            @Override public void onStartTrackingTouch(SeekBar s){}
            @Override public void onStopTrackingTouch(SeekBar s){queueColor();}
        };
        red.setOnSeekBarChangeListener(rgbListener);green.setOnSeekBarChangeListener(rgbListener);blue.setOnSeekBarChangeListener(rgbListener);
        section(mainPage,"밝기 조절");
        brightnessLabel=new TextView(this);mainPage.addView(brightnessLabel);
        SeekBar brightBar=new SeekBar(this);brightBar.setMax(99);brightBar.setProgress(99);mainPage.addView(brightBar);
        brightnessLabel.setText("100%");
        brightBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean user){if(!user)return;
                brightness=progress+1;brightnessLabel.setText(brightness+"%");handler.removeCallbacks(sendBrightness);handler.postDelayed(sendBrightness,100);
            }
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){handler.removeCallbacks(sendBrightness);handler.post(sendBrightness);}
        });
        setColor(255,0,0,false);showPage(mainPage);
        TextView settingsTitle=new TextView(this);settingsTitle.setText("차량 연결 설정");settingsTitle.setTextSize(25);settingsPage.addView(settingsTitle);
        TextView info=new TextView(this);info.setText("이전에 선택한 차량은 앱 실행 시 자동 연결합니다. 기기 이름이 바뀌어도 저장된 주소로 인식합니다.");
        info.setPadding(0,dp(16),0,dp(16));settingsPage.addView(info);
        button(settingsPage,"차량 연결",()->{if(savedAddress.isEmpty())startDevicePicker();else{disconnect(false);scan();}});
        button(settingsPage,"다른 차량 선택",()->startDevicePicker());
        button(settingsPage,"←  메인 화면",()->showPage(mainPage));
    }
    private void refreshPresets(){
        if(presetRow==null)return;
        presetRow.removeAllViews();
        for(int i=0;i<presets.length;i++){
            final int index=i;
            TextView swatch=new TextView(this);
            swatch.setText(presetSaved[i]?"●":"＋");swatch.setTextSize(25);
            swatch.setGravity(Gravity.CENTER);
            swatch.setTextColor(presetSaved[i]?presets[i]:Color.GRAY);
            swatch.setContentDescription("프리셋 "+(i+1)+(presetSaved[i]?" · 누르면 적용, 길게 누르면 저장":" · 길게 누르면 저장"));
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,dp(54),1);
            params.setMargins(dp(2),0,dp(2),0);
            presetRow.addView(swatch,params);
            swatch.setOnClickListener(v->{
                if(presetSaved[index]){
                    int color=presets[index];setColor(Color.red(color),Color.green(color),Color.blue(color),false);
                    Toast.makeText(this,"프리셋 "+(index+1)+" 적용",Toast.LENGTH_SHORT).show();
                }else{
                    Toast.makeText(this,"길게 눌러 현재 색상을 저장하세요",Toast.LENGTH_SHORT).show();
                }
            });
            swatch.setOnLongClickListener(v->{
                int color=Color.rgb(r,g,b);
                new android.app.AlertDialog.Builder(this)
                    .setTitle("프리셋 "+(index+1)+" 저장")
                    .setMessage(String.format(Locale.US,"현재 색상 #%06X을 저장할까요?",color&0xFFFFFF))
                    .setPositiveButton("저장",(dialog,which)->{
                        presets[index]=color;presetSaved[index]=true;
                        getPreferences(MODE_PRIVATE).edit().putInt("preset_"+index,color).apply();
                        refreshPresets();
                        Toast.makeText(this,"색상 저장 완료",Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("취소",null).show();
                return true;
            });
        }
    }
    private void refreshPower(){if(powerButton!=null){powerButton.setTextColor(powerOn?Color.rgb(0,165,130):Color.GRAY);powerButton.setContentDescription(powerOn?"전원 끄기":"전원 켜기");}}
    private void openSettings(){showPage(settingsPage);}
    private void showPage(LinearLayout page){ScrollView scroll=new ScrollView(this);if(page.getParent()!=null)((android.view.ViewGroup)page.getParent()).removeView(page);scroll.addView(page);setContentView(scroll);}
    private static byte[] brightnessPacket(int value){int v=Math.max(1,Math.min(100,value));return new byte[]{0x7B,(byte)0xFF,0x01,(byte)(v==1?0x00:0x20),(byte)v,0x02,(byte)0xFF,(byte)0xFF,(byte)0xBF};}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private void section(LinearLayout root,String text){TextView t=new TextView(this);t.setText(text);t.setTextSize(19);t.setPadding(0,dp(18),0,dp(7));root.addView(t);}
    private void button(LinearLayout root,String text,Runnable action){Button b=new Button(this);b.setText(text);root.addView(b,root.getOrientation()==LinearLayout.HORIZONTAL?new LinearLayout.LayoutParams(0,dp(50),1):new LinearLayout.LayoutParams(-1,dp(50)));b.setOnClickListener(v->action.run());}
    private SeekBar seek(LinearLayout root,String name,int initial){TextView label=new TextView(this);label.setText(name);root.addView(label);SeekBar bar=new SeekBar(this);bar.setMax(255);bar.setProgress(initial);root.addView(bar);return bar;}
    private void setColor(int nr,int ng,int nb,boolean fromWheel){r=nr;g=ng;b=nb;
        if(colorLabel!=null){colorLabel.setText(String.format(Locale.US,"#%02X%02X%02X  ·  R %d  G %d  B %d",r,g,b,r,g,b));colorLabel.setTextColor(Color.rgb(r,g,b));}
        if(fromWheel&&red!=null){updating=true;red.setProgress(r);green.setProgress(g);blue.setProgress(b);updating=false;}
        if(!fromWheel&&wheel!=null)wheel.setSelectedColor(Color.rgb(r,g,b));
        queueColor();
    }
    private void queueColor(){handler.removeCallbacks(sendPending);handler.postDelayed(sendPending,100);}
    private static byte[] rgbPacket(int r,int g,int b){return new byte[]{0x7B,0x01,0x07,(byte)r,(byte)g,(byte)b,0x00,(byte)0xFF,(byte)0xBF};}
    private boolean hasPermissions(){if(Build.VERSION.SDK_INT>=31)return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    private void requestNeededPermissions(){if(Build.VERSION.SDK_INT>=31)requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},1);
        else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},1);}
    @Override public void onRequestPermissionsResult(int req,String[] permissions,int[] grants){super.onRequestPermissionsResult(req,permissions,grants);if(hasPermissions())startAuto();else show("근처 기기 권한을 허용하세요");}
    private void startAuto(){active=true;
        if(savedAddress.isEmpty())show("차량 연결을 눌러 기기를 선택하세요");
        else scan();
    }
    private void startDevicePicker(){
        if(!hasPermissions()){requestNeededPermissions();return;}
        if(adapter==null || !adapter.isEnabled()){show("블루투스를 켜 주세요");return;}
        disconnect(false);active=true;choosing=true;candidates.clear();
        try{
            scanner=adapter.getBluetoothLeScanner();
            if(scanner==null){choosing=false;show("블루투스 검색을 시작할 수 없음");return;}
            scanner.startScan(scanCallback);scanning=true;
            show("주변 차량 기기 검색 중 · 8초");
            handler.removeCallbacks(scanTimeout);handler.postDelayed(scanTimeout,8000);
        }catch(SecurityException e){choosing=false;show("블루투스 권한 오류");}
    }
    private void showCandidates(){
        if(isFinishing()||isDestroyed())return;
        if(candidates.isEmpty()){
            new android.app.AlertDialog.Builder(this).setTitle("기기를 찾지 못했어요")
                .setMessage("기기 전원과 블루투스를 확인하고 다시 검색하세요. 이름이 바뀐 기기도 검색할 수 있습니다.")
                .setPositiveButton("다시 검색",(dialog,which)->startDevicePicker())
                .setNegativeButton("닫기",null).show();
            return;
        }
        final ArrayList<BluetoothDevice> devices=new ArrayList<>(candidates.values());
        String[] labels=new String[devices.size()];
        for(int i=0;i<devices.size();i++){
            BluetoothDevice device=devices.get(i);String name=null;
            try{name=device.getName();}catch(SecurityException ignored){}
            labels[i]=(name==null||name.trim().isEmpty()?"이름 없는 기기":name)+"  ·  "+device.getAddress();
        }
        new android.app.AlertDialog.Builder(this).setTitle("연결할 차량 기기 선택")
            .setItems(labels,(dialog,which)->connect(devices.get(which)))
            .setNegativeButton("취소",null)
            .setNeutralButton("다시 검색",(dialog,which)->startDevicePicker()).show();
    }
    private void scheduleRetry(){handler.removeCallbacks(retry);if(active&&!choosing&&!savedAddress.isEmpty()&&!connected&&!connecting)handler.postDelayed(retry,4000);}
    private void scan(){if(!active||!hasPermissions()||choosing)return;
        if(savedAddress.isEmpty()){show("차량 연결을 눌러 기기를 선택하세요");return;}if(adapter==null||!adapter.isEnabled()){show("블루투스를 켜 주세요");scheduleRetry();return;}
        if(scanning||connecting||connected)return;
        try{scanner=adapter.getBluetoothLeScanner();if(scanner==null){scheduleRetry();return;}scanner.startScan(scanCallback);scanning=true;
            show("이전에 선택한 차량 자동 검색 중...");handler.removeCallbacks(scanTimeout);handler.postDelayed(scanTimeout,10000);
        }catch(SecurityException e){show("블루투스 권한 오류");}
    }
    private void stopScan(){handler.removeCallbacks(scanTimeout);if(scanning&&scanner!=null){try{scanner.stopScan(scanCallback);}catch(Exception ignored){}}scanning=false;}
    private void connect(BluetoothDevice device){if(connecting||connected)return;stopScan();choosing=false;disconnect(false);connecting=true;
        show("연결 중 · "+device.getAddress());try{gatt=device.connectGatt(this,false,gattCallback,BluetoothDevice.TRANSPORT_LE);}
        catch(SecurityException e){connecting=false;show("연결 권한 오류");scheduleRetry();}
    }
    private void lost(BluetoothGatt current){if(current==gatt){gatt=null;characteristic=null;connecting=false;connected=false;
            try{current.close();}catch(Exception ignored){}show("연결 끊김 · 재연결 대기");scheduleRetry();}}
    private void disconnect(boolean stopAuto){if(stopAuto)active=false;stopScan();handler.removeCallbacks(retry);handler.removeCallbacks(sendPending);handler.removeCallbacks(sendBrightness);
        BluetoothGatt old=gatt;gatt=null;characteristic=null;connecting=false;connected=false;
        if(old!=null){try{old.disconnect();old.close();}catch(Exception ignored){}}
    }
    private void send(byte[] data){if(!connected||gatt==null||characteristic==null){show("차량 연결 대기 중");return;}
        try{int props=characteristic.getProperties();int type=(props&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0?BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE:BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
            if(Build.VERSION.SDK_INT>=33){int result=gatt.writeCharacteristic(characteristic,data,type);if(result!=BluetoothStatusCodes.SUCCESS)show("전송 실패: "+result);}
            else{characteristic.setWriteType(type);characteristic.setValue(data);if(!gatt.writeCharacteristic(characteristic))show("전송 실패");}
        }catch(SecurityException e){show("블루투스 권한 오류");}
    }
    private void show(String text){runOnUiThread(()->{if(status!=null)status.setText(text);});}
    private static byte[] hex(String value){byte[] out=new byte[value.length()/2];for(int i=0;i<out.length;i++)out[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return out;}
    @Override public void onBackPressed(){if(settingsPage!=null && settingsPage.getParent()!=null){showPage(mainPage);}else{super.onBackPressed();}}
    @Override protected void onDestroy(){active=false;disconnect(true);super.onDestroy();}

    private static class ColorWheel extends View {
        interface Listener{void onColorChange(int r,int g,int b);}
        private Listener listener;private Bitmap bitmap;private final Paint paint=new Paint(3);private final Paint marker=new Paint(3);
        private float hue=0,saturation=1;private int selected=Color.RED;
        ColorWheel(Context ctx){super(ctx);}
        void setOnColorChange(Listener l){listener=l;}
        void setSelectedColor(int color){selected=color;float[] hsv=new float[3];Color.colorToHSV(color,hsv);hue=hsv[0];saturation=hsv[1];invalidate();}
        @Override protected void onSizeChanged(int w,int h,int ow,int oh){super.onSizeChanged(w,h,ow,oh);if(bitmap!=null)bitmap.recycle();
            int size=Math.min(w,h);if(size<=0)return;bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);
            int[] pixels=new int[size*size];float radius=size/2f;
            for(int y=0;y<size;y++)for(int x=0;x<size;x++){float dx=x-radius,dy=y-radius;float dist=(float)Math.sqrt(dx*dx+dy*dy);
                if(dist>radius-2)pixels[y*size+x]=Color.TRANSPARENT;
                else{float angle=(float)Math.toDegrees(Math.atan2(dy,dx));if(angle<0)angle+=360;
                    pixels[y*size+x]=Color.HSVToColor(new float[]{angle,Math.min(1,dist/(radius-2)),1});}}
            bitmap.setPixels(pixels,0,size,0,0,size,size);
        }
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);if(bitmap==null)return;
            float size=bitmap.getWidth(),left=(getWidth()-size)/2,top=(getHeight()-size)/2;
            canvas.drawBitmap(bitmap,left,top,paint);float rad=(float)Math.toRadians(hue),radius=(size/2-2)*saturation;
            float cx=left+size/2+(float)Math.cos(rad)*radius,cy=top+size/2+(float)Math.sin(rad)*radius;
            marker.setStyle(Paint.Style.STROKE);marker.setStrokeWidth(5);marker.setColor(Color.BLACK);canvas.drawCircle(cx,cy,12,marker);
            marker.setStrokeWidth(3);marker.setColor(Color.WHITE);canvas.drawCircle(cx,cy,15,marker);marker.setStyle(Paint.Style.FILL);
        }
        @Override public boolean onTouchEvent(android.view.MotionEvent event){if(event.getAction()!=MotionEvent.ACTION_DOWN&&event.getAction()!=MotionEvent.ACTION_MOVE)return true;
            float size=Math.min(getWidth(),getHeight());float dx=event.getX()-getWidth()/2f,dy=event.getY()-getHeight()/2f;
            hue=(float)Math.toDegrees(Math.atan2(dy,dx));if(hue<0)hue+=360;
            saturation=Math.min(1,(float)Math.sqrt(dx*dx+dy*dy)/(size/2-2));
            selected=Color.HSVToColor(new float[]{hue,saturation,1});invalidate();
            if(listener!=null)listener.onColorChange(Color.red(selected),Color.green(selected),Color.blue(selected));return true;
        }
    }
}
