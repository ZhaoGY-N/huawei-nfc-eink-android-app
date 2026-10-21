package org.nfcresearch.qpolllab;
import java.util.Arrays;

public final class PanelTypeTest {
    private static void require(boolean ok,String label){if(!ok)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception {
        byte[] codes=new byte[552*768];java.util.Random random=new java.util.Random(615297);
        for(int i=0;i<codes.length;i++)codes[i]=(byte)random.nextInt(4);
        byte[] original=codes.clone();
        byte[] linear=PanelProtocol.packCodes(codes,552,768);
        require(Arrays.equals(linear,PanelType.A1.pack(codes)),"A1 factory layout preserves existing output");
        require(!Arrays.equals(PanelType.A0.pack(codes),PanelType.A1.pack(codes)),"variants differ for asymmetric input");
        for(PanelType type:PanelType.values()) {
            byte[] raw=type.pack(codes),wire=PanelType.forScan(raw,true);
            int[] preview=type.preview(raw);
            for(int y=0;y<768;y++)for(int x=0;x<552;x++)require(preview[y*552+x]==ImageRaster.color(codes[y*552+x]),"logical preview round trip");
            // Independent direct pixel-to-wire oracle: invert target row numbering.
            for(int controller=0;controller<552;controller++) {
                int source=type==PanelType.A1?controller:(controller%2==0?controller/2:551-controller/2);
                if(type==PanelType.A0X)source=(source+276)%552;
                int x=551-source;
                for(int y=0;y<768;y+=4) {
                    int expected=0;for(int k=0;k<4;k++)expected|=codes[(y+k)*552+x]<<(6-2*k);
                    require((wire[controller*192+y/4]&255)==expected,"reference top-down native layout "+type);
                }
            }
            require(Arrays.equals(PanelType.forScan(wire,true),raw),"scan direction conversion is reversible");
            require(Arrays.equals(PanelType.forScan(raw,false),raw),"factory scan retained");
            for(boolean topDown:new boolean[]{false,true}) {
                ProtocolTest.Fake transport=new ProtocolTest.Fake();transport.info=ProtocolTest.INFO.clone();
                transport.info[11]=(byte)(topDown?1:0);
                new PanelProtocol(transport,transport).write(raw,2000,500);
                require(transport.d4==1,"one refresh for selected variant");
                java.io.ByteArrayOutputStream actual=new java.io.ByteArrayOutputStream(),block=new java.io.ByteArrayOutputStream();
                for(byte[] command:transport.uploads) {
                    block.write(command,7,command.length-7);
                    if(command[3]==1) {
                        byte[] lzo=block.toByteArray();int offset=1,n;
                        if((lzo[0]&255)>17)n=(lzo[0]&255)-17;
                        else {n=18;while(lzo[offset]==0){n+=255;offset++;}n+=lzo[offset++]&255;}
                        require(offset+n+3==lzo.length&&lzo[offset+n]==17&&lzo[offset+n+1]==0&&lzo[offset+n+2]==0,"literal stream framing");
                        actual.write(lzo,offset,n);block.reset();
                    }
                }
                require(Arrays.equals(actual.toByteArray(),topDown?wire:raw),"actual D3 payload matches selected panel and stored scan");
            }
        }
        require(Arrays.equals(codes,original),"no input mutation");
        for(String value:new String[]{"","a0","unknown",null})try {PanelType.parse(value);throw new AssertionError("invalid choice accepted");}catch(IllegalArgumentException expected){}
        require(PanelType.parse("A0")==PanelType.A0&&PanelType.parse("A1")==PanelType.A1&&PanelType.parse("A0X")==PanelType.A0X,"explicit selection");
        ProtocolTest.Fake changed=new ProtocolTest.Fake();
        PanelProtocol changing=new PanelProtocol((cmd,timeout)->{
            if((cmd[1]&255)==0xD1&&changed.d1==1){changed.info=changed.info.clone();changed.info[11]=1;}
            return changed.exchange(cmd,timeout);
        },changed);
        try {changing.write(linear,2000,500);throw new AssertionError("scan change accepted");}catch(IllegalArgumentException expected){require(changed.d3==0&&changed.d4==0,"scan change stops before upload");}
        java.nio.file.Path fixtures=java.nio.file.Paths.get(args[0]);
        java.nio.file.Files.write(fixtures.resolve("panel-types-codes.bin"),codes);
        for(PanelType type:PanelType.values())java.nio.file.Files.write(fixtures.resolve("panel-"+type+"-scan01.bin"),PanelType.forScan(type.pack(codes),true));
        System.out.println("PanelType tests passed: full-frame reference oracle, both variants/scans, preview, validation");
    }
}
