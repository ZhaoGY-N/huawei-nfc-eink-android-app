package org.nfcresearch.qpolllab;

/** Firmware evidence is a prerequisite, not proof of a working RF/session path. */
final class NxpHalPolicy {
    private NxpHalPolicy() {}
    static boolean supported(String config) {
        return config!=null&&(config.startsWith("NXP SN220")||config.startsWith("NXP SN100U"));
    }
    static String firmwareConfig(String text,String path) {
        for(String line:text.split("\\r?\\n")) {
            java.util.regex.Matcher m=java.util.regex.Pattern.compile("^\\s*NXP_FW_NAME\\s*=\\s*\"([^\"]+)\"").matcher(line);
            if(!m.find())continue;
            String firmware=m.group(1);
            java.util.regex.Matcher chip=java.util.regex.Pattern.compile("lib([a-zA-Z0-9]+)_fw\\.so").matcher(firmware);
            return "NXP "+(chip.matches()?chip.group(1).toUpperCase(java.util.Locale.ROOT):firmware)
                    +"\n依据："+path+" / "+firmware+"\n来自系统固件配置，非芯片 ID 实读";
        }
        return null;
    }
}
