"""Disposable native AppWidgetHost for CI; no NoteLS reflection or private-code instrumentation."""
import os
import subprocess
import tempfile
import zipfile
from pathlib import Path
import release_settings_gc_probe as ui

PACKAGE='com.notessync.calendarhostprobe'
JAVA=r'''
package com.notessync.calendarhostprobe;
import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.os.*;
import android.text.*;
import android.text.style.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;

public class HostActivity extends Activity {
    private AppWidgetHost host;
    private AppWidgetHostView widget;
    private Handler handler = new Handler(Looper.getMainLooper());
    private long generation;
    private String kind, nonce, family, expected;
    private float factor;
    private int widgetId;
    private long deadline;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        host = new AppWidgetHost(this, 93);
        host.startListening();
        inspect(getIntent());
    }
    @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent); inspect(intent); }
    @Override public void onDestroy() { host.stopListening(); super.onDestroy(); }
    private void inspect(Intent intent) {
        kind=intent.getStringExtra("kind"); nonce=intent.getStringExtra("nonce");
        family=intent.getStringExtra("family"); expected=intent.getStringExtra("expected");
        factor=intent.getFloatExtra("factor",1f);
        long request=++generation;
        try {
            if (!"month".equals(kind) && !"week".equals(kind)) throw new AssertionError("Invalid kind");
            ComponentName component=new ComponentName("com.notessync.app", "com.notessync.app.widget."+
                ("month".equals(kind)?"CalendarWidgetProvider":"WeeklyWidgetProvider"));
            android.content.SharedPreferences prefs=getSharedPreferences("host",0);
            widgetId=prefs.getInt(kind,-1);
            AppWidgetManager manager=AppWidgetManager.getInstance(this);
            if(widgetId<0) {
                widgetId=host.allocateAppWidgetId();
                if(!manager.bindAppWidgetIdIfAllowed(widgetId,component)) throw new AssertionError("Native bind permission denied");
                prefs.edit().putInt(kind,widgetId).commit();
            }
            AppWidgetProviderInfo info=manager.getAppWidgetInfo(widgetId);
            if(info==null || !info.provider.equals(component)) throw new AssertionError("Wrong provider");
            widget=host.createView(this,widgetId,info);widget.setAppWidget(widgetId,info);
            float density=getResources().getDisplayMetrics().density;
            FrameLayout root=new FrameLayout(this);
            root.setBackgroundColor(0xff202020);
            root.addView(widget,new FrameLayout.LayoutParams(Math.round(320*density),Math.round(520*density)));
            setContentView(root);
            deadline=SystemClock.uptimeMillis()+15000;
            handler.postDelayed(new Runnable() { public void run() {
                if(request!=generation) return;
                try {
                    JSONObject result=capture();
                    if(!result.getBoolean("ready") && SystemClock.uptimeMillis()<deadline) { handler.postDelayed(this,200);return; }
                    result.put("status",result.getBoolean("ready")?"success":"failure");
                    save(result);
                } catch(Throwable error) { failure(error); }
            } },200);
        } catch(Throwable error) { failure(error); }
    }
    private JSONObject capture() throws Exception {
        JSONArray nodes=new JSONArray(); walk(widget,nodes);
        int textCount=0;boolean found=false,privateLeak=false;boolean correct=true;
        float scaled=getResources().getDisplayMetrics().scaledDensity;
        for(int i=0;i<nodes.length();i++) {
            JSONObject node=nodes.getJSONObject(i);String id=node.getString("id");String text=node.getString("text");
            if(text.contains("PRIVATE_CALENDAR_SECRET")) privateLeak=true;
            if(text.contains(expected)) found=true;
            float base="widget_month_title".equals(id)?16f:
                id.startsWith("widget_weekday_")?12f:"day_number".equals(id)?13f:
                "day_entries".equals(id)?11f:"week_title".equals(id)||id.startsWith("week_day_")?14f:0f;
            if(base>0 && text.length()>0) {
                textCount++;
                if(Math.abs(node.getDouble("size_px")-android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,base*factor,getResources().getDisplayMetrics()))>.1)correct=false;
                if(node.getInt("width")<=0 || node.getInt("height")<=0)correct=false;
                JSONArray families=node.getJSONArray("families");
                if("default".equals(family)) { if(families.length()!=0)correct=false; }
                else if(families.length()!=1 || !family.equals(families.getString(0)))correct=false;
            }
        }
        JSONObject result=new JSONObject(); result.put("nonce",nonce);result.put("kind",kind);result.put("widget_id",widgetId);
        result.put("provider","com.notessync.app.widget."+("month".equals(kind)?"CalendarWidgetProvider":"WeeklyWidgetProvider"));
        result.put("family",family);result.put("factor",factor);result.put("font_scale",getResources().getConfiguration().fontScale);
        result.put("density",getResources().getDisplayMetrics().density); result.put("scaled_density",scaled);
        result.put("width_px",widget.getWidth());result.put("height_px",widget.getHeight()); result.put("nodes",nodes);
        result.put("matching_typography",correct);result.put("visible_fixture",found);result.put("private_text_absent",!privateLeak);
        result.put("ready",correct && found && !privateLeak && textCount>=8);
        return result;
    }
    private void walk(View view,JSONArray out) throws Exception {
        if(view instanceof TextView) {
            TextView text=(TextView)view;CharSequence value=text.getText();String id="";
            try{id=view.getResources().getResourceEntryName(view.getId());}catch(Exception ignored){}
            JSONObject node=new JSONObject(); node.put("id",id);node.put("text",value.toString());
            node.put("size_px",text.getTextSize());node.put("width",view.getWidth());node.put("height",view.getHeight());
            JSONArray families=new JSONArray();int strike=0,color=0,bold=0;
            if(value instanceof Spanned) {
                Spanned styled=(Spanned)value;
                for(TypefaceSpan span:styled.getSpans(0,value.length(),TypefaceSpan.class))families.put(span.getFamily());
                strike=styled.getSpans(0,value.length(),StrikethroughSpan.class).length;
                color=styled.getSpans(0,value.length(),ForegroundColorSpan.class).length;
                bold=styled.getSpans(0,value.length(),StyleSpan.class).length;
            }
            node.put("families",families);node.put("strike_spans",strike);node.put("color_spans",color);node.put("style_spans",bold);out.put(node);
        }
        if(view instanceof ViewGroup) { ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)walk(group.getChildAt(i),out); }
    }
    private void failure(Throwable error) {
        try {JSONObject result=new JSONObject();result.put("nonce",nonce);result.put("status","failure");result.put("error",error.toString());save(result);}
        catch(Exception ignored){throw new RuntimeException(ignored);}
    }
    private void save(JSONObject result)throws Exception {
        File temp=new File(getExternalFilesDir(null),"widget-report.tmp");
        try(FileOutputStream stream=new FileOutputStream(temp)){stream.write(result.toString(2).getBytes("UTF-8"));}
        File destination=new File(getExternalFilesDir(null),"widget-report.json");
        if(!temp.renameTo(destination))throw new IOException("Report rename failed");
    }
}
'''
MANIFEST=f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{PACKAGE}">
<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35" />
<application android:debuggable="true" android:label="Disposable calendar widget host" android:theme="@android:style/Theme.Material.Light.NoActionBar">
<activity android:name=".HostActivity" android:exported="true" android:launchMode="singleTask" />
</application></manifest>'''

class CalendarWidgetHost:
    def __init__(self): self.temporary=None
    def prepare(self):
        assert ui.adb('shell','getprop','ro.kernel.qemu').strip()=='1','CI emulator only'
        sdk=Path(os.environ['ANDROID_HOME']);tools=sdk/'build-tools/35.0.0';android=sdk/'platforms/android-35/android.jar'
        assert android.is_file() and tools.is_dir()
        self.temporary=tempfile.TemporaryDirectory(prefix='notels-calendar-host-');root=Path(self.temporary.name)
        source=root/'HostActivity.java';source.write_text(JAVA);manifest=root/'AndroidManifest.xml';manifest.write_text(MANIFEST)
        classes=root/'classes';classes.mkdir();dex=root/'dex';dex.mkdir()
        subprocess.run(['javac','--release','8','-cp',str(android),'-d',str(classes),str(source)],check=True)
        subprocess.run([str(tools/'d8'),'--min-api','26','--lib',str(android),'--output',str(dex),
                        *map(str,classes.rglob('*.class'))],check=True)
        unsigned=root/'unsigned.apk'
        subprocess.run([str(tools/'aapt2'),'link','-I',str(android),'--manifest',str(manifest),'-o',str(unsigned)],check=True)
        with zipfile.ZipFile(unsigned,'a',compression=zipfile.ZIP_DEFLATED)as archive:archive.write(dex/'classes.dex','classes.dex')
        key=root/'fixture.p12';password='disposable-calendar-host-only'
        # Separate disposable helper key; never reads or changes NoteLS signing material.
        subprocess.run(['keytool','-genkeypair','-noprompt','-storetype','PKCS12','-keystore',str(key),'-alias','fixture',
            '-storepass',password,'-keypass',password,'-keyalg','RSA','-keysize','2048','-validity','2','-dname','CN=Disposable CI calendar host'],check=True,capture_output=True)
        aligned=root/'aligned.apk';apk=root/'helper.apk'
        subprocess.run([str(tools/'zipalign'),'-f','4',str(unsigned),str(aligned)],check=True)
        subprocess.run([str(tools/'apksigner'),'sign','--ks',str(key),'--ks-pass','pass:'+password,'--out',str(apk),str(aligned)],check=True)
        subprocess.run(['adb','uninstall',PACKAGE],capture_output=True)
        ui.adb('install',str(apk));ui.adb('shell','appwidget','grantbind','--package',PACKAGE)
    def close(self):
        subprocess.run(['adb','uninstall',PACKAGE],capture_output=True)
        if self.temporary:self.temporary.cleanup()
