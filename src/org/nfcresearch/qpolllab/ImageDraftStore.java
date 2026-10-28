package org.nfcresearch.qpolllab;

import java.io.*;
import java.nio.file.*;

/** One atomic private editing draft, independent of recent-image retention. */
public final class ImageDraftStore {
    private static final int MAGIC=0x45494e31,MAX_IMAGE=20*1024*1024;
    private final File file;
    public static final class Draft {
        public final String settings;
        public final byte[] original,current;
        public Draft(String settings,byte[] original,byte[] current){this.settings=settings;this.original=original;this.current=current;}
    }
    public ImageDraftStore(File file){this.file=file;}
    public synchronized void save(Draft draft)throws IOException {
        check(draft.original.length);check(draft.current.length);
        File parent=file.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("无法创建草稿目录");
        File pending=File.createTempFile("draft-",".tmp",parent);
        try{
            try(FileOutputStream output=new FileOutputStream(pending);DataOutputStream data=new DataOutputStream(output)){
                data.writeInt(MAGIC);data.writeUTF(draft.settings);data.writeInt(draft.original.length);data.write(draft.original);
                boolean shared=draft.original==draft.current;data.writeInt(shared?0:draft.current.length);if(!shared)data.write(draft.current);data.flush();output.getFD().sync();
            }
            Files.move(pending.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }finally{if(pending.exists())pending.delete();}
    }
    private static void check(int size)throws IOException {if(size<1||size>MAX_IMAGE)throw new IOException("草稿图片大小无效");}
    public synchronized Draft load()throws IOException {
        if(!file.exists())return null;
        if(file.length()>2L*MAX_IMAGE+65548)throw new IOException("草稿过大");
        try(DataInputStream data=new DataInputStream(new FileInputStream(file))){
            if(data.readInt()!=MAGIC)throw new IOException("草稿格式无效");String settings=data.readUTF();
            int size=data.readInt();check(size);byte[] original=new byte[size];data.readFully(original);
            size=data.readInt();byte[] current=original;if(size!=0){check(size);current=new byte[size];data.readFully(current);}
            if(data.read()!=-1)throw new IOException("草稿存在多余数据");return new Draft(settings,original,current);
        }
    }
}
