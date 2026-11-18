package szserver.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Native firearm payload plus the installed client's versioned hit report. No entity/raycast inference. */
public final class ShotPackets {
    private ShotPackets(){}
    public record Point(double x,double y,double z){
        public double[] vector(){return new double[]{x,y,z};}
        public double distance(Point p){return Math.hypot(Math.hypot(x-p.x,y-p.y),z-p.z);}
    }
    public record Report(int entity,int group,Point impact){}
    public record Shot(Point origin,Point end,int flags,long seed,int sequence,int serverTick,Report report,byte[] nativeBody){
        public Shot{nativeBody=nativeBody.clone();}
        @Override public byte[] nativeBody(){return nativeBody.clone();}
        public double[] direction(){double d=origin.distance(end);return new double[]{(end.x-origin.x)/d,(end.y-origin.y)/d,(end.z-origin.z)/d};}
        public double[] tracer(double range){double d=Math.min(origin.distance(end),range);double[] n=direction();return new double[]{origin.x+n[0]*d,origin.y+n[1]*d,origin.z+n[2]*d};}
    }
    private static Point point(ByteBuffer b,int offset)throws IOException{
        Point p=new Point(b.getDouble(offset),b.getDouble(offset+8),b.getDouble(offset+16));
        for(double v:p.vector())if(!Double.isFinite(v)||Math.abs(v)>30_000_000)throw new IOException("shot_vector");return p;
    }
    public static Shot read(byte[] raw)throws IOException{
        if(raw.length<84||raw.length>65569)throw new IOException("shot_size");ByteBuffer b=ByteBuffer.wrap(raw);int size=raw.length;Report report=null;
        if(size>=118&&b.getInt(size-34)==0x535a4854){
            int version=b.get(size-30)&255,entity=b.getInt(size-29),group=b.get(size-25)&255;
            if(version!=1||entity< -1||group>3||(entity==-1)!=(group==0))throw new IOException("shot_hit_report");
            report=new Report(entity,group,point(b,size-24));size-=34;
        }
        if(size<84||size>65535)throw new IOException("shot_size");Point origin=point(b,7),end=point(b,31);
        int sequence=b.getInt(size-5);if(sequence<1||origin.distance(end)<.001)throw new IOException("shot_ray");
        return new Shot(origin,end,b.getInt(1),b.getLong(size-13),sequence,b.getInt(55),report,Arrays.copyOf(raw,size));
    }
}
