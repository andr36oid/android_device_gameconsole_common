package org.andr36oid.subsystemsetup;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.util.Locale;

/** Daijishou port entry. The dashboard owns the visible handoff and broker request. */
public class LaunchPortActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String spec=resolveSpec(getIntent());
        if(spec!=null) {
            startActivity(new Intent(this,SubsystemSetupActivity.class)
                    .putExtra(SubsystemSetupActivity.EXTRA_LAUNCH,spec)
                    .putExtra(SubsystemSetupActivity.EXTRA_PORT_LAUNCH,true)
                    .putExtra(SubsystemSetupActivity.EXTRA_LAUNCH_TOKEN,SubsystemSetupActivity.mintLaunchToken()));
        }
        finish();
    }

    /** Same ROM-extra / VIEW URI and basename protocol as SubsystemBridge. */
    static String resolveSpec(Intent intent) {
        String raw=intent.getStringExtra("ROM");
        if(raw==null) {
            Uri data=intent.getData();
            if(data!=null)raw=data.getPath();
        }
        if(raw==null)return null;
        raw=raw.trim();
        if(raw.equalsIgnoreCase("ES")||raw.equalsIgnoreCase("PM"))return raw.toUpperCase(Locale.ROOT);
        String name=new File(raw).getName();
        // A tool marker lives at .../gameconsole-ports/tools/<exact script name>.
        // Keep the name verbatim (including a .SH extension) in the TOOL: namespace
        // so the broker resolves it only under /roms/tools, never as a port. Match
        // the marker case-insensitively so a differently-cased path cannot slip the
        // tool into the plain port namespace.
        if(raw.toLowerCase(Locale.ROOT).contains("/gameconsole-ports/tools/"))return valid(name)?"TOOL:"+name:null;
        // Plain port: drop a single trailing .sh (a lone ".sh" becomes empty and is
        // rejected), then require a clean basename before it can reach the broker.
        if(name.toLowerCase(Locale.ROOT).endsWith(".sh"))name=name.substring(0,name.length()-3);
        return valid(name)?name:null;
    }

    /**
     * Defense in depth against a spec escaping the port/tool namespace. The broker
     * confines targets under /roms; the app refuses anything that is not a plain
     * basename: empty, "." / "..", or carrying a path separator, NUL or control char.
     */
    static boolean valid(String name) {
        if(name.isEmpty()||name.equals(".")||name.equals(".."))return false;
        for(int i=0;i<name.length();i++) {
            char c=name.charAt(i);
            if(c=='/'||c=='\\'||c<='\u001f'||c=='\u007f')return false;
        }
        return true;
    }
}
