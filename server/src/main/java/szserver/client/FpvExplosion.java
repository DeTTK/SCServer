package szserver.client;

import szserver.net.Wire;
import java.nio.file.*;
import java.util.*;

/** Reviewed legacy FAB-100 properties; no flight simulation or native factory on game owner. */
public final class FpvExplosion {
    public static final String PACKET="1155277618";
    private final byte[] properties;
    public FpvExplosion(Path fixture)throws Exception{
        properties=RuntimeFiles.read(fixture);
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(properties));
        if(!hash.equals("dd2dd9065a8e83300e8a09350db26e0743d3649ba32d9412ee2c9282eac3653b"))throw new IllegalArgumentException("fpv_explosion_profile_changed");
    }
    public byte[] body(FpvPackets.Vector p){return Wire.bytes(out->{out.writeInt(12501);out.writeBoolean(false);out.write(properties);out.writeDouble(p.x());out.writeDouble(p.y());out.writeDouble(p.z());out.writeBoolean(true);out.writeFloat(1);out.writeBoolean(false);out.writeBoolean(false);});}
    public static float damage(FpvPackets.Vector p,double[] target,float health,Map<String,Float> stats){
        if(health<=0)return 0;double dx=target[0]-p.x(),dy=target[1]-p.y(),dz=target[2]-p.z(),distance=Math.sqrt(dx*dx+dy*dy+dz*dz);if(distance>=20)return 0;
        double base=distance<=5?Math.max(health,100):100*(20-distance)/15;
        return (float)(base*100/(100+Math.max(-99,stats.getOrDefault("explosion_dmg_factor",0f)))*100/(100+Math.max(-99,stats.getOrDefault("receive_damage_modifier",0f))));
    }
}
