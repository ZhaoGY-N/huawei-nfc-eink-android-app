package org.nfcresearch.qpolllab;

import java.io.*;
import java.nio.file.Files;
import java.util.Arrays;

public final class ImageDraftStoreTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        File folder=Files.createTempDirectory(new File(args[0]).toPath(),"draft-").toFile(),file=new File(folder,"editor.bin");ImageDraftStore store=new ImageDraftStore(file);
        check(store.load()==null,"Missing draft");
        byte[] original={1,2,3},crop={4,5};String metadata="旋转90度、裁切窗口、A0X、测试模式";
        store.save(new ImageDraftStore.Draft(metadata,original,crop));
        ImageDraftStore.Draft loaded=new ImageDraftStore(file).load();
        check(loaded.settings.equals(metadata)&&Arrays.equals(loaded.original,original)&&Arrays.equals(loaded.current,crop),"Restart preserves original, cropped source and metadata");
        store.save(new ImageDraftStore.Draft("uncropped",original,original));loaded=store.load();
        check(loaded.current==loaded.original,"Uncropped original shared without another image copy");
        try{store.save(new ImageDraftStore.Draft("invalid",new byte[0],original));throw new AssertionError("Empty image accepted");}catch(IOException expected){}
        check(store.load().settings.equals("uncropped"),"Failed save preserves previous draft");
        try{store.save(new ImageDraftStore.Draft(new String(new char[70000]).replace('\0','x'),original,crop));throw new AssertionError("Oversize metadata accepted");}catch(IOException expected){}
        check(store.load().settings.equals("uncropped")&&folder.listFiles().length==1,"Interrupted write leaves previous complete draft and no temporary file");
        Files.write(file.toPath(),new byte[]{0,1,2});try{store.load();throw new AssertionError("Truncated draft accepted");}catch(IOException expected){}
        store.save(new ImageDraftStore.Draft("restored",original,crop));check(store.load().settings.equals("restored"),"Can replace corrupt draft");
        CropWindow window=new CropWindow(1200,1600);window.scale(2.5,0.1,-0.2);window.pan(130,-45);
        CropWindow restored=CropWindow.restore(1200,1600,window.left(),window.top(),window.width());
        check(Math.abs(restored.left()-window.left())<1e-8&&Math.abs(restored.top()-window.top())<1e-8&&Math.abs(restored.height()-window.height())<1e-8,"Crop window restores exactly for further editing");
        try{CropWindow.restore(1200,1600,Double.NaN,0,100);throw new AssertionError("Invalid crop accepted");}catch(IllegalArgumentException expected){}
        Files.delete(file.toPath());Files.delete(folder.toPath());
        System.out.println("ImageDraftStoreTest: PASS (restart, original/crop metadata, atomic replacement, invalid/truncated drafts, exact crop restoration)");
    }
}
