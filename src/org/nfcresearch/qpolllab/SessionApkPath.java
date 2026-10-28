package org.nfcresearch.qpolllab;

import java.io.*;
import java.util.zip.*;

/** A resident service must resolve the installed APK again for every operation. */
final class SessionApkPath {
    static final class Source {
        final int uid;final String path;
        Source(int uid,String path){this.uid=uid;this.path=path;}
    }
    interface Lookup {Source current()throws Exception;}
    private SessionApkPath(){}
    static String resolve(String supplied,int ownerUid,Lookup lookup)throws Exception {
        String path=supplied;
        if(ownerUid>=0){
            Source installed=lookup.current();
            if(installed==null||installed.uid!=ownerUid)throw new SecurityException("App 安装身份已改变，请重新连接授权服务");
            path=installed.path;
        }
        if(path==null)throw new IOException("无法取得当前 APK 路径，请重新连接授权服务");
        File apk=new File(path);
        if(!apk.isAbsolute()||!apk.isFile()||!apk.canRead())throw new IOException("当前 APK 已移动或不可读，未启动 NFC；请重新连接授权服务");
        try(ZipFile zip=new ZipFile(apk)){
            ZipEntry dex=zip.getEntry("classes.dex");
            if(dex==null||dex.getSize()<1)throw new IOException("当前 APK 缺少 classes.dex，未启动 NFC");
        }
        return path;
    }
}
