package org.nfcresearch.qpolllab;

import android.content.pm.ApplicationInfo;
import java.io.IOException;

final class InstalledApp {
    private InstalledApp(){}
    static SessionApkPath.Source current()throws Exception {
        Object pm=Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null);
        ApplicationInfo info=(ApplicationInfo)Class.forName("android.content.pm.IPackageManager")
                .getMethod("getApplicationInfo",String.class,long.class,int.class).invoke(pm,"org.nfcresearch.qpolllab",0L,0);
        if(info==null)throw new IOException("App 已移除，请重新连接授权服务");
        return new SessionApkPath.Source(info.uid,info.sourceDir);
    }
    static String sessionPath(String cached,int appUid)throws Exception {return SessionApkPath.resolve(cached,appUid,InstalledApp::current);}
}
