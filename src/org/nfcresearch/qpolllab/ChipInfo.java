package org.nfcresearch.qpolllab;
import java.io.*;

public final class ChipInfo {
    public static String inspect(){
        for(String path:new String[]{"/odm/etc/libnfc-nxp.conf","/vendor/etc/libnfc-nxp.conf",
                "/odm/etc/libnfc-nci.conf","/vendor/etc/libnfc-nci.conf"}){
            try(BufferedReader in=new BufferedReader(new FileReader(path))){String line;StringBuilder text=new StringBuilder();
                while((line=in.readLine())!=null&&text.length()<262144)text.append(line).append('\n');
                String result=NxpHalPolicy.firmwareConfig(text.toString(),path);if(result!=null)return result;
            }catch(IOException ignored){}
        }
        return "未能从当前可读配置识别，请查看系统日志；不据此判定不支持 NFC 或 Q-Poll。";
    }
}
