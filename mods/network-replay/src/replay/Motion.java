package replay;

import java.io.*;
import java.util.*;

/** Source 1635525243: exact frame boundaries, fixed32 coordinates, signed8 angles. */
final class Motion {
    record Update(int entity,int flags,int[] absolute,int[] delta,int[] look,byte[] tail){}
    record Frame(int tick,List<Update> updates){}
    static int r8(int n,int bits){return ((n&255)>>>bits|n<<(8-bits))&255;}
    static DataInputStream reader(byte[] data){return new DataInputStream(new ByteArrayInputStream(data));}
    static int var(DataInput in)throws IOException {return SourceAdapters.length((int)SourceAdapters.var(in,5));}
    static Frame read(byte[] payload)throws IOException {
        DataInputStream in=reader(payload);int variant=r8(in.readUnsignedByte(),2),tick,length;
        switch(variant){
            case 2:tick=in.readInt()+1293592232;length=var(in);break;
            case 1:length=var(in);tick=Integer.rotateRight(in.readInt()^0x573CBE37,31)-554638708;break;
            case 0:length=var(in);tick=in.readInt()+1292223559-347782439-1950030841;break;
            default:throw new IOException("Invalid movement frame variant "+variant);
        }
        byte[] body=SourceAdapters.bytes(in,length);if(in.available()!=0)throw new IOException("Trailing movement frame bytes");in=reader(body);List<Update> updates=new ArrayList<>();
        while(in.available()>0){
            variant=r8(in.readUnsignedByte(),6);int flags,entity,size;
            switch(variant){
                case 2:flags=(in.readUnsignedByte()+114)&255;entity=Integer.rotateRight(in.readInt()^0x4E60281C,9);size=var(in);break;
                case 0:size=var(in);flags=r8(in.readUnsignedByte(),3);entity=(in.readInt()^0xAE4F04ED)+1320784020;break;
                case 1:flags=r8((in.readUnsignedByte()-37)&255,3);size=var(in);entity=in.readInt()+2147018072+297379281+1375302766;break;
                default:throw new IOException("Invalid movement entity variant "+variant);
            }
            DataInputStream p=reader(SourceAdapters.bytes(in,size));int[] absolute=null,delta=null,look=null;int x,y,z;
            if((flags&2)!=0){
                int v=(p.readUnsignedByte()-56)&255;
                switch(v){
                    case 0:x=p.readInt()+723751023;y=Integer.rotateRight(Integer.rotateRight(p.readInt()-724460193,7),14);z=Integer.rotateRight(p.readInt()+1331030142,1);break;
                    case 2:y=p.readInt()^0x674A8179;z=p.readInt()^0xA0EEAC13;x=(p.readInt()+907512558)^0xFFE958C7;break;
                    case 1:x=p.readInt()+1139687053;y=Integer.rotateRight(p.readInt()^0x1173240F,28);z=p.readInt()-1093346722-95791492;break;
                    default:throw new IOException("Invalid absolute movement variant "+v);
                }absolute=new int[]{x,y,z};
            }
            if((flags&64)!=0){
                int v=r8((p.readUnsignedByte()-80)&255,1);
                switch(v){
                    case 1:y=(byte)(p.readUnsignedByte()+75);x=(byte)(p.readUnsignedByte()+113);z=(byte)(p.readUnsignedByte()^0x56);break;
                    case 0:x=(byte)r8(r8(r8(p.readUnsignedByte(),1),1),5);z=(byte)(p.readUnsignedByte()^0x40);y=(byte)((p.readUnsignedByte()+89)^0x7C);break;
                    case 2:y=(byte)(r8(p.readUnsignedByte(),2)+66+123);z=(byte)(r8(p.readUnsignedByte(),7)-121-117);x=(byte)(p.readUnsignedByte()-42);break;
                    default:throw new IOException("Invalid delta movement variant "+v);
                }delta=new int[]{x,y,z};
            }
            if((flags&4)!=0){
                int v=p.readUnsignedByte()^0x4D^0x70;int yaw,pitch;
                switch(v){
                    case 2:yaw=(byte)((p.readUnsignedByte()^0x7D)-70);pitch=(byte)(p.readUnsignedByte()^8);break;
                    case 0:pitch=(byte)((p.readUnsignedByte()^0x3F)-119);yaw=(byte)(p.readUnsignedByte()^0x24);break;
                    case 1:yaw=(byte)(p.readUnsignedByte()-74-116+15);pitch=(byte)(p.readUnsignedByte()-105-69);break;
                    default:throw new IOException("Invalid look variant "+v);
                }look=new int[]{yaw,pitch};
            }
            updates.add(new Update(entity,flags,absolute,delta,look,p.readAllBytes()));
        }
        return new Frame(tick,List.copyOf(updates));
    }
}
