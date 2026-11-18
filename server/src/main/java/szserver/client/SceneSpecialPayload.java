package szserver.client;

import szserver.net.Wire;
import szserver.world.ScenePayload;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Reviewed canonical behavior wire layouts. Native allocation sites are bounded before any QA read. */
public final class SceneSpecialPayload {
    private static final Set<String> HANDLED=Set.of("lootable","cable2d","cable3d","curve","prestige/respawn_marker","modeller_mesh","zipline","zipline_vertical");
    public static boolean handles(String behavior){return behavior!=null&&HANDLED.contains(behavior);}
    static int end(ByteBuffer in,String behavior){
        switch(behavior){
            case "lootable"->{int flags=in.get()&255;if((flags&~3)!=0)throw new IllegalArgumentException("scene_lootable_flags");if((flags&2)!=0)utf(in);}
            case "cable2d"->{in.getInt();cable(in);}
            case "cable3d"->{floatArray(in);intArray(in);cable(in);}
            case "curve"->{int count=count(in,52);for(int i=0;i<count;i++){floats(in,12);in.getInt();}floatArray(in);intArray(in);floats(in,7);}
            case "prestige/respawn_marker"->{int begin=in.position();ScenePayload.namedNbt(in);SceneConfigurationPackets.validate(Arrays.copyOfRange(in.array(),begin,in.position()));utf(in);ScenePayload.bool(in);}
            case "modeller_mesh"->{int mode=in.get()&255;if(mode>1)throw new IllegalArgumentException("scene_mesh_context");mesh(in);if(mode==0)utf(in);}
            case "zipline"->{in.getInt();ordinal(in,3);doubles(in,4);floatArray(in);intArray(in);cable(in);}
            case "zipline_vertical"->{cable(in);floatArray(in);intArray(in);floats(in,7);ordinal(in,3);ordinal(in,3);doubles(in,4);}
            default->throw new IllegalArgumentException("scene_configuration_behavior_unsupported");
        }return in.position();
    }
    private static void cable(ByteBuffer in){floats(in,7);in.getInt();floats(in,10);}
    private static int count(ByteBuffer in,int elementSize){int count=in.getInt();if(count<0||count>16384||count>in.remaining()/elementSize)throw new IllegalArgumentException("scene_behavior_count");return count;}
    private static void floatArray(ByteBuffer in){floats(in,count(in,4));}
    private static void intArray(ByteBuffer in){ScenePayload.take(in,Math.multiplyExact(count(in,4),4));}
    private static void floats(ByteBuffer in,int count){for(int i=0;i<count;i++)if(!Float.isFinite(in.getFloat()))throw new IllegalArgumentException("scene_behavior_nonfinite");}
    private static void doubles(ByteBuffer in,int count){for(int i=0;i<count;i++)if(!Double.isFinite(in.getDouble()))throw new IllegalArgumentException("scene_behavior_nonfinite");}
    private static void ordinal(ByteBuffer in,int size){int value=in.getInt();if(value<0||value>=size)throw new IllegalArgumentException("scene_behavior_enum");}
    private static void utf(ByteBuffer in){byte[] bytes=ScenePayload.utf(in);try{Wire.input(bytes).readUTF();}catch(IOException invalid){throw new IllegalArgumentException("scene_behavior_utf",invalid);}}
    static void mesh(ByteBuffer in){
        int version=in.getShort()&65535;if(version>5)throw new IllegalArgumentException("scene_mesh_version");int materials=version<5?in.getShort()&65535:in.get()&255;if(materials>256)throw new IllegalArgumentException("scene_mesh_materials");for(int i=0;i<materials;i++){utf(in);if(version<5)in.getShort();else in.get();}
        float scale=in.getFloat();if(!Float.isFinite(scale)||scale<=0)throw new IllegalArgumentException("scene_mesh_scale");ScenePayload.take(in,12*(in.getShort()&65535));ScenePayload.take(in,8*(in.getShort()&65535));int faces=in.getShort()&65535;if(faces>in.remaining()/4)throw new IllegalArgumentException("scene_mesh_faces");
        for(int i=0;i<faces;i++){int vertices=in.get()&255;ScenePayload.take(in,6*vertices+2);int flags=in.get()&255;if(version==5){ScenePayload.take(in,in.get()&255);if((flags&64)!=0)ScenePayload.take(in,3);ScenePayload.take(in,(flags&128)!=0?4:1);}else ScenePayload.take(in,(version>2?6:3)+(version>3?1:0)+2);}
        if(version<5){int edges=in.getShort()&65535;if(edges>in.remaining()/6)throw new IllegalArgumentException("scene_mesh_edges");for(int i=0;i<edges;i++){ScenePayload.take(in,4);int flags=in.get()&255;ScenePayload.take(in,1+2*(flags>>>1));}}
    }
    private SceneSpecialPayload(){}
}
