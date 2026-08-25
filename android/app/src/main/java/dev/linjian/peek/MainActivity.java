package dev.linjian.peek;
import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private EditText serverInput, tokenInput, deviceInput, intervalInput, lockPackageInput;
    private TextView statusText, phoneStateText, debugText;
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b); setContentView(R.layout.activity_main);
        serverInput=findViewById(R.id.serverInput); tokenInput=findViewById(R.id.tokenInput); deviceInput=findViewById(R.id.deviceInput);
        intervalInput=findViewById(R.id.intervalInput); lockPackageInput=findViewById(R.id.lockPackageInput);
        statusText=findViewById(R.id.statusText); phoneStateText=findViewById(R.id.phoneStateText); debugText=findViewById(R.id.debugText);
        loadSettings();
        findViewById(R.id.saveButton).setOnClickListener(v->{saveSettings();updateUI();toast("设置已保存");});
        findViewById(R.id.startButton).setOnClickListener(v->startServiceNow());
        findViewById(R.id.stopButton).setOnClickListener(v->{stopService(new Intent(this,CompanionService.class));toast("服务已停止");});
        findViewById(R.id.accessibilityButton).setOnClickListener(v->openAccessibility());
        findViewById(R.id.connectionButton).setOnClickListener(v->checkConnection());
        findViewById(R.id.stateButton).setOnClickListener(v->updateUI());
        findViewById(R.id.screenshotButton).setOnClickListener(v->testScreenshot());
        findViewById(R.id.lockButton).setOnClickListener(v->testLock());
        findViewById(R.id.clearLogButton).setOnClickListener(v->{DebugState.clear(this);updateUI();});
        if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},8);
        updateUI();
    }
    private void loadSettings(){
        serverInput.setText(AppPrefs.server(this));tokenInput.setText(AppPrefs.token(this));deviceInput.setText(AppPrefs.device(this));
        intervalInput.setText(String.valueOf(AppPrefs.interval(this)));lockPackageInput.setText(AppPrefs.get(this).getString(AppPrefs.KEY_LOCK_PACKAGE,""));
    }
    private void saveSettings(){
        int interval=1800;try{interval=Integer.parseInt(intervalInput.getText().toString().trim());}catch(Exception ignored){}
        String lock=lockPackageInput.getText().toString().trim();if(!lock.isEmpty()&&!AppPrefs.isPackageLike(lock)){toast("一键锁屏包名格式不对");return;}
        AppPrefs.get(this).edit().putString(AppPrefs.KEY_SERVER,AppPrefs.cleanServer(serverInput.getText().toString())).putString(AppPrefs.KEY_TOKEN,tokenInput.getText().toString().trim())
          .putString(AppPrefs.KEY_DEVICE,deviceInput.getText().toString().trim().isEmpty()?"android-phone":deviceInput.getText().toString().trim())
          .putInt(AppPrefs.KEY_INTERVAL,Math.max(900,interval)).putString(AppPrefs.KEY_LOCK_PACKAGE,lock).apply();
    }
    private void startServiceNow(){saveSettings();if(AppPrefs.server(this).isEmpty()||AppPrefs.token(this).isEmpty()){toast("先填写服务器地址和 Token");return;}
        Intent i=new Intent(this,CompanionService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);toast("掌心窗已启动");}
    private void updateUI(){
        statusText.setText(ScreenshotService.ready()?"无障碍已连接 · 状态、截图和控制待命":"无障碍未连接");
        try{JSONObject s=LifeState.collect(this);phoneStateText.setText("屏幕："+(s.optBoolean("screen_on")?"亮":"灭")+"\\n当前 App："+s.optString("current_app","-")
          +"\\n电量："+s.optInt("battery_percent",-1)+"%"+(s.optBoolean("charging")?" · 充电中":"")+"\\n网络："+s.optString("network_type","-")
          +"\\n无障碍："+(s.optBoolean("accessibility_ready")?"正常":"未连接"));}catch(Exception e){phoneStateText.setText("状态读取失败");}
        debugText.setText(DebugState.get(this));
    }
    private void testScreenshot(){saveSettings();ScreenshotService s=ScreenshotService.getInstance();if(s==null){toast("先开启无障碍");return;}s.doScreenshot(AppPrefs.server(this),AppPrefs.token(this));toast("已请求截图");}
    private void testLock(){saveSettings();String pkg=AppPrefs.get(this).getString(AppPrefs.KEY_LOCK_PACKAGE,"");if(pkg.isEmpty()){toast("先填写“一键锁屏”的包名");return;}String r=CompanionService.openPackageResult(this,pkg);if(!r.startsWith("opened_"))toast(r);}
    private void checkConnection(){saveSettings();final String url=AppPrefs.server(this);if(url.isEmpty()){toast("先填写服务器地址");return;}
        new Thread(()->{String r;try{HttpURLConnection c=(HttpURLConnection)new URL(url+"/health").openConnection();c.setConnectTimeout(10000);c.setReadTimeout(15000);int code=c.getResponseCode();r=code==200?"后端连接正常":"连接失败：HTTP "+code;c.disconnect();}catch(Exception e){r="连接失败："+ScreenshotService.friendlyNetMsg(e);}final String m=r;runOnUiThread(()->toast(m));}).start();}
    private void openAccessibility(){try{startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}catch(Exception e){toast("设置 → 无障碍 → 掌心窗");}}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override protected void onResume(){super.onResume();updateUI();}
}
