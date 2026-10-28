package org.nfcresearch.qpolllab;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Bounded, content-deduplicated private copies of decoded original images. */
public final class RecentImageStore {
    public static final int MAX_ENTRIES=20;
    public static final long MAX_BYTES=64L*1024*1024,MAX_IMAGE_BYTES=20L*1024*1024;
    private final File directory;
    private final int maxEntries;
    private final long maxBytes,maxImageBytes;
    public static final class Entry {
        public final String id;
        public final long usedAt,bytes;
        Entry(File file){
            id=file.getName().substring(0,64);bytes=file.length();long time=file.lastModified();
            try(DataInputStream in=new DataInputStream(new FileInputStream(new File(file.getParentFile(),id+".used")))){time=in.readLong();}catch(IOException ignored){}
            usedAt=time;
        }
    }
    public RecentImageStore(File directory){this(directory,MAX_ENTRIES,MAX_BYTES,MAX_IMAGE_BYTES);}
    RecentImageStore(File directory,int maxEntries,long maxBytes,long maxImageBytes){
        if(maxEntries<1||maxImageBytes<1||maxBytes<maxImageBytes)throw new IllegalArgumentException("Invalid history limits");
        this.directory=directory;this.maxEntries=maxEntries;this.maxBytes=maxBytes;this.maxImageBytes=maxImageBytes;
    }
    public synchronized List<Entry> list() throws IOException {
        if(!directory.exists())return new ArrayList<>();
        File[] files=directory.listFiles();if(files==null)throw new IOException("无法读取最近图片");
        List<Entry> entries=new ArrayList<>();
        for(File file:files)if(file.isFile()&&file.getName().matches("[0-9a-f]{64}\\.png"))entries.add(new Entry(file));
        Collections.sort(entries,(a,b)->{int order=Long.compare(b.usedAt,a.usedAt);return order!=0?order:a.id.compareTo(b.id);});
        return entries;
    }
    public synchronized File file(String id) throws IOException {
        if(id==null||!id.matches("[0-9a-f]{64}"))throw new IOException("无效的图片记录");
        return new File(directory,id+".png");
    }
    public synchronized Entry add(byte[] png) throws Exception {
        if(png.length==0||png.length>maxImageBytes)throw new IOException("图片副本过大，未加入最近图片");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("无法创建最近图片目录");
        StringBuilder key=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(png))key.append(String.format(Locale.ROOT,"%02x",b&255));
        File target=file(key.toString());
        if(!target.exists()){
            File pending=File.createTempFile("image-",".tmp",directory);
            try{
                try(FileOutputStream out=new FileOutputStream(pending)){out.write(png);out.getFD().sync();}
                if(!pending.renameTo(target))throw new IOException("无法保存最近图片");
            }finally{if(pending.exists())pending.delete();}
        }
        touch(key.toString());prune();return new Entry(target);
    }
    public synchronized void touch(String id) throws IOException {
        File target=file(id);if(!target.isFile())throw new IOException("图片副本已不存在");
        long time=System.currentTimeMillis();for(Entry entry:list())time=Math.max(time,entry.usedAt+1);
        // Persist recency separately: some filesystems round file timestamps to seconds.
        File pending=File.createTempFile("used-",".tmp",directory);
        try{
            try(FileOutputStream out=new FileOutputStream(pending);DataOutputStream data=new DataOutputStream(out)){data.writeLong(time);data.flush();out.getFD().sync();}
            if(!pending.renameTo(new File(directory,id+".used")))throw new IOException("无法更新最近使用时间");
        }finally{if(pending.exists())pending.delete();}
    }
    private void prune() throws IOException {
        long bytes=0;int count=0;
        for(Entry entry:list()){
            if(count>=maxEntries||bytes+entry.bytes>maxBytes)remove(entry.id);
            else{bytes+=entry.bytes;count++;}
        }
    }
    public synchronized void remove(String id) throws IOException {
        File target=file(id);if(target.exists()&&!target.delete())throw new IOException("无法删除图片副本");
        File used=new File(directory,id+".used");if(used.exists()&&!used.delete())throw new IOException("无法删除最近使用时间");
    }
    public synchronized void clear() throws IOException {for(Entry entry:list())remove(entry.id);}
}
