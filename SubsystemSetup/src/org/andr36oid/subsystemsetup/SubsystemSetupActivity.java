package org.andr36oid.subsystemsetup;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.andr36oid.hints.HintBar;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Stable controller-first screens. Only the broker decides whether Linux is ready. */
public class SubsystemSetupActivity extends Activity {
    static final String P="sys.andr36oid.subsystem.";
    static final String SELECT="persist.sys.andr36oid.subsystem.selected";
    static final String ICON="persist.andr36oid.subsystem.es_icon";
    static final String EXTRA_LAUNCH="launch";
    static final String EXTRA_PORT_LAUNCH="port_launch";
    /** One-shot token proving an in-process entry (not a third-party app) sent the launch. */
    static final String EXTRA_LAUNCH_TOKEN="launch_token";
    private static volatile String sLaunchToken;
    static String mintLaunchToken(){String t=Long.toHexString(new java.security.SecureRandom().nextLong());sLaunchToken=t;return t;}
    private static boolean consumeLaunchToken(String t){String want=sLaunchToken;sLaunchToken=null;return want!=null&&want.equals(t);}
    private static final int SAVE_LOG=41;
    private enum Screen { SPLASH, HOME, SETUP, PROGRESS, LOGS, SETTINGS, IMAGES, OPTIMIZE, SYNC }
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Runnable poll=this::refresh;
    private final EnumMap<Screen,Page> pages=new EnumMap<>(Screen.class);
    private final ArrayDeque<Screen> history=new ArrayDeque<>();
    private final Map<String,Row> imageRows=new LinkedHashMap<>();
    private final Map<String,Row> archiveRows=new LinkedHashMap<>();
    private final List<Setting> settings=new ArrayList<>();
    private final List<Image> images=new ArrayList<>();
    private final List<SubsystemBackend.Archive> archives=new ArrayList<>();
    private SubsystemBackend backend;
    private FrameLayout host;
    private HintBar hints;
    private TextView statusText,statusDot,splashMessage,setupStep,setupTitle,setupBody,setupInfo;
    private TextView progressTitle,progressPhase,progressDescription,progressDetail,progressElapsed,progressNote,logStatus,logText,optimizeInfo,optimizeResult,imagesEmpty;
    private final OptimizeProgress optimizeProgress=new OptimizeProgress();
    private ProgressBar workBar;
    private ScrollView logScroll;
    private LinearLayout imageList,splashActions;
    private Row playRow,optimizeRow,imagesRow,setupContinue,keepRow,optimizeAction,recoverRow;
    private Screen screen,restoreScreen;
    private Snapshot snapshot=new Snapshot(),cachedSnapshot;
    private String log="",optLog="",version="",latestLog="",exportLog="",launchTarget="";
    private String scanBaseline="",observedDone="",requestDone="",pendingVerb="",deferredRequest="";
    private long overlays,scanAt,requestedAt;
    private int setupIndex;
    private boolean portLaunch;
    private boolean resumed,reading,initialResolved,scanWaiting,scanAttempted,pending,wasBusy,wasCleanupFailed,keepOriginal,tailOptimization,followLog=true;

