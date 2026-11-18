package replay;

import java.io.*;

/** Source 650017940 mouse telemetry; never inject recorded input into spectator controls. */
final class MouseTrace {
    record Summary(int bytes,int records,int motion,int deviceDefinitions,int extendedAim){}
    static Summary read(byte[] bytes)throws IOException {
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));int records=0,motion=0,devices=0,aim=0;
        while(in.available()!=0){
            int opcode=in.readUnsignedByte();records++;
            if((opcode&128)!=0){motion++;continue;}
            if((opcode&96)==96||(opcode&96)==64){in.readUnsignedByte();motion++;continue;}
            switch(opcode){
                case 0,1,2,3->{in.readUnsignedByte();SourceAdapters.var(in,5);SourceAdapters.var(in,5);motion++;}
                case 5->in.readByte();
                case 6->{in.readByte();in.readUTF();devices++;}
                case 7,31->{}
                case 8->{finite(in);in.readByte();}
                case 9,10,12->{finite(in);finite(in);}
                case 11->finite(in);
                case 13->{int flags=in.readUnsignedByte();SourceAdapters.var(in,5);for(int n=0;n<(flags>>>4);n++)in.readUnsignedByte();}
                case 14->{SourceAdapters.var(in,5);in.readShort();in.readShort();in.readShort();in.readUnsignedByte();aim++;}
                default->throw new IOException("Unknown source mouse trace opcode "+opcode);
            }
        }
        return new Summary(bytes.length,records,motion,devices,aim);
    }
    private static void finite(DataInput in)throws IOException {if(!Float.isFinite(in.readFloat()))throw new IOException("Non-finite recorded mouse telemetry");}
}
