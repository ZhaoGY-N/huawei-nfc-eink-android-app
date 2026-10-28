package org.nfcresearch.qpolllab;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

public final class SessionApkPathTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static File apk(Path dir,String name,boolean dex)throws Exception {
        File file=dir.resolve(name).toFile();try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(file))){out.putNextEntry(new ZipEntry(dex?"classes.dex":"AndroidManifest.xml"));out.write(new byte[]{1,2,3});out.closeEntry();}return file;
    }
    private static void rejected(SessionApkPath.Lookup action)throws Exception {try{action.current();throw new AssertionError("Invalid APK source accepted");}catch(IOException|SecurityException expected){}}
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory("session-apk-path-");File old=apk(dir,"old.apk",true),fresh=apk(dir,"fresh.apk",true),bad=apk(dir,"no-dex.apk",false);
        SessionApkPath.Lookup lookup=()->new SessionApkPath.Source(10232,fresh.getAbsolutePath());
        check(SessionApkPath.resolve(old.getAbsolutePath(),10232,lookup).equals(fresh.getAbsolutePath()),"A live old APK must not override the newly installed APK");
        check(old.delete(),"Old install removed");
        check(SessionApkPath.resolve(old.getAbsolutePath(),10232,lookup).equals(fresh.getAbsolutePath()),"Upgrade with stale deleted path recovers before worker/HAL startup");
        check(SessionApkPath.resolve(fresh.getAbsolutePath(),-1,()->{throw new AssertionError("Process mode must retain its supplied snapshot");}).equals(fresh.getAbsolutePath()),"Process/legacy launch path unchanged");
        rejected(()->{SessionApkPath.resolve(old.getAbsolutePath(),10232,()->new SessionApkPath.Source(10000,fresh.getAbsolutePath()));return null;});
        rejected(()->{SessionApkPath.resolve(fresh.getAbsolutePath(),10232,()->new SessionApkPath.Source(10232,old.getAbsolutePath()));return null;});
        rejected(()->{SessionApkPath.resolve(bad.getAbsolutePath(),-1,lookup);return null;});
        rejected(()->{SessionApkPath.resolve(old.getAbsolutePath(),-1,lookup);return null;});
        rejected(()->{SessionApkPath.resolve(fresh.getAbsolutePath(),10232,()->null);return null;});
        Files.delete(fresh.toPath());Files.delete(bad.toPath());Files.delete(dir);
        System.out.println("SessionApkPathTest: PASS (resident service upgrade/reinstall, live old path, deleted APK, UID changes, no stale fallback, process snapshot and missing DEX)");
    }
}