    static final class Image {
        final String id,name,path; final long original,optimized;
        Image(String[] v) {id=v[0];name=v[1];path=v[2];original=number(v[3]);optimized=number(v[4]);}
    }
    /** Read property state off the UI thread, including the resolver's publication guard. */
    private static final class Snapshot {
        final Map<String,String> values=new HashMap<>();
        boolean stable;
        Snapshot() { }
        String value(String key) {String v=values.get(key);return v==null?"":v;}
        boolean ready() {return "1".equals(value("ready"))&&"1".equals(value("image"));}
        boolean available() {return "1".equals(value("image"));}
        boolean busy() {String op=value("operation");return (!op.isEmpty()&&!"scan".equals(op))||"running".equals(value("session"));}
        boolean working() {String request=value("request");return busy()||(!request.isEmpty()&&!"scan".equals(request));}
        boolean settled() {
            if(!stable||number(value("resolve_seq"))<=0||!value("resolve_seq").equals(value("resolve_guard")))return false;
            switch(value("resolve_state")) {
                case "ready_plain":case "ready_erofs":case "fallback_plain":
                    return available()&&!value("resolved").isEmpty();
                case "no_image":case "selected_missing":case "invalid_image":return !available();
                default:return false;
            }
        }
        static Snapshot read() {
            Snapshot s=new Snapshot();String beforeOperation=get(P+"operation"),guard=get(P+"resolve_guard");
            for(String key:new String[]{"ready","image","operation","request","session","resolved","resolve_state","resolve_seq",
                    "done_seq","result","provisioning","opt_state","opt_bytes","opt_total","opt_elapsed",
                    "opt_free","opt_available","opt_required","opt_job","launch_error","opt_error","select_error"}) s.values.put(key,get(P+key));
            s.values.put("resolve_guard",guard);
            s.stable=guard.equals(get(P+"resolve_guard"))&&!guard.endsWith(":writing")&&
                    beforeOperation.equals(s.value("operation"))&&beforeOperation.equals(get(P+"operation"));
            return s;
        }
    }
    static long number(String s) {try{return Long.parseLong(s.trim());}catch(RuntimeException e){return 0;}}
    static String get(String key) {return SubsystemBackend.get(key);}
    static void put(String key,String value) {if(!SubsystemBackend.set(key,value))throw new IllegalStateException(key);}
    static boolean isReady() {return "1".equals(get(P+"ready"))&&"1".equals(get(P+"image"));}
    static void updateLauncher(Activity context) {updateLauncher((android.content.Context)context);}
    static void updateLauncher(android.content.Context context) {
        boolean visible=isReady()&&"1".equals(get(ICON));
        ComponentName alias=new ComponentName(context,"org.andr36oid.subsystemsetup.EmulationStationLauncher");
        int wanted=visible?PackageManager.COMPONENT_ENABLED_STATE_ENABLED:PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        PackageManager pm=context.getPackageManager();
        if(pm.getComponentEnabledSetting(alias)!=wanted)pm.setComponentEnabledSetting(alias,wanted,PackageManager.DONT_KILL_APP);
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.subsystem_shell);
        backend=new SubsystemBackend(this);
        hints=HintBar.attachDefault(this);
        host=findViewById(R.id.screen_host);statusText=findViewById(R.id.status_text);statusDot=findViewById(R.id.status_dot);
        if(saved!=null) {
            try {restoreScreen=Screen.valueOf(saved.getString("screen","HOME"));}catch(IllegalArgumentException ignored) { }
            setupIndex=saved.getInt("setup_step",0);keepOriginal=saved.getBoolean("keep_original");
            pending=saved.getBoolean("pending");pendingVerb=saved.getString("pending_verb","");
            deferredRequest=saved.getString("deferred_request","");
            requestedAt=saved.getLong("requested_at");requestDone=saved.getString("request_done","");
            launchTarget=saved.getString("launch_target","");portLaunch=saved.getBoolean("port_launch");
            exportLog=saved.getString("export_log","");
        } else {
            // The activity is exported only for Settings' IA_SETTINGS injection, which
            // carries no extras. A launch spec reaches the broker-privileged handoff, so
            // honor it only when it carries a one-shot token minted by an in-process
            // entry (LaunchPortActivity/EmulationStationActivity/RetroArchActivity). A
            // third-party app cannot read our process memory to forge the token, so it
            // cannot trigger a handoff. getLaunchedFromUid() does not exist on API 30.
            String launch=getIntent().getStringExtra(EXTRA_LAUNCH);
            boolean port=getIntent().getBooleanExtra(EXTRA_PORT_LAUNCH,false);
            if(launch!=null&&!consumeLaunchToken(getIntent().getStringExtra(EXTRA_LAUNCH_TOKEN))){launch=null;port=false;}
            launchTarget=launch==null?"":launch;
            portLaunch=port;
        }
        observedDone=get(P+"done_seq");
        // Construct all screens exactly once. No route rebuilds in the polling path.
        buildSplash();buildHome();buildSetup();buildProgress();buildLogs();buildSettings();buildImages();buildOptimize();buildSync();
        switchScreen(Screen.SPLASH);
        // An active operation owns the storage lock, so do not issue a competing scan.
        Snapshot opening=Snapshot.read();
        if(opening.stable)applySnapshot(opening,new ArrayList<>(),"","","",0);
        if(saved!=null&&!deferredRequest.isEmpty())postBrokerRequest(deferredRequest);
        // A settled resolver already describes the correct page. Refresh its
        // catalog in the background; direct handoffs have already entered Progress.
        if(!isBusy())handler.post(this::requestScan);
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("screen",screen.name());out.putInt("setup_step",setupIndex);out.putBoolean("keep_original",keepOriginal);
        out.putBoolean("pending",pending);out.putString("pending_verb",pendingVerb);out.putString("deferred_request",deferredRequest);out.putLong("requested_at",requestedAt);
        out.putString("request_done",requestDone);out.putString("launch_target",launchTarget);out.putBoolean("port_launch",portLaunch);
        out.putString("export_log",exportLog);
    }
    @Override protected void onResume() {super.onResume();resumed=true;handler.removeCallbacks(poll);handler.post(poll);}
    @Override protected void onPause() {resumed=false;handler.removeCallbacks(poll);super.onPause();}
    @Override protected void onDestroy() {handler.removeCallbacksAndMessages(null);io.shutdownNow();super.onDestroy();}
    @Override public void onBackPressed() {
        if(screen==Screen.SETUP&&setupIndex>0) {setupIndex--;updateSetup();return;}
        if(!history.isEmpty()) {
            Screen previous=history.pop();
            if(previous==Screen.PROGRESS&&!isBusy())previous=idleScreen();
            switchScreen(previous);return;
        }
        if(screen!=Screen.HOME&&screen!=Screen.PROGRESS&&screen!=Screen.SPLASH) {
            switchScreen(initialResolved?Screen.HOME:Screen.SPLASH);return;
        }
        super.onBackPressed();
    }
    static String tail(String path,int bytes) {
        try(RandomAccessFile f=new RandomAccessFile(path,"r")) {
            int n=(int)Math.min(f.length(),bytes);byte[] b=new byte[n];f.seek(f.length()-n);f.readFully(b);
            return new String(b,StandardCharsets.UTF_8).trim();
        }catch(Exception e){return "";}
    }
    /** The most recent non-blank line of the broker boot log, for the progress screen. */
    private String lastLogLine() {
        if(log.isEmpty())return "";
        String[] lines=log.split("\n");
        for(int i=lines.length-1;i>=0;i--) {String line=lines[i].trim();if(!line.isEmpty())return line;}
        return "";
    }
    private void refresh() {
        if(!resumed||reading)return;
        reading=true;
        io.execute(()->{
            List<Image> found=new ArrayList<>();
            String listing=tail("/data/local/tmp/portmaster/images.tsv",131072);
            for(String line:listing.split("\n")) {
                String[] v=line.split("\t",-1);
                if(v.length==6&&v[0].matches("[0-9a-f]{64}"))found.add(new Image(v));
            }
            String boot=tail("/data/local/tmp/portmaster/boot.log",65536);
            String optimization=tail("/data/local/tmp/portmaster/optimize.log",65536);
            String ver=tail("/data/local/tmp/portmaster/version.txt",1024);
            long used=number(tail("/data/local/tmp/portmaster/overlay-bytes",64));
            List<SubsystemBackend.Archive> foundArchives=backend.archives();
            Snapshot next=Snapshot.read();
            handler.post(()->{
                reading=false;if(isDestroyed())return;
                if(next.stable){archives.clear();archives.addAll(foundArchives);applySnapshot(next,found,boot,optimization,ver,used);}
                retryScanIfTimedOut();
                if(resumed)handler.postDelayed(poll,1000);
            });
        });
    }
    private void applySnapshot(Snapshot next,List<Image> found,String boot,String optimization,String ver,long used) {
        snapshot=next;images.clear();images.addAll(found);log=boot;optLog=optimization;version=ver;overlays=used;
        String done=snapshot.value("done_seq");
        boolean failed=false;
        if(!done.equals(observedDone)) {
            observedDone=done;
            String result=snapshot.value("result");
            failed=!result.isEmpty()&&!"done".equals(result)&&!"0".equals(result);
        }
        if(pending&&(snapshot.busy()||!requestDone.equals(done)))pending=false;
        if(pending&&SystemClock.elapsedRealtime()-requestedAt>30000) {pending=false;failed=true;}
        boolean cleanupFailed="cleanup_failed".equals(snapshot.value("session"));
        if(next.settled())cachedSnapshot=next;
        boolean scanFinished=scanWaiting&&next.settled()&&!scanBaseline.equals(next.value("resolve_seq"))&&
                !"scan".equals(next.value("operation"));
        if(scanFinished)scanWaiting=false;
        if(!initialResolved&&(snapshot.working()||pending||snapshot.settled()||failed||cleanupFailed)) {
            initialResolved=true;
            Screen destination=failed||cleanupFailed?Screen.LOGS:isBusy()?Screen.PROGRESS:idleScreen();
            // Resolve launcher intents before exposing Home. request() paints
            // Progress before posting the broker trigger to the next frame.
            if(!launchTarget.isEmpty()&&snapshot.ready()&&!isBusy()) {
                String target=launchTarget;launchTarget="";launch(target,false);
                destination=isBusy()?Screen.PROGRESS:Screen.LOGS;
            } else if(!isBusy()&&restoreScreen!=null&&restoreScreen!=Screen.SPLASH&&restoreScreen!=Screen.PROGRESS) {
                destination=restoreScreen==Screen.SETUP&&snapshot.ready()?Screen.HOME:restoreScreen;
            }
            if(screen==Screen.SPLASH)switchScreen(destination);
            restoreScreen=null;launchTarget="";portLaunch=false;
        }
        // No cached publication yet (or the opening operation ended before our
        // first poll). Only this case needs the neutral opening spinner.
        if(!initialResolved&&!scanAttempted&&!snapshot.working()&&!pending)requestScan();
        boolean busy=isBusy();
        if(initialResolved) {
            if(failed||(cleanupFailed&&!wasCleanupFailed)) {
                if(failed)toast(R.string.asl_failed);
                if(screen!=Screen.LOGS)open(Screen.LOGS);
            } else if(busy&&!wasBusy&&screen!=Screen.PROGRESS) {
                history.clear();switchScreen(Screen.PROGRESS);
            } else if(!busy&&wasBusy&&screen==Screen.PROGRESS) {
                history.clear();switchScreen(idleScreen());
            } else if(snapshot.ready()&&screen==Screen.SETUP) {
                history.clear();switchScreen(Screen.HOME);
            }
        }
        wasBusy=busy;wasCleanupFailed=cleanupFailed;
        updateLauncher(this);
        updateViews();
    }
    private boolean isBusy() {return pending||snapshot.working();}
    private Screen idleScreen() {return snapshot.ready()?Screen.HOME:Screen.SETUP;}
    private Image selected() {
        String wanted=get(SELECT),resolved=snapshot.value("resolved");
        for(Image image:images)if(image.id.equals(resolved))return image;
        for(Image image:images)if(image.id.equals(wanted))return image;
        return images.isEmpty()?null:images.get(0);
    }
    private String size(long value) {return Formatter.formatFileSize(this,value);}
    private String optBytes(long value) {return OptimizeProgress.bytes(value,Locale.getDefault(),getString(R.string.ss_unit_gb),getString(R.string.ss_unit_mb));}
    private String info() {
        Image image=selected();if(image==null)return getString(R.string.asl_empty);
        return getString(R.string.asl_info,image.name,image.path,size(image.original),size(image.optimized),size(overlays),version.isEmpty()?image.name:version);
    }
    private void updateViews() {
        int status=!initialResolved?R.string.ss_checking:isBusy()?R.string.ss_working:snapshot.ready()?R.string.asl_ready:R.string.ss_not_setup;
        setText(statusText,getString(status));
        setText(statusDot,initialResolved&&!snapshot.ready()&&!isBusy()?"○":"●");
        statusDot.setTextColor(color(android.R.attr.colorAccent));
        playRow.title(snapshot.ready()?R.string.ss_play:R.string.ss_setup);
        playRow.subtitle(getString(snapshot.ready()?R.string.ss_play_note:R.string.ss_setup_note));
        Image image=selected();
        boolean optimized=alreadyOptimized(),optFailed=optimizeFailed();
        String optResult=optimized?getString(R.string.ss_already_optimized):"";
        if(optFailed)optResult+=(optResult.isEmpty()?"":"\n")+optimizeFailure();
        optimizeRow.subtitle(optResult.isEmpty()?getString(R.string.ss_optimize_note):optResult.replace('\n',' '));
        optimizeRow.setEnabled((snapshot.available()||image!=null||optFailed)&&!isBusy());
        imagesRow.subtitle(image==null?getString(R.string.ss_images_note):image.name);
        updateSetup();updateProgress();updateImages();updateSettings();
        setText(optimizeInfo,info());
        keepRow.subtitle(getString(keepOriginal?R.string.ss_on:R.string.ss_off));keepRow.setSelected(keepOriginal);
        setText(optimizeResult,optResult);optimizeResult.setVisibility(optResult.isEmpty()?View.GONE:View.VISIBLE);
        optimizeAction.title(optimized?R.string.ss_already_optimized:optFailed?R.string.ss_retry_optimize:R.string.ss_optimize);
        optimizeAction.setEnabled(image!=null&&image.original>0&&!optimized&&!isBusy());
        keepRow.setEnabled(!optimized&&!isBusy());
        String diagnostics=getString(R.string.ss_diagnostics)+"\n"+getString(R.string.ss_diagnostic_values,
                snapshot.value("session"),snapshot.value("resolve_state"),snapshot.value("operation"),
                snapshot.value("launch_error"),snapshot.value("opt_error"),snapshot.value("select_error"))+
                "\n\n"+getString(R.string.ss_image_details)+"\n"+info();
        String boot="\n\n"+getString(R.string.ss_boot_section)+"\n"+(log.isEmpty()?getString(R.string.subsystem_log_waiting):log);
        String optimization="\n\n"+getString(R.string.ss_opt_section)+"\n"+optLog;
        // Keep the most recent operation's log last, including its completion,
        // rather than jumping to an older log as soon as the operation clears.
        String activeOp=pending?pendingVerb:snapshot.value("operation");
        if("optimize".equals(activeOp))tailOptimization=true;
        else if("run".equals(activeOp)||"running".equals(snapshot.value("session")))tailOptimization=false;
        latestLog=diagnostics+(tailOptimization?boot+optimization:optimization+boot);
        if(followLog||logText.getText().length()==0)updateLogText();
        updateLogStatus();
        showControl(recoverRow,"cleanup_failed".equals(snapshot.value("session")),logScroll);
    }
    private void open(Screen next) {if(screen==next)return;history.push(screen);switchScreen(next);}
    /** Visibility and focus change only on navigation or a real operation transition. */
    private void switchScreen(Screen next) {
        if(screen==next)return;
        Page old=pages.get(screen);
        if(old!=null) {View focus=getCurrentFocus();if(focus!=null&&isInside(focus,old.root))old.focus=focus;old.root.setVisibility(View.GONE);}
        screen=next;Page page=pages.get(next);page.root.setVisibility(View.VISIBLE);
        if(next==Screen.LOGS) {
            hints.set("A",getString(R.string.ss_copy_save),"B",getString(R.string.andr36oid_hint_back));
            hints.setLeading(getString(R.string.ss_log_keys));
            if(followLog)updateLogText();
        } else {
            hints.set("A",getString(R.string.andr36oid_hint_select),"B",getString(R.string.andr36oid_hint_back));
            hints.setLeading(null);
        }
        handler.post(()->{
            if(isDestroyed()||screen!=next)return;
            if(page.focus!=null&&page.focus.isShown()&&page.focus.isEnabled())page.focus.requestFocus();
            else focusFirst(page.root);
            if(next==Screen.LOGS&&followLog)logScroll.fullScroll(View.FOCUS_DOWN);
        });
    }
    private static boolean isInside(View view,View ancestor) {
        while(view!=null) {if(view==ancestor)return true;view=view.getParent() instanceof View?(View)view.getParent():null;}return false;
    }
    private boolean focusFirst(View view) {
        if(view.getVisibility()!=View.VISIBLE||!view.isEnabled())return false;
        if(view.isFocusable())return view.requestFocus();
        if(view instanceof ViewGroup) {ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(focusFirst(group.getChildAt(i)))return true;}
        return false;
    }
    private final class Page {
        final LinearLayout root,column;
        View focus;
        Page(Screen key,boolean scroll) {
            root=column();root.setVisibility(View.GONE);host.addView(root,new FrameLayout.LayoutParams(-1,-1));
            if(scroll) {
                ScrollView scroller=new ScrollView(SubsystemSetupActivity.this);scroller.setFillViewport(true);
                scroller.setClipToPadding(false);scroller.setFocusable(false);
                root.addView(scroller,new LinearLayout.LayoutParams(-1,-1));
                column=column();scroller.addView(column,new ScrollView.LayoutParams(-1,-2));
            } else column=root;
            pages.put(key,this);
        }
        void heading(int title,int note) {
            TextView heading=text(getString(title),22,true);heading.setAccessibilityHeading(true);
            column.addView(heading,lp(-1,-2,0,0,0,2));
            if(note!=0)column.addView(secondary(getString(note),12),lp(-1,-2,0,0,0,8));
        }
    }
    private void buildSplash() {
        Page p=new Page(Screen.SPLASH,false);p.column.setGravity(Gravity.CENTER);
        ProgressBar spinner=new ProgressBar(this);spinner.setIndeterminateTintList(accent());
        p.column.addView(spinner,new LinearLayout.LayoutParams(dp(32),dp(32)));
        splashMessage=secondary(getString(R.string.ss_phase_scan),14);splashMessage.setGravity(Gravity.CENTER);
        p.column.addView(splashMessage,lp(-1,-2,0,16,0,12));
        splashActions=column();splashActions.setVisibility(View.GONE);p.column.addView(splashActions,new LinearLayout.LayoutParams(-1,-2));
        row(splashActions,"↻",R.string.asl_scan,"",this::retryScan);
        row(splashActions,"≡",R.string.ss_logs,"",()->open(Screen.LOGS)).chevron();
    }
    private void buildHome() {
        Page p=new Page(Screen.HOME,true);
        playRow=row(p.column,"▶",R.string.ss_play,getString(R.string.ss_play_note),()->{
            if(!snapshot.ready()){setupIndex=0;updateSetup();open(Screen.SETUP);return;}
            showDialog(new AlertDialog.Builder(this).setTitle(R.string.ss_play)
                .setItems(new CharSequence[]{getString(R.string.subsystem_open_es),getString(R.string.asl_ra)},(d,w)->launch(w==0?"ES":"RA",true))
                .setNegativeButton(android.R.string.cancel,null));
        });
        optimizeRow=row(p.column,"↗",R.string.ss_optimize,getString(R.string.ss_optimize_note),()->open(Screen.OPTIMIZE)).chevron();
        imagesRow=row(p.column,"▣",R.string.ss_images,getString(R.string.ss_images_note),()->open(Screen.IMAGES)).chevron();
        row(p.column,"⚙",R.string.ss_settings,getString(R.string.ss_settings_note),()->open(Screen.SETTINGS)).chevron();
        row(p.column,"≡",R.string.ss_logs,getString(R.string.ss_logs_note),()->open(Screen.LOGS)).chevron();
    }
    private void buildSetup() {
        Page p=new Page(Screen.SETUP,true);
        setupStep=secondary("",12);setupStep.setTextColor(color(android.R.attr.colorAccent));
        p.column.addView(setupStep,lp(-1,-2,0,0,0,6));
        setupTitle=text("",22,true);setupTitle.setAccessibilityHeading(true);p.column.addView(setupTitle,lp(-1,-2,0,0,0,8));
        setupBody=secondary("",14);p.column.addView(setupBody,lp(-1,-2,0,0,0,8));
        setupInfo=secondary("",12);p.column.addView(setupInfo,lp(-1,-2,0,0,0,12));
        setupContinue=row(p.column,"→",R.string.subsystem_continue,"",()->{
            if(setupIndex<2){setupIndex++;updateSetup();pages.get(Screen.SETUP).focus=setupContinue;}
            else launch("SETUP",false);
        });
        row(p.column,"↻",R.string.asl_scan,getString(R.string.ss_images_note),this::retryScan);
        row(p.column,"▣",R.string.ss_images,getString(R.string.ss_images_note),()->open(Screen.IMAGES)).chevron();
        row(p.column,"⚙",R.string.asl_advanced,getString(R.string.ss_advanced_note),this::advanced).chevron();
        updateSetup();
    }
    private void updateSetup() {
        setText(setupStep,getString(R.string.subsystem_step_count,setupIndex+1,3));
        int title=setupIndex==0?R.string.subsystem_welcome:setupIndex==1?R.string.subsystem_image_title:R.string.ss_setup_review;
        int body=setupIndex==0?R.string.subsystem_intro:setupIndex==1?R.string.asl_missing:R.string.asl_setup_body;
        setText(setupTitle,getString(title));setText(setupBody,getString(body));
        setText(setupInfo,setupIndex==2?getString(R.string.asl_perf_note):
                setupIndex==1?(scanWaiting?getString(R.string.ss_phase_scan):selected()==null?getString(R.string.asl_empty):selected().name):getString(R.string.subsystem_step_ports_summary));
        setupContinue.title(setupIndex==2?R.string.asl_start_setup:setupIndex==1&&snapshot.available()?R.string.ss_setup_next:R.string.subsystem_continue);
        setupContinue.setEnabled((setupIndex==0||snapshot.available())&&!isBusy());
    }
    private void buildProgress() {
        Page p=new Page(Screen.PROGRESS,false);
        LinearLayout center=column();center.setGravity(Gravity.CENTER);
        p.column.addView(center,new LinearLayout.LayoutParams(-1,0,1));
        progressTitle=text(getString(R.string.ss_progress),24,true);progressTitle.setGravity(Gravity.CENTER);progressTitle.setAccessibilityHeading(true);
        center.addView(progressTitle,lp(-1,-2,0,0,0,14));
        progressPhase=secondary("",16);progressPhase.setGravity(Gravity.CENTER);progressPhase.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        center.addView(progressPhase,lp(-1,-2,0,0,0,12));
        progressDescription=secondary("",13);progressDescription.setGravity(Gravity.CENTER);progressDescription.setVisibility(View.GONE);
        center.addView(progressDescription,lp(-1,-2,0,0,0,12));
        workBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);workBar.setMax(100);workBar.setIndeterminate(true);
        workBar.setProgressTintList(accent());workBar.setIndeterminateTintList(accent());
        center.addView(workBar,lp(-1,dp(12),32,0,32,12));
        progressDetail=secondary("",13);progressDetail.setGravity(Gravity.CENTER);center.addView(progressDetail,lp(-1,-2,0,0,0,10));
        progressElapsed=secondary("",13);progressElapsed.setGravity(Gravity.CENTER);progressElapsed.setVisibility(View.GONE);
        center.addView(progressElapsed,lp(-1,-2,0,0,0,10));
        progressNote=secondary(getString(R.string.ss_progress_note),12);progressNote.setGravity(Gravity.CENTER);
        center.addView(progressNote,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER);p.column.addView(actions,new LinearLayout.LayoutParams(-1,-2));
        Button logs=button(R.string.ss_view_log,()->open(Screen.LOGS),true);actions.addView(logs,new LinearLayout.LayoutParams(0,dp(48),1));
    }
    private void updateProgress() {
        String op=pending?pendingVerb:snapshot.value("operation");
        if(op.isEmpty())op=snapshot.value("request");
        boolean optimize="optimize".equals(op),unpack="unpack".equals(op),running="running".equals(snapshot.value("session"));
        boolean handoff=!optimize&&!unpack&&("run".equals(op)||running)&&!"0".equals(get(SubsystemBackend.HANDOFF));
        setText(progressTitle,getString(optimize?R.string.ss_optimize:unpack?R.string.ss_extract:handoff?R.string.ss_progress:running?R.string.ss_running:"run".equals(op)&&"1".equals(snapshot.value("provisioning"))?R.string.subsystem_provisioning:R.string.ss_progress));
        progressDescription.setVisibility(optimize||unpack?View.VISIBLE:View.GONE);
        progressElapsed.setVisibility(optimize||unpack?View.VISIBLE:View.GONE);
        workBar.setVisibility(View.VISIBLE);
        if(unpack) {
            String phase=pending?"preparing":snapshot.value("opt_state");
            long done=pending?0:number(snapshot.value("opt_bytes")),total=pending?0:number(snapshot.value("opt_total"));
            long elapsed=pending?0:Math.max(0,number(snapshot.value("opt_elapsed")));
            int step=unpackStep(phase);
            if(phase.isEmpty())step=1;
            // Only the real end phases are terminal; an unrecognized phase stays neutral.
            boolean terminal="done".equals(phase)||"interrupted".equals(phase)||"failed".equals(phase);
            boolean determinate="extracting".equals(phase)&&total>0;
            if(workBar.isIndeterminate()==determinate)workBar.setIndeterminate(!determinate);
            if(determinate)workBar.setProgress(OptimizeProgress.percent(done,total));
            String detail="";
            if("extracting".equals(phase)&&total>0)detail=getString(R.string.ss_opt_bytes,OptimizeProgress.percent(done,total),
                    optBytes(done),optBytes(total));
            if(terminal) {
                workBar.setVisibility(View.GONE);
                boolean success="done".equals(phase),interrupted="interrupted".equals(phase);
                setText(progressPhase,getString(success?R.string.ss_extract_done:interrupted?R.string.ss_opt_interrupted:R.string.ss_opt_failed_title));
                setText(progressDescription,success?getString(R.string.ss_extract_done_description):interrupted?
                        getString(R.string.subsystem_optimize_interrupted):optimizeFailure());
                if(!success&&!snapshot.value("opt_job").isEmpty())detail=getString(R.string.ss_opt_job,snapshot.value("opt_job"));
            } else if(step>0) {
                int[] names={R.string.ss_opt_preparing,R.string.ss_extract_extracting,R.string.ss_opt_verifying,R.string.ss_opt_saving};
                int[] descriptions={R.string.ss_extract_preparing_description,R.string.ss_extract_extracting_description,
                        R.string.ss_opt_verifying_description,R.string.ss_extract_saving_description};
                setText(progressPhase,getString(R.string.ss_opt_step,step,getString(names[step-1])));
                setText(progressDescription,getString(descriptions[step-1]));
            } else {
                // Unrecognized, non-terminal phase: keep a neutral working state.
                setText(progressPhase,getString(R.string.ss_working));
                progressDescription.setVisibility(View.GONE);
            }
            setText(progressDetail,detail);progressDetail.setVisibility(detail.isEmpty()?View.GONE:View.VISIBLE);
            progressDetail.setTextColor(color(android.R.attr.textColorSecondary));
            setText(progressElapsed,getString(R.string.ss_opt_elapsed,OptimizeProgress.elapsed(elapsed)));
        } else if(optimize) {
            String phase=pending?"preparing":snapshot.value("opt_state");
            long done=pending?0:number(snapshot.value("opt_bytes")),total=pending?0:number(snapshot.value("opt_total"));
            long elapsed=pending?0:Math.max(0,number(snapshot.value("opt_elapsed")));
            int step=OptimizeProgress.step(phase);
            // An empty state is possible while the service acknowledges a request.
            if(phase.isEmpty())step=1;
            // Only the real end phases are terminal; an unrecognized phase stays neutral.
            boolean terminal="done".equals(phase)||"interrupted".equals(phase)||"failed".equals(phase);
            boolean determinate="building".equals(phase)&&total>0;
            if(workBar.isIndeterminate()==determinate)workBar.setIndeterminate(!determinate);
            if(determinate)workBar.setProgress(OptimizeProgress.percent(done,total));
            // Feed every phase/poll, including building at zero bytes, so ETA
            // warms up from a local building baseline and resets on phase exit.
            long eta=optimizeProgress.remaining(snapshot.value("opt_job"),phase,done,total,elapsed);
            String detail="";
            if("building".equals(phase)) {
                String estimate;
                if(eta<0)estimate=getString(R.string.ss_opt_estimating);
                else if(eta<60)estimate=getString(R.string.ss_opt_eta_under_minute);
                else {long m=OptimizeProgress.minutes(eta);estimate=getResources().getQuantityString(R.plurals.ss_opt_eta_minutes,(int)m,m);}
                detail=determinate?getString(R.string.ss_opt_bytes,OptimizeProgress.percent(done,total),
                        optBytes(done),optBytes(total))+"\n"+estimate:estimate;
            }
            if(terminal) {
                workBar.setVisibility(View.GONE);
                boolean success="done".equals(phase),interrupted="interrupted".equals(phase);
                setText(progressPhase,getString(success?R.string.ss_opt_done:interrupted?R.string.ss_opt_interrupted:R.string.ss_opt_failed_title));
                setText(progressDescription,success?getString(R.string.ss_opt_done_description):interrupted?
                        getString(R.string.subsystem_optimize_interrupted):optimizeFailure());
                if(!success&&!snapshot.value("opt_job").isEmpty())detail=getString(R.string.ss_opt_job,snapshot.value("opt_job"));
            } else if(step>0) {
                int[] names={R.string.ss_opt_preparing,R.string.ss_opt_converting,R.string.ss_opt_verifying,R.string.ss_opt_saving};
                int[] descriptions={R.string.ss_opt_preparing_description,R.string.ss_opt_converting_description,
                        R.string.ss_opt_verifying_description,R.string.ss_opt_saving_description};
                setText(progressPhase,getString(R.string.ss_opt_step,step,getString(names[step-1])));
                setText(progressDescription,getString(descriptions[step-1]));
            } else {
                // Unrecognized, non-terminal phase: keep a neutral working state.
                setText(progressPhase,getString(R.string.ss_working));
                progressDescription.setVisibility(View.GONE);
            }
            setText(progressDetail,detail);progressDetail.setVisibility(detail.isEmpty()?View.GONE:View.VISIBLE);
            progressDetail.setTextColor(color(android.R.attr.textColorSecondary));
            setText(progressElapsed,getString(R.string.ss_opt_elapsed,OptimizeProgress.elapsed(elapsed)));
        } else {
            optimizeProgress.reset();
            progressDetail.setVisibility(View.VISIBLE);
            if(!workBar.isIndeterminate())workBar.setIndeterminate(true);
            int phase=handoff?R.string.ss_phase_run:pending?R.string.ss_phase_waiting:running?R.string.subsystem_session_running:
                    "select".equals(op)?R.string.ss_phase_select:"stop".equals(op)||"recover".equals(op)?R.string.ss_phase_stop:R.string.ss_phase_run;
            setText(progressPhase,getString(phase));
            // First-boot provisioning is one long silent step; show the live tail of
            // the broker log so it is clearly working and not hung.
            String last="1".equals(snapshot.value("provisioning"))?lastLogLine():"";
            if(!last.isEmpty()) {
                setText(progressDetail,last);
                progressDetail.setTextColor(color(android.R.attr.textColorSecondary));
            } else {
                setText(progressDetail,getString(handoff?R.string.ss_handoff_warning:running?R.string.asl_hotkey_help:R.string.ss_progress_note));
                progressDetail.setTextColor(color(handoff?android.R.attr.textColorPrimary:android.R.attr.textColorSecondary));
            }
        }
        setText(progressNote,getString(optimize?R.string.subsystem_optimize_time:unpack?R.string.ss_extract_note:R.string.asl_perf_note));
        if(optimize&&(pending||"preparing".equals(snapshot.value("opt_state")))) {
            String available=snapshot.value("opt_available");
            if(available.isEmpty())available=snapshot.value("opt_free");
            long required=number(snapshot.value("opt_required"));
            if(!pending&&!available.isEmpty()&&required>0)setText(progressNote,getString(R.string.ss_opt_space,
                    optBytes(number(available)),optBytes(required))+
                    "\n"+getString(R.string.subsystem_optimize_time));
        }
    }
    private void buildLogs() {
        Page p=new Page(Screen.LOGS,false);
        TextView heading=text(getString(R.string.ss_logs),20,true);heading.setAccessibilityHeading(true);
        p.column.addView(heading,lp(-1,-2,0,0,0,3));
        logStatus=secondary("",12);p.column.addView(logStatus,lp(-1,-2,0,0,0,8));
        logScroll=new ScrollView(this);logScroll.setFillViewport(true);logScroll.setFocusable(true);logScroll.setFocusableInTouchMode(true);
        logScroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        logScroll.setBackgroundColor(getColor(R.color.subsystem_console_background));
        logScroll.setForeground(getDrawable(R.drawable.subsystem_console_focus));
        logScroll.setPadding(dp(12),dp(8),dp(12),dp(8));logScroll.setContentDescription(getString(R.string.subsystem_boot_log));
        p.column.addView(logScroll,new LinearLayout.LayoutParams(-1,0,1));
        logText=text("",12,false);logText.setTypeface(Typeface.MONOSPACE);logText.setTextColor(getColor(R.color.subsystem_console_text));
        logText.setLineSpacing(0,1.12f);logScroll.addView(logText,new ScrollView.LayoutParams(-1,-2));
        logScroll.setOnClickListener(v->copySaveLog());
        logScroll.setOnFocusChangeListener((v,focused)->{if(focused&&screen==Screen.LOGS)setLogHints(false);});
        logScroll.setOnTouchListener((v,event)->{if(event.getActionMasked()==MotionEvent.ACTION_DOWN)setFollowLog(false);return false;});
        logScroll.setOnScrollChangeListener((v,x,y,oldX,oldY)->{
            if(!followLog&&logText.getHeight()>0&&y>=logText.getHeight()-logScroll.getHeight()+logScroll.getPaddingTop()+logScroll.getPaddingBottom())setFollowLog(true);
        });
        p.column.addView(secondary(getString(R.string.ss_log_tail_note),11),lp(-1,-2,0,5,0,0));
        recoverRow=row(p.column,"↻",R.string.asl_recover,getString(R.string.subsystem_cleanup_failed),()->request("stop"));
        recoverRow.setOnFocusChangeListener((v,focused)->{
            recoverRow.updateRail();if(focused&&screen==Screen.LOGS)setLogHints(true);
        });
        recoverRow.setVisibility(View.GONE);p.focus=logScroll;
    }
    private void updateLogText() {
        if(latestLog.contentEquals(logText.getText()))return;
        setText(logText,latestLog);
        if(screen==Screen.LOGS&&followLog)logScroll.post(()->{if(screen==Screen.LOGS&&followLog)logScroll.fullScroll(View.FOCUS_DOWN);});
    }
    private void updateLogStatus() {setText(logStatus,getString(R.string.ss_log_status,getString(R.string.ss_log_source),getString(followLog?R.string.ss_log_following:R.string.ss_log_paused)));}
    private void setLogHints(boolean recovery) {hints.set("A",getString(recovery?R.string.asl_recover:R.string.ss_copy_save),"B",getString(R.string.andr36oid_hint_back));}
    private void setFollowLog(boolean follow) {followLog=follow;updateLogStatus();if(follow){updateLogText();logScroll.post(()->logScroll.fullScroll(View.FOCUS_DOWN));}}
    private void pageLog(boolean down) {
        setFollowLog(false);int amount=Math.max(dp(32),logScroll.getHeight()-dp(32));
        logScroll.scrollBy(0,down?amount:-amount);
        if(down&&logScroll.getScrollY()>=logText.getHeight()-logScroll.getHeight()+logScroll.getPaddingTop()+logScroll.getPaddingBottom())setFollowLog(true);
    }
    private void copySaveLog() {
        exportLog=logText.getText().toString();
        showDialog(new AlertDialog.Builder(this).setTitle(R.string.ss_logs)
            .setItems(new CharSequence[]{getString(R.string.ss_copy),getString(R.string.ss_save)},(dialog,which)->{
                if(which==0) {
                    ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.ss_logs),exportLog));toast(R.string.ss_copied);
                } else {
                    Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain")
                        .putExtra(Intent.EXTRA_TITLE,"darkosen-log-"+System.currentTimeMillis()+".txt");
                    try{startActivityForResult(intent,SAVE_LOG);}catch(RuntimeException e){toast(R.string.ss_save_failed);}
                }
            }).setNegativeButton(android.R.string.cancel,null));
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=SAVE_LOG||resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        final android.net.Uri uri=data.getData();final String content=exportLog.isEmpty()?logText.getText().toString():exportLog;
        io.execute(()->{
            boolean saved=false;
            try(OutputStream output=getContentResolver().openOutputStream(uri,"wt")) {
                if(output!=null){output.write(content.getBytes(StandardCharsets.UTF_8));saved=true;}
            }catch(Exception ignored) { }
            final boolean success=saved;
            handler.post(()->{if(!isDestroyed())Toast.makeText(this,success?getString(R.string.ss_saved,uri.getLastPathSegment()):getString(R.string.ss_save_failed),Toast.LENGTH_LONG).show();});
        });
    }
    private void buildSettings() {
        Page p=new Page(Screen.SETTINGS,true);p.heading(R.string.ss_settings,R.string.ss_settings_note);
        toggle(p.column,R.string.asl_launch_icon,ICON,false);
        toggle(p.column,R.string.asl_mute,"persist.andr36oid.subsystem.mute",false);
        toggle(p.column,R.string.asl_daijishou_cores,SubsystemBackend.DAIJISHOU_CORES,false);
        toggle(p.column,R.string.asl_daijishou_tools,SubsystemBackend.DAIJISHOU_TOOLS,true);
        toggle(p.column,R.string.asl_hotkeys,"persist.andr36oid.subsystem.hotkeys",true);
        choose(p.column,R.string.asl_retries,"persist.andr36oid.subsystem.retries",new String[]{"1","2","3","4","5"},"3");
        choose(p.column,R.string.asl_audio_rate,"persist.andr36oid.subsystem.rate",new String[]{"44100","48000"},"44100");
        choose(p.column,R.string.asl_audio_buffer,"persist.andr36oid.subsystem.buffer",new String[]{"2048","4096","8192"},"4096");
        row(p.column,"⇄",R.string.asl_sync,getString(R.string.ss_sync_note),()->open(Screen.SYNC)).chevron();
        row(p.column,"⚙",R.string.asl_advanced,getString(R.string.ss_advanced_note),this::advanced).chevron();
    }
    private void buildSync() {
        Page p=new Page(Screen.SYNC,true);p.heading(R.string.asl_sync,R.string.ss_sync_note);
        p.column.addView(secondary(getString(R.string.asl_sync_note),13),lp(-1,-2,0,0,0,10));
        toggle(p.column,R.string.asl_sync,"persist.andr36oid.subsystem_autosync",true);
        String[] fields={"favorite","playcount","lastplayed","desc","art","retroarch"};
        int[] labels={R.string.asl_sync_favorite,R.string.asl_sync_playcount,R.string.asl_sync_lastplayed,R.string.asl_sync_desc,R.string.asl_sync_art,R.string.asl_sync_ra};
        for(int i=0;i<fields.length;i++)toggle(p.column,labels[i],"persist.andr36oid.sync."+fields[i],i!=5);
    }
    private final class Setting {
        final Row row;final String key,defaultValue;final boolean toggle;
        Setting(Row row,String key,String defaultValue,boolean toggle) {this.row=row;this.key=key;this.defaultValue=defaultValue;this.toggle=toggle;}
        String value(){String value=get(key);return value.isEmpty()?defaultValue:value;}
        void update(){
            String value=value();boolean on="1".equals(value);
            row.subtitle(toggle?getString(on?R.string.ss_on:R.string.ss_off):value);
            if(toggle) {
                setText(row.glyphView,on?"●":"○");
                row.glyphView.setTextColor(color(on?android.R.attr.colorAccent:android.R.attr.textColorSecondary));
                row.detail.setTextColor(color(on?android.R.attr.colorAccent:android.R.attr.textColorSecondary));
            }
        }
    }
    private void toggle(LinearLayout parent,int label,String key,boolean defaultOn) {
        Row row=row(parent,"○",label,"",()->{
            String current=get(key);boolean on=current.isEmpty()?defaultOn:"1".equals(current);
            put(key,on?"0":"1");updateLauncher(this);updateSettings();
        });
        settings.add(new Setting(row,key,defaultOn?"1":"0",true));
    }
    private void choose(LinearLayout parent,int label,String key,String[] values,String defaultValue) {
        Row row=row(parent,"≋",label,"",()->{
            String current=get(key);if(current.isEmpty())current=defaultValue;
            int index=Arrays.asList(values).indexOf(current);
            showDialog(new AlertDialog.Builder(this).setTitle(label).setSingleChoiceItems(values,index,(dialog,which)->{
                try{put(key,values[which]);updateSettings();}catch(RuntimeException e){toast(R.string.subsystem_setting_failed);}dialog.dismiss();
            }).setNegativeButton(android.R.string.cancel,null));
        });
        settings.add(new Setting(row,key,defaultValue,false));
    }
    private void updateSettings() {for(Setting setting:settings)setting.update();}
    private void buildImages() {
        Page p=new Page(Screen.IMAGES,true);p.heading(R.string.ss_images,R.string.ss_images_note);
        imagesEmpty=secondary(getString(R.string.asl_empty),14);p.column.addView(imagesEmpty,lp(-1,-2,0,0,0,12));
        imageList=column();p.column.addView(imageList,new LinearLayout.LayoutParams(-1,-2));
        row(p.column,"↻",R.string.asl_scan,getString(R.string.ss_images_note),this::retryScan);
        p.column.addView(secondary(getString(R.string.asl_change),12),lp(-1,-2,0,8,0,0));
    }
    /** Reconcile catalog membership only; existing image rows and their focus survive polls. */
    private void updateImages() {
        Map<String,Image> current=new LinkedHashMap<>();for(Image image:images)current.put(image.id,image);
        Iterator<Map.Entry<String,Row>> iterator=imageRows.entrySet().iterator();
        boolean lostFocus=false;
        while(iterator.hasNext()) {
            Map.Entry<String,Row> entry=iterator.next();
            if(!current.containsKey(entry.getKey())) {lostFocus|=entry.getValue().hasFocus();imageList.removeView(entry.getValue());iterator.remove();}
        }
        Image active=selected();int index=0;
        for(Image image:current.values()) {
            Row row=imageRows.get(image.id);
            if(row==null) {
                final String id=image.id;
                row=new Row("▣",image.name,"",()->{for(Image candidate:images)if(candidate.id.equals(id)){select(candidate);break;}});
                imageRows.put(id,row);imageList.addView(row,index,lp(-1,dp(52),0,0,0,4));
            }
            row.title(image.name);boolean selected=active!=null&&active.id.equals(image.id);row.setSelected(selected);
            String sizes=getString(R.string.ss_image_sizes,size(image.original),size(image.optimized));
            row.subtitle(selected?getString(R.string.ss_image_selected,sizes):getString(R.string.ss_image_summary,image.path,sizes));
            row.setEnabled(!isBusy());index++;
        }
        // Copied-but-unextracted release archives sit below any images in the same
        // picker; selecting one extracts it into darkosen.img (confirm first).
        Map<String,SubsystemBackend.Archive> currentArchives=new LinkedHashMap<>();
        for(SubsystemBackend.Archive archive:archives)currentArchives.put(archive.name,archive);
        Iterator<Map.Entry<String,Row>> archiveIterator=archiveRows.entrySet().iterator();
        while(archiveIterator.hasNext()) {
            Map.Entry<String,Row> entry=archiveIterator.next();
            if(!currentArchives.containsKey(entry.getKey())) {lostFocus|=entry.getValue().hasFocus();imageList.removeView(entry.getValue());archiveIterator.remove();}
        }
        for(SubsystemBackend.Archive archive:currentArchives.values()) {
            Row row=archiveRows.get(archive.name);
            if(row==null) {
                final SubsystemBackend.Archive pick=archive;
                row=new Row("⇩",archive.display,"",()->confirmExtract(pick));
                archiveRows.put(archive.name,row);imageList.addView(row,index,lp(-1,dp(52),0,0,0,4));
            }
            row.title(archive.display);row.subtitle(getString(R.string.ss_extract_summary,size(archive.size)));row.setEnabled(!isBusy());index++;
        }
        imagesEmpty.setVisibility(images.isEmpty()&&archives.isEmpty()?View.VISIBLE:View.GONE);
        if(lostFocus&&screen==Screen.IMAGES)focusFirst(pages.get(Screen.IMAGES).root);
    }
    private void confirmExtract(SubsystemBackend.Archive archive) {
        if(isBusy())return;
        showDialog(new AlertDialog.Builder(this).setTitle(archive.display)
            .setMessage(getString(R.string.ss_extract_confirm,size(archive.size)))
            .setPositiveButton(R.string.ss_extract,(dialog,which)->{
                put(P+"unp_source",archive.name);
                put(P+"unp_request_id",UUID.randomUUID().toString().replace("-","").substring(0,16));
                request("unpack");
            })
            .setNegativeButton(android.R.string.cancel,null));
    }
    private void buildOptimize() {
        Page p=new Page(Screen.OPTIMIZE,true);p.heading(R.string.ss_optimize,R.string.ss_optimize_note);
        p.column.addView(secondary(getString(R.string.asl_optimize_body),13),lp(-1,-2,0,0,0,10));
        optimizeResult=text("",16,true);optimizeResult.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        p.column.addView(optimizeResult,lp(-1,-2,0,0,0,12));
        optimizeInfo=secondary("",12);p.column.addView(optimizeInfo,lp(-1,-2,0,0,0,12));
        keepRow=row(p.column,"○",R.string.asl_keep_original,"",()->{keepOriginal=!keepOriginal;keepRow.setSelected(keepOriginal);keepRow.subtitle(getString(keepOriginal?R.string.ss_on:R.string.ss_off));});
        optimizeAction=row(p.column,"↗",R.string.ss_optimize,getString(R.string.subsystem_optimize_time),()->{
            Image image=selected();if(image==null||alreadyOptimized())return;
            put(P+"opt_source",image.id);put(P+"opt_keep",keepOriginal?"1":"0");
            put(P+"opt_request_id",UUID.randomUUID().toString().replace("-","").substring(0,16));request("optimize");
        });
        row(p.column,"≡",R.string.ss_view_log,getString(R.string.ss_logs_note),()->open(Screen.LOGS)).chevron();
    }
    private boolean alreadyOptimized() {
        Image image=selected();
        return "ready_erofs".equals(snapshot.value("resolve_state"))||(image!=null&&image.optimized>0);
    }
    private static int unpackStep(String phase) {
        switch(phase) {
            case "preparing":return 1;
            case "extracting":return 2;
            case "verifying":return 3;
            case "publishing":return 4;
            default:return 0;
        }
    }
    private boolean optimizeFailed() {return "failed".equals(snapshot.value("opt_state"))||!snapshot.value("opt_error").isEmpty();}
    private String optimizeFailure() {
        String error=snapshot.value("opt_error");
        int message;
        switch(error) {
            case "io_error":message=R.string.ss_opt_error_io;break;
            case "no_space":case "data_space":case "file_limit":message=R.string.ss_opt_error_space;break;
            case "no_card":case "no_image":case "selected_missing":message=R.string.ss_opt_error_missing;break;
            case "invalid_image":case "source_changed":message=R.string.ss_opt_error_source;break;
            case "interrupted":message=R.string.ss_opt_error_interrupted;break;
            case "verify_failed":message=R.string.ss_opt_error_verify;break;
            case "mkfs_failed":case "tool_missing":message=R.string.ss_opt_error_tool;break;
            case "output_exists":message=R.string.ss_opt_error_output;break;
            case "power_hold":message=R.string.ss_opt_error_power;break;
            case "source_remove_failed":message=R.string.ss_opt_error_remove;break;
            case "name_too_long":message=R.string.ss_opt_error_name;break;
            case "already_optimized":message=R.string.ss_already_optimized;break;
            case "invalid_archive":case "no_image_inside":message=R.string.ss_extract_error_archive;break;
            case "incomplete_archive":message=R.string.ss_extract_error_incomplete;break;
            case "extract_failed":message=R.string.ss_extract_error_failed;break;
            default:message=R.string.ss_opt_error_unknown;
        }
        return getString(R.string.ss_opt_failed,getString(message),error.isEmpty()?"unknown":error);
    }
    private void advanced() {startActivity(new Intent(this,AdvancedSetupActivity.class));}
    private void select(Image image) {
        CheckBox keep=new CheckBox(this);keep.setText(R.string.asl_keep_overlay);
        showDialog(new AlertDialog.Builder(this).setTitle(image.name).setMessage(R.string.asl_change).setView(keep)
            .setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,(d,w)->{
                try{put(P+"select_id",image.id);put(P+"select_keep",keep.isChecked()?"1":"0");request("select");}catch(RuntimeException e){toast(R.string.asl_failed);}
            }));
    }
    private void launch(String target,boolean confirm) {
        if(confirm) {
            showDialog(new AlertDialog.Builder(this).setTitle(R.string.subsystem_launch)
                .setMessage(getString(R.string.ss_handoff_warning)+"\n\n"+getString(R.string.asl_launch_confirm)+"\n\n"+getString(R.string.asl_perf_note))
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(android.R.string.ok,(d,w)->launch(target,false)));return;
        }
        try {
            String queued=target;
            if(!portLaunch&&"RACORE".equals(target))queued=SubsystemBackend.retroArchRequest(this,getIntent().getStringExtra("CORE"),getIntent().getStringExtra("ROM"));
            put(SubsystemBackend.ROOTFS,"");put(SubsystemBackend.HANDOFF,!portLaunch&&"SETUP".equals(target)?"0":"1");put(SubsystemBackend.PORT,queued);
            request("run");portLaunch=false;
        }catch(java.io.IOException|RuntimeException e){toast(R.string.subsystem_launch_failed);}
    }
    private void retryScan() {scanWaiting=false;requestScan();}
    private void requestScan() {
        if(isBusy()||scanWaiting)return;
        scanAttempted=true;
        scanBaseline=get(P+"resolve_seq");scanAt=SystemClock.elapsedRealtime();
        try {put(SubsystemBackend.REQUEST,"scan");scanWaiting=true;setText(splashMessage,getString(R.string.ss_phase_scan));splashActions.setVisibility(View.GONE);}
        catch(RuntimeException e){scanWaiting=true;setText(splashMessage,getString(R.string.ss_loading_retry));splashActions.setVisibility(View.VISIBLE);}
    }
    private void retryScanIfTimedOut() {
        if(!scanWaiting||isBusy()||SystemClock.elapsedRealtime()-scanAt<=30000)return;
        scanWaiting=false;
        if(cachedSnapshot!=null&&!snapshot.settled()&&!cachedSnapshot.working()) {
            applySnapshot(cachedSnapshot,new ArrayList<>(images),log,optLog,version,overlays);
        }
        // Keep the cached screen usable. With no publication, keep checking and
        // expose Logs / Retry while automatically reissuing the scan.
        requestScan();
        setText(splashMessage,getString(R.string.ss_loading_retry));splashActions.setVisibility(View.VISIBLE);
    }
    private void request(String verb) {
        if(pending)return;
        requestDone=get(P+"done_seq");
        pending=true;pendingVerb=verb;requestedAt=SystemClock.elapsedRealtime();
        initialResolved=true;wasBusy=true;history.clear();updateProgress();switchScreen(Screen.PROGRESS);
        deferredRequest=verb;
        postBrokerRequest(verb);
    }
    private void postBrokerRequest(String verb) {
        // Let the branded screen and handoff warning draw before waking init.
        // Preserve this small pre-trigger interval across activity recreation.
        host.postOnAnimation(()->handler.post(()->sendBrokerRequest(verb)));
    }
    private void sendBrokerRequest(String verb) {
        if(isDestroyed()||!pending||!verb.equals(deferredRequest))return;
        // A background scan owns the broker's storage lock. Keep Progress on
        // screen until it releases the lock, then send the original trigger.
        if(("run".equals(verb)||"optimize".equals(verb)||"select".equals(verb)||"unpack".equals(verb))&&
                ("scan".equals(get(P+"operation"))||"scan".equals(get(P+"request")))) {
            handler.postDelayed(()->sendBrokerRequest(verb),250);return;
        }
        try{put(SubsystemBackend.REQUEST,verb);}
        catch(RuntimeException e){pending=false;wasBusy=false;toast(R.string.asl_failed);open(Screen.LOGS);}
        finally{deferredRequest="";}
    }
    /** Dialog windows do not dispatch through Activity's gamepad key handler. */
    private void showDialog(AlertDialog.Builder builder) {
        AlertDialog dialog=builder.create();
        dialog.setOnKeyListener((d,key,event)->{
            if(key==KeyEvent.KEYCODE_BUTTON_B) {if(event.getAction()==KeyEvent.ACTION_UP)dialog.cancel();return true;}
            if(key!=KeyEvent.KEYCODE_BUTTON_A)return false;
            dialog.dispatchKeyEvent(new KeyEvent(event.getDownTime(),event.getEventTime(),
                    event.getAction(),KeyEvent.KEYCODE_DPAD_CENTER,event.getRepeatCount(),event.getMetaState(),
                    event.getDeviceId(),event.getScanCode(),event.getFlags(),event.getSource()));
            return true;
        });
        dialog.show();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key=event.getKeyCode();boolean up=event.getAction()==KeyEvent.ACTION_UP;
        if(key==KeyEvent.KEYCODE_BUTTON_B) {if(up)onBackPressed();return true;}
        if(screen==Screen.LOGS) {
            boolean pageUp=key==KeyEvent.KEYCODE_BUTTON_L1||key==KeyEvent.KEYCODE_BUTTON_L2||key==KeyEvent.KEYCODE_PAGE_UP||key==KeyEvent.KEYCODE_DPAD_UP;
            boolean pageDown=key==KeyEvent.KEYCODE_BUTTON_R1||key==KeyEvent.KEYCODE_BUTTON_R2||key==KeyEvent.KEYCODE_PAGE_DOWN||key==KeyEvent.KEYCODE_DPAD_DOWN;
            if(pageUp||pageDown) {if(!up)pageLog(pageDown);return true;}
            if(key==KeyEvent.KEYCODE_BUTTON_X) {if(up)setFollowLog(true);return true;}
            if(key==KeyEvent.KEYCODE_BUTTON_A||key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER) {
                if(up){if(recoverRow.hasFocus())recoverRow.performClick();else copySaveLog();}return true;
            }
            // Left/right moves between the console and recovery, when it is offered.
            if(recoverRow.isShown()&&(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT)) {
                if(!up){if(logScroll.hasFocus())recoverRow.requestFocus();else logScroll.requestFocus();}return true;
            }
        }
        if(key==KeyEvent.KEYCODE_BUTTON_A) {if(up){View focus=getCurrentFocus();if(focus!=null&&focus.isEnabled())focus.performClick();}return true;}
        return super.dispatchKeyEvent(event);
    }
    private Row row(LinearLayout parent,String glyph,int title,String subtitle,Runnable action) {
        Row row=new Row(glyph,getString(title),subtitle,action);parent.addView(row,lp(-1,dp(52),0,0,0,4));return row;
    }
    /** A single focus target, with stable children and a system-accent selection rail. */
    private final class Row extends LinearLayout {
        final TextView heading,detail,glyphView,arrow;final View rail;
        Row(String glyph,String title,String subtitle,Runnable action) {
            super(SubsystemSetupActivity.this);setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);
            setFocusable(true);setClickable(true);setBackgroundResource(R.drawable.subsystem_row);setPadding(dp(2),dp(4),dp(12),dp(4));
            rail=new View(SubsystemSetupActivity.this);rail.setBackgroundTintList(accent());rail.setBackgroundColor(color(android.R.attr.colorAccent));rail.setVisibility(View.INVISIBLE);
            addView(rail,new LinearLayout.LayoutParams(dp(3),dp(30)));
            glyphView=text(glyph,20,true);glyphView.setTextColor(color(android.R.attr.colorAccent));glyphView.setGravity(Gravity.CENTER);
            addView(glyphView,new LinearLayout.LayoutParams(dp(44),-1));
            LinearLayout labels=column();labels.setGravity(Gravity.CENTER_VERTICAL);addView(labels,new LinearLayout.LayoutParams(0,-1,1));
            heading=text(title,15,true);heading.setSingleLine(true);heading.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(heading,new LinearLayout.LayoutParams(-1,-2));
            detail=secondary(subtitle,12);detail.setSingleLine(true);detail.setEllipsize(TextUtils.TruncateAt.END);labels.addView(detail,new LinearLayout.LayoutParams(-1,-2));
            arrow=secondary("›",22);arrow.setGravity(Gravity.CENTER);arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);arrow.setVisibility(View.GONE);
            addView(arrow,new LinearLayout.LayoutParams(dp(20),-1));glyphView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            setOnFocusChangeListener((v,focused)->updateRail());
            setOnClickListener(v->{try{action.run();}catch(RuntimeException e){toast(R.string.asl_failed);}});
        }
        void title(int value){title(getString(value));}
        void title(String value){setText(heading,value);}
        void subtitle(String value){setText(detail,value);}
        @Override public void setSelected(boolean selected){super.setSelected(selected);if(rail!=null)updateRail();}
        @Override public void setEnabled(boolean enabled){super.setEnabled(enabled);setAlpha(enabled?1f:0.45f);}
        void updateRail(){rail.setVisibility(hasFocus()||isSelected()?View.VISIBLE:View.INVISIBLE);}
        Row chevron(){arrow.setVisibility(View.VISIBLE);return this;}
    }
    private Button button(int label,Runnable action,boolean primary) {
        Button button=new Button(this);button.setText(label);button.setAllCaps(false);button.setTextSize(14);button.setMinHeight(dp(48));
        if(primary){button.setBackgroundResource(R.drawable.subsystem_primary);button.setTextColor(Color.luminance(color(android.R.attr.colorAccent))>0.179?Color.BLACK:Color.WHITE);}
        else {button.setTextColor(color(android.R.attr.colorAccent));button.setForeground(getDrawable(R.drawable.subsystem_console_focus));}
        button.setOnClickListener(v->{try{action.run();}catch(RuntimeException e){toast(R.string.asl_failed);}});return button;
    }
    private void showControl(View control,boolean visible,View fallback) {
        int visibility=visible?View.VISIBLE:View.GONE;if(control.getVisibility()==visibility)return;
        boolean focused=control.hasFocus();control.setVisibility(visibility);if(focused)focusFirst(fallback);
    }
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private TextView text(String value,float size,boolean medium) {
        TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color(android.R.attr.textColorPrimary));
        v.setTypeface(Typeface.create(medium?"sans-serif-medium":"sans-serif",Typeface.NORMAL));return v;
    }
    private TextView secondary(String value,float size){TextView v=text(value,size,false);v.setTextColor(color(android.R.attr.textColorSecondary));return v;}
    private int color(int attribute) {TypedValue value=new TypedValue();getTheme().resolveAttribute(attribute,value,true);return value.resourceId!=0?getColorStateList(value.resourceId).getDefaultColor():value.data;}
    private ColorStateList accent(){return ColorStateList.valueOf(color(android.R.attr.colorAccent));}
    private LinearLayout.LayoutParams lp(int width,int height,int left,int top,int right,int bottom) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(width,height);p.setMargins(dp(left),dp(top),dp(right),dp(bottom));return p;
    }
    private static void setText(TextView view,CharSequence value){if(!value.toString().contentEquals(view.getText()))view.setText(value);}
    private void toast(int id){Toast.makeText(this,id,Toast.LENGTH_LONG).show();}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
