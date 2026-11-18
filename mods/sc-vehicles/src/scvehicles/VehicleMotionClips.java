package scvehicles;
import java.io.*;
import java.util.*;

/** Real Warzone motion sampled offline; all points are pelvis-relative in SC axes. */
final class VehicleMotionClips {
    record Clip(String id,String source,double duration,float[][] frames){
        float[] sample(double phase){double at=Math.max(0,Math.min(1,phase))*(frames.length-1);int a=(int)at,b=Math.min(a+1,frames.length-1);float[] out=new float[frames[a].length];for(int i=0;i<out.length;i++)out[i]=(float)(frames[a][i]+(frames[b][i]-frames[a][i])*(at-a));return out;}
    }
    static final Map<String,Clip> ALL=new LinkedHashMap<>();static final Map<String,Integer> JOINTS=new HashMap<>();
    static {
        try(var in=new DataInputStream(Objects.requireNonNull(VehicleMotionClips.class.getResourceAsStream("/scvehicles/vehicle-clips.bin")))){
            if(in.readInt()!=0x53434131)throw new IOException("vehicle motion magic");int joints=in.readInt(),clips=in.readInt();if(joints<1||joints>128||clips>64)throw new IOException("vehicle motion bounds");
            for(int i=0;i<joints;i++)JOINTS.put(in.readUTF(),i);
            for(int c=0;c<clips;c++){String id=in.readUTF(),source=in.readUTF();double duration=in.readFloat();int count=in.readInt();if(count<2||count>1000||duration<=0||duration>10)throw new IOException("vehicle motion clip");float[][] frames=new float[count][3+joints*3];for(float[] f:frames)for(int k=0;k<f.length;k++){f[k]=in.readFloat();if(!Float.isFinite(f[k]))throw new IOException("vehicle motion nonfinite");}ALL.put(id,new Clip(id,source,duration,frames));}
            if(in.read()!=-1)throw new IOException("vehicle motion trailing data");
        }catch(Exception failure){throw new ExceptionInInitializerError(failure);}
    }
    static Clip get(String name){return Objects.requireNonNull(ALL.get(name),name);}
    static double[] point(float[] f,String joint){int i=3+JOINTS.get(joint)*3;return new double[]{f[i],f[i+1],f[i+2]};}
    private VehicleMotionClips(){}
}
