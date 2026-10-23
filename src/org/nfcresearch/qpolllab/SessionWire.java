package org.nfcresearch.qpolllab;

import java.io.*;

/** Bounded stdin envelope; no shell-readable task files or arbitrary commands. */
final class SessionWire {
    private static final int MAGIC=0x51504231;
    static final int MAX_JSON=8192, MAX_IMAGE=105984;
    static final long OWNER_TIMEOUT_MS=5000;
    static final class Job {
        final String mode,json; final int seconds; final byte[] image;
        Job(String mode,int seconds,String json,byte[] image){this.mode=mode;this.seconds=seconds;this.json=json;this.image=image;}
    }
    static void validate(Job job) {
        BackendPolicy.validate(2000,job.mode,"system");
        BackendPolicy.validateDuration(job.mode,job.seconds);
        if(job.json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_JSON||job.image.length>MAX_IMAGE)
            throw new IllegalArgumentException("任务数据超出限制");
        if(!job.mode.equals("refresh")&&job.image.length!=0)throw new IllegalArgumentException("只有刷新任务接受图像");
        if(job.mode.equals("refresh")&&job.image.length!=MAX_IMAGE)throw new IllegalArgumentException("刷新图像长度错误");
    }
    static void write(OutputStream output,Job job)throws IOException {
        validate(job);
        DataOutputStream out=new DataOutputStream(output);out.writeInt(MAGIC);out.writeUTF(job.mode);out.writeInt(job.seconds);
        byte[] json=job.json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(json.length);out.write(json);out.writeInt(job.image.length);out.write(job.image);out.flush();
    }
    static Job read(InputStream input)throws IOException {
        DataInputStream in=new DataInputStream(input);
        if(in.readInt()!=MAGIC)throw new IOException("进程协议版本不匹配");
        String mode=in.readUTF();int seconds=in.readInt();
        byte[] json=readBytes(in,MAX_JSON),image=readBytes(in,MAX_IMAGE);
        Job job=new Job(mode,seconds,new String(json,java.nio.charset.StandardCharsets.UTF_8),image);
        validate(job);return job;
    }
    private static byte[] readBytes(DataInputStream in,int max)throws IOException {
        int n=in.readInt();if(n<0||n>max)throw new IOException("进程任务长度越界");
        byte[] b=new byte[n];in.readFully(b);return b;
    }
    static final class Lease {
        private long last; private boolean stopped;
        Lease(long now){last=now;}
        synchronized void control(int value,long now){if(value==1&&!cancelled(now))last=now;else stopped=true;}
        synchronized boolean cancelled(long now){if(now-last>=OWNER_TIMEOUT_MS)stopped=true;return stopped;}
    }
}
