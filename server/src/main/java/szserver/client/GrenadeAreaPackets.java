package szserver.client;

import szserver.world.SceneObject;
import szserver.net.Wire;
import java.util.*;

/** Native scene addition envelope; gameplay readiness is separate from codec preparation. */
public final class GrenadeAreaPackets {
    public static final String SPAWN="1080895182",REMOVE="303048901";
    public static byte[] spawnBody(int world,List<SceneObject> objects){return body(world,objects,false);}
    public static byte[] spawnWireBody(int world,List<SceneObject> objects){return body(world,objects,true);}
    private static byte[] body(int world,List<SceneObject> objects,boolean hashes){
        if(objects.isEmpty()||objects.size()>16)throw new IllegalArgumentException("grenade_area_packet_count");
        int size=0;Set<UUID> ids=new HashSet<>();for(var o:objects){size+=o.recordSize();if(!ids.add(o.identity())||o.recordSize()>8192)throw new IllegalArgumentException("grenade_area_packet_object");}
        if(size>48*1024)throw new IllegalArgumentException("grenade_area_packet_budget");
        return Wire.bytes(out->{out.writeInt(world);out.writeBoolean(false);out.writeInt(objects.size());for(var o:objects){byte[] b=hashes?szserver.world.SceneHashWire.frame(o.asset(),o.record()):o.record();Wire.varint(out,b.length);out.write(b);}});
    }
    public static byte[] remove(int handle){if(handle>=-1)throw new IllegalArgumentException("grenade_area_handle");return Wire.dynamic(REMOVE,Wire.bytes(out->out.writeInt(handle)));}
    private GrenadeAreaPackets(){}
}
