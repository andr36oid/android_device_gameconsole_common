package org.andr36oid.subsystemsetup;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.Formatter;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import org.andr36oid.hints.HintBar;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Installation state comes from the root worker, never a local wizard checkbox. */
public class SubsystemSetupActivity extends Activity {
    static final String P="sys.andr36oid.subsystem.";
    static final String SELECT="persist.sys.andr36oid.subsystem.selected";
    static final String ICON="persist.andr36oid.subsystem.es_icon";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final List<Image> images=new ArrayList<>();
    private LinearLayout side,extra;
    private TextView title,body,label,live;
    private ProgressBar progress;
    private String page="",route="",log="",optLog="",version="",operation="",state="",resolved="",check="";
    private long overlays,requestedAt;
    private String requestSeq="";
    private boolean resumed,reading,ready,available,busy,pending;
    private final Runnable poll=this::refresh;
    static final class Image {
        String id,name,path; long original,optimized;
        Image(String[] v) { id=v[0];name=v[1];path=v[2];original=number(v[3]);optimized=number(v[4]); }
    }
    static long number(String s) { try { return Long.parseLong(s.trim()); } catch(RuntimeException e) { return 0; } }
    static String get(String key) { return SubsystemBackend.get(key); }
    static void put(String key,String value) { if(!SubsystemBackend.set(key,value)) throw new IllegalStateException(key); }
    static boolean isReady() { return "1".equals(get(P+"ready")) && "1".equals(get(P+"image")); }
    static void updateLauncher(Activity context) { updateLauncher((android.content.Context)context); }
    static void updateLauncher(android.content.Context context) {
        boolean visible=isReady() && "1".equals(get(ICON));
        ComponentName alias=new ComponentName(context,"org.andr36oid.subsystemsetup.EmulationStationLauncher");
        int wanted=visible?PackageManager.COMPONENT_ENABLED_STATE_ENABLED:PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        PackageManager pm=context.getPackageManager();
        if(pm.getComponentEnabledSetting(alias)!=wanted) pm.setComponentEnabledSetting(alias,wanted,PackageManager.DONT_KILL_APP);
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); setContentView(R.layout.subsystem_wizard); HintBar.attachDefault(this);
        title=findViewById(R.id.title);body=findViewById(R.id.body);label=findViewById(R.id.step_label);
        side=findViewById(R.id.side);extra=findViewById(R.id.body_extra);progress=findViewById(R.id.progress);
        findViewById(R.id.console).setVisibility(View.GONE);
        findViewById(R.id.secondary).setVisibility(View.GONE);findViewById(R.id.next).setVisibility(View.GONE);
        Button back=findViewById(R.id.back);back.setText(R.string.subsystem_back);back.setOnClickListener(v->onBackPressed());
        if(getResources().getConfiguration().screenWidthDp<480) {
            LinearLayout panes=findViewById(R.id.panes);panes.setOrientation(LinearLayout.VERTICAL);
            for(int i=0;i<panes.getChildCount();i++) panes.getChildAt(i).setLayoutParams(new LinearLayout.LayoutParams(-1,0,1));
        }
        if(saved!=null) page=saved.getString("page","");
        show(); request("scan");
    }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out);out.putString("page",page); }
    @Override protected void onResume() { super.onResume();resumed=true;handler.post(poll); }
    @Override protected void onPause() { resumed=false;handler.removeCallbacks(poll);super.onPause(); }
    @Override protected void onDestroy() { io.shutdownNow();super.onDestroy(); }
    @Override public void onBackPressed() { if(!page.isEmpty()) {page="";show();} else super.onBackPressed(); }
    static String tail(String path,int bytes) {
        try(RandomAccessFile f=new RandomAccessFile(path,"r")) {
            int n=(int)Math.min(f.length(),bytes);byte[] b=new byte[n];f.seek(f.length()-n);f.readFully(b);
            return new String(b,StandardCharsets.UTF_8).trim();
        } catch(Exception e) {return "";}
    }
    private void refresh() {
        if(!resumed || reading) return;reading=true;
        io.execute(()->{
            List<Image> found=new ArrayList<>();
            for(String line:tail("/data/local/tmp/portmaster/images.tsv",131072).split("\n")) {
                String[] v=line.split("\t",-1);
                if(v.length==6 && v[0].matches("[0-9a-f]{64}")) found.add(new Image(v));
            }
            String boot=tail("/data/local/tmp/portmaster/boot.log",8192);
            String optimization=tail("/data/local/tmp/portmaster/optimize.log",8192);
            String ver=tail("/data/local/tmp/portmaster/version.txt",1024);
            long used=number(tail("/data/local/tmp/portmaster/overlay-bytes",64));
            handler.post(()->{
                reading=false;if(isDestroyed())return;
                images.clear();images.addAll(found);log=boot;optLog=optimization;version=ver;overlays=used;
                ready=isReady();available="1".equals(get(P+"image"));operation=get(P+"operation");state=get(P+"session");resolved=get(P+"resolved");check=get(P+"resolve_state");
                busy=!operation.isEmpty() || "running".equals(state);
                if(pending && (busy || !requestSeq.equals(get(P+"done_seq")))) pending=false;
                String completed=get(P+"done_seq");
                if(!requestSeq.isEmpty() && !requestSeq.equals(completed)) {
                    requestSeq=completed;
                    String result=get(P+"result");
                    if(!"done".equals(result) && !"0".equals(result)) {page="health";toast(R.string.asl_failed);show();}
                }
                if(pending && SystemClock.elapsedRealtime()-requestedAt>30000) {pending=false;toast(R.string.asl_failed);}
                updateLauncher(this);
                String next=busy||pending?"busy":!available?"missing":!ready?"setup":"dashboard";
                if(!route.equals(next)) {route=next;show();}
                updateLive();
                if(resumed) handler.postDelayed(poll,1000);
            });
        });
    }
    private Image selected() {
        String wanted=get(SELECT);
        for(Image image:images) if(image.id.equals(resolved)||image.id.equals(wanted)) return image;
        return images.isEmpty()?null:images.get(0);
    }
    private String size(long value) {return Formatter.formatFileSize(this,value);}
    private String info() {
        Image image=selected();if(image==null)return getString(R.string.asl_empty);
        return getString(R.string.asl_info,image.name,image.path,size(image.original),size(image.optimized),size(overlays),version.isEmpty()?image.name:version);
    }
    private void updateLive() {
        if(live==null)return;
        if("busy".equals(route)) {
            String phase=get(P+"opt_state");
            live.setText("optimize".equals(operation)?getString(R.string.asl_op_progress,phase,size(number(get(P+"opt_bytes"))),get(P+"opt_elapsed")):log);
        } else if("health".equals(page)) live.setText(getString(R.string.asl_health_body,state,check,operation,log+"\n"+get(P+"opt_error")+"\n"+get(P+"select_error")+"\n"+optLog));
        else live.setText(info());
    }
    private void show() {
        side.removeAllViews();extra.removeAllViews();live=null;
        progress.setVisibility(View.GONE);
        String shown=page.isEmpty()?route:page;
        label.setText(R.string.subsystem_title);
        if("busy".equals(route)) shown="busy";
        switch(shown) {
        case "busy":
            title.setText(R.string.asl_working);body.setText(R.string.asl_hotkey_help);
            progress.setVisibility(View.VISIBLE);progress.setIndeterminate(true);
            live=text(extra,"");
            if("running".equals(state)&&!"1".equals(get(P+"provisioning"))) button(R.string.asl_stop,()->request("stop"));
            break;
        case "missing": case "":
            title.setText(R.string.asl_install);body.setText(R.string.asl_missing);
            button(R.string.asl_scan,()->request("scan"));button(R.string.asl_images,()->open("images"));button(R.string.asl_health,()->open("health"));button(R.string.asl_advanced,this::advanced);break;
        case "setup":
            title.setText(R.string.asl_start_setup);body.setText(R.string.asl_setup_body);live=text(extra,"");
            button(R.string.asl_start_setup,()->launch("SETUP",false));
            button(R.string.asl_images,()->open("images"));button(R.string.asl_advanced,this::advanced);break;
        case "images":
            title.setText(R.string.asl_images);body.setText(R.string.asl_change);
            for(Image image:images) button(image.name+"\n"+image.path,()->select(image));
            button(R.string.asl_scan,()->request("scan"));break;
        case "optimize":
            title.setText(R.string.asl_optimize);body.setText(R.string.asl_optimize_body);live=text(extra,"");
            CheckBox keep=check(R.string.asl_keep_original,false,null);
            Image image=selected();
            Button optimize=button(R.string.asl_optimize,()->{
                Image current=selected();if(current==null)return;
                put(P+"opt_source",current.id);put(P+"opt_keep",keep.isChecked()?"1":"0");
                put(P+"opt_request_id",UUID.randomUUID().toString().replace("-","").substring(0,16));request("optimize");
            });
            optimize.setEnabled(image!=null&&image.original>0&&image.optimized==0);break;
        case "config":
            title.setText(R.string.asl_config);body.setText(R.string.asl_hotkey_help);
            toggle(R.string.asl_launch_icon,ICON,false);toggle(R.string.asl_mute,"persist.andr36oid.subsystem.mute",false);
            toggle(R.string.asl_hotkeys,"persist.andr36oid.subsystem.hotkeys",true);
            choose(R.string.asl_retries,"persist.andr36oid.subsystem.retries",new String[]{"1","2","3","4","5"},"3");
            choose(R.string.asl_audio_rate,"persist.andr36oid.subsystem.rate",new String[]{"44100","48000"},"44100");
            choose(R.string.asl_audio_buffer,"persist.andr36oid.subsystem.buffer",new String[]{"2048","4096","8192"},"4096");
            button(R.string.asl_sync,()->open("sync"));button(R.string.asl_advanced,this::advanced);break;
        case "sync":
            title.setText(R.string.asl_sync);body.setText(R.string.asl_sync_note);
            toggle(R.string.asl_sync,"persist.andr36oid.subsystem_autosync",true);
            String[] fields={"favorite","playcount","lastplayed","desc","art","retroarch"};
            int[] labels={R.string.asl_sync_favorite,R.string.asl_sync_playcount,R.string.asl_sync_lastplayed,R.string.asl_sync_desc,R.string.asl_sync_art,R.string.asl_sync_ra};
            for(int i=0;i<fields.length;i++)toggle(labels[i],"persist.andr36oid.sync."+fields[i],i!=5);break;
        case "health":
            title.setText(R.string.asl_health);body.setText(R.string.asl_native_help);live=text(extra,"");break;
        case "storage":
            title.setText(R.string.asl_storage);body.setText(R.string.asl_shared);live=text(extra,"");
            button(R.string.asl_optimize,()->open("optimize"));break;
        default:
            title.setText(R.string.asl_dashboard);body.setText(R.string.asl_ready);live=text(extra,"");
            button(R.string.subsystem_open_es,()->launch("ES",true));button(R.string.asl_ra,()->launch("RA",true));
            button(R.string.asl_images,()->open("images"));button(R.string.asl_storage,()->open("storage"));
            button(R.string.asl_optimize,()->open("optimize"));button(R.string.asl_config,()->open("config"));
            button(R.string.asl_health,()->open("health"));break;
        }
        updateLive();
        if(side.getChildCount()>0)side.getChildAt(0).requestFocus();
    }
    private void open(String next) {page=next;show();}
    private void advanced() {startActivity(new Intent(this,AdvancedSetupActivity.class));}
    private void select(Image image) {
        CheckBox keep=new CheckBox(this);keep.setText(R.string.asl_keep_overlay);
        new AlertDialog.Builder(this).setTitle(image.name).setMessage(R.string.asl_change).setView(keep)
            .setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,(d,w)->{
                put(P+"select_id",image.id);put(P+"select_keep",keep.isChecked()?"1":"0");page="";request("select");
            }).show();
    }
    private void launch(String target,boolean confirm) {
        if(confirm) {new AlertDialog.Builder(this).setTitle(R.string.subsystem_launch).setMessage(R.string.asl_launch_confirm)
            .setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,(d,w)->launch(target,false)).show();return;}
        put(SubsystemBackend.ROOTFS,"");put(SubsystemBackend.HANDOFF,"SETUP".equals(target)?"0":"1");put(SubsystemBackend.PORT,target);page="";request("run");
    }
    private void request(String verb) {
        try {requestSeq=get(P+"done_seq");put(SubsystemBackend.REQUEST,verb);if(!"scan".equals(verb)){pending=true;requestedAt=SystemClock.elapsedRealtime();route="busy";show();}}
        catch(RuntimeException e){toast(R.string.asl_failed);}
    }
    private void toast(int id){Toast.makeText(this,id,Toast.LENGTH_LONG).show();}
    private TextView text(LinearLayout parent,String value){TextView v=new TextView(this);v.setTextSize(14);v.setText(value);parent.addView(v);return v;}
    private Button button(int label,Runnable action){return button(getString(label),action);}
    private Button button(String label,Runnable action){
        Button v=new Button(this,null,0,R.style.Andr36oidSetupButton_Secondary);v.setAllCaps(false);v.setText(label);v.setMinHeight(dp(48));v.setTextSize(14);
        v.setForeground(getDrawable(R.drawable.andr36oid_setup_focus));v.setOnClickListener(x->{try{action.run();}catch(RuntimeException e){toast(R.string.asl_failed);}});
        side.addView(v,new LinearLayout.LayoutParams(-1,-2));return v;
    }
    private CheckBox check(int label,boolean checked,java.util.function.Consumer<Boolean> changed){
        CheckBox v=new CheckBox(this);v.setText(label);v.setTextSize(14);v.setMinHeight(dp(48));v.setChecked(checked);v.setForeground(getDrawable(R.drawable.andr36oid_setup_focus));
        if(changed!=null)v.setOnCheckedChangeListener((b,on)->changed.accept(on));side.addView(v);return v;
    }
    private void choose(int label,String property,String[] values,String defaultValue) {
        String current=get(property);if(current.isEmpty())current=defaultValue;
        final String selected=current;
        button(getString(label)+": "+current,()->{
            int index=java.util.Arrays.asList(values).indexOf(selected);
            new AlertDialog.Builder(this).setTitle(label).setSingleChoiceItems(values,index,(dialog,which)->{
                put(property,values[which]);dialog.dismiss();show();
            }).setNegativeButton(android.R.string.cancel,null).show();
        });
    }
    private void toggle(int label,String key,boolean defaultOn){String current=get(key);check(label,current.isEmpty()?defaultOn:"1".equals(current),on->{put(key,on?"1":"0");updateLauncher(this);});}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
