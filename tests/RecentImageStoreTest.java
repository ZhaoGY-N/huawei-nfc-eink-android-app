package org.nfcresearch.qpolllab;

import java.io.*;
import java.nio.file.Files;
import java.util.*;

public final class RecentImageStoreTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        File root=Files.createTempDirectory(new File(args[0]).toPath(),"recent-images-").toFile();
        RecentImageStore store=new RecentImageStore(root,3,64,32);
        check(store.list().isEmpty(),"New history is empty");
        String first=store.add(new byte[]{1}).id,second=store.add(new byte[]{2}).id;
        check(store.list().get(0).id.equals(second),"Newest first");
        store.add(new byte[]{1});check(store.list().size()==2&&store.list().get(0).id.equals(first),"Deduplicate and move to front");
        check(Arrays.equals(Files.readAllBytes(store.file(first).toPath()),new byte[]{1}),"Private copy round-trip");
        check(new RecentImageStore(root,3,64,32).list().get(0).id.equals(first),"History survives store recreation");
        String third=store.add(new byte[]{3}).id;store.add(new byte[]{4});
        check(store.list().size()==3&&!store.file(second).exists(),"Count limit evicts least recently used");
        store.touch(first);check(store.list().get(0).id.equals(first),"Selecting history updates recency");
        store.remove(third);check(!store.file(third).exists(),"Individual removal");
        File unrelated=new File(root,"unrelated.txt");Files.write(unrelated.toPath(),new byte[]{9});
        try{store.remove("../unrelated.txt");throw new AssertionError("Invalid id accepted");}catch(IOException expected){}
        try{store.add(new byte[33]);throw new AssertionError("Oversize accepted");}catch(IOException expected){}
        store.clear();check(store.list().isEmpty()&&unrelated.exists(),"Clear only owns history copies");
        byte[] a=new byte[30],b=new byte[30],c=new byte[30];a[0]=1;b[0]=2;c[0]=3;
        String old=store.add(a).id;store.add(b);store.add(c);
        long total=0;for(RecentImageStore.Entry entry:store.list())total+=entry.bytes;
        check(total==60&&store.list().size()==2&&!store.file(old).exists(),"Total byte budget evicts oldest");
        check(root.listFiles((dir,name)->name.endsWith(".tmp")).length==0,"No unfinished copies");
        store.clear();Files.delete(unrelated.toPath());Files.delete(root.toPath());
        System.out.println("RecentImageStoreTest: PASS (restart, round-trip, dedup, recency, count/byte limits, removal, path validation)");
    }
}
