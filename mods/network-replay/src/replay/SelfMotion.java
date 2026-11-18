package replay;

import java.io.*;

/** Source local-control snapshot, including recorded doubles rather than a simulation. */
final class SelfMotion {
    record Frame(int flags,Float inputSpeed,Integer mode,int[] keys,double[] position,float[] look,int[] counters,float interpolation,byte[] actions){}
    static Frame read(byte[] payload)throws IOException {
        DataInputStream in=Motion.reader(payload);int flags=in.readUnsignedByte();if((flags&~7)!=0)throw new IOException("Unknown local movement flags");
        Float inputSpeed=null;Integer mode=null;int[] keys=null;
        if((flags&1)!=0){inputSpeed=in.readFloat();mode=in.readInt();if(!Float.isFinite(inputSpeed)||mode<0||mode>=6)throw new IOException("Invalid local input");keys=new int[8];for(int i=0;i<8;i++){keys[i]=in.readUnsignedByte();if(keys[i]>1)throw new IOException("Invalid local input key");}int count=in.readInt();if(count!=0)throw new IOException("Local action registry requires translation: "+count);}
        double[] position=null;if((flags&2)!=0){position=new double[]{in.readDouble(),in.readDouble(),in.readDouble()};for(double n:position)if(!Double.isFinite(n))throw new IOException("Invalid local position");}
        float[] look=null;if((flags&4)!=0){look=new float[]{in.readFloat(),in.readFloat()};for(float n:look)if(!Float.isFinite(n))throw new IOException("Invalid local look");}
        int[] counters={in.readInt(),in.readInt(),in.readInt()};float interpolation=in.readFloat();if(!Float.isFinite(interpolation))throw new IOException("Invalid interpolation");
        byte[] actions=SourceAdapters.bytes(in,Motion.var(in));if(in.available()!=0)throw new IOException("Trailing local movement bytes");
        MouseTrace.read(actions);
        return new Frame(flags,inputSpeed,mode,keys,position,look,counters,interpolation,actions);
    }
}
