package szserver.client;

import szserver.net.*;
import szserver.world.*;
import szserver.inventory.*;
import java.io.*;
import java.util.*;
import java.nio.file.*;

/** Installed throwable protocol. Profiles are prepared by the native parser before listening. */
public final class GrenadePackets {
    /** Installed 423259911 registry: 10 = 1471738856 (grenade); 9 is a different actor. */
    public static final int PROJECTILE_TYPE=10;
    public static final String START="1815459149",THROW="1830478064",SPAWN="1510484702",UPDATE="1246769498",PREDICTION="1395748633";
    public static void verifyRelease(Path root,Path runtime)throws Exception {prepareDirected(root);for(String fixture:List.of("native-grenades.json","native-grenade-cooking.json","native-grenade-areas.json","native-grenade-fire.json","native-grenade-continuous.json","native-grenade-fast.json")){var manifest=com.google.gson.JsonParser.parseString(RuntimeFiles.readString(root.resolve("fixtures/"+fixture))).getAsJsonObject();for(var entry:manifest.getAsJsonObject("classHashes").entrySet()){String name=entry.getKey();if(!name.matches("[0-9]+"))throw new IOException("grenade_release_class");String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(RuntimeFiles.read(runtime.resolve("classes").resolve(name+".class"))));if(!digest.equals(entry.getValue().getAsString()))throw new IOException("grenade_native_release_changed:"+name);}}}
    private static volatile Set<Integer> directedItems=Set.of();
    /** Native launcher ammunition uses the directed renderer; hand grenades retain their tumble. */
    private static void prepareDirected(Path root)throws IOException {
        var definitions=com.google.gson.JsonParser.parseString(RuntimeFiles.readString(root.resolve("fixtures/item-definitions.json"))).getAsJsonObject();Set<Integer> result=new HashSet<>();
        for(var entry:definitions.entrySet()){var value=entry.getValue().getAsJsonObject();if(value.has("hand_use")&&!value.get("hand_use").getAsBoolean()&&value.has("type")&&Set.of("grenade","explosive").contains(value.get("type").getAsString()))result.add(Integer.parseInt(entry.getKey()));}
        directedItems=Set.copyOf(result);
    }
    public static boolean directed(int item){return directedItems.contains(item);}
    /** Matches 267674355.static: the model yaw is atan2(vx,vz), pitch is -atan2(vy,horizontal). */
    public static float[] heading(World.Vec velocity){if(velocity==null)return new float[]{0,0};return new float[]{(float)Math.toDegrees(Math.atan2(velocity.x(),velocity.z())),(float)-Math.toDegrees(Math.atan2(velocity.y(),Math.hypot(velocity.x(),velocity.z())))};}
    public record Throw(int charge,int prediction,float yaw,float pitch){}
    public record Profile(int lifetime,int chargeTicks,float speed,float pitchOffset,float absolutePitch,boolean absolute,
                          float gravity,float drag,float bounce,float friction,byte[] properties) {
        public Profile {for(float f:new float[]{speed,pitchOffset,absolutePitch,gravity,drag,bounce,friction})if(!Float.isFinite(f))throw new IllegalArgumentException("grenade_resource_nonfinite");if(lifetime<1||lifetime>12000||chargeTicks<0||chargeTicks>12000||speed<0||speed>Grenades.MAX_COMPONENT_SPEED/20||gravity>0||gravity<-1||drag<0||drag>1||bounce<0||bounce>1||friction<0||friction>1)throw new IllegalArgumentException("grenade_resource_budget");properties=properties.clone();}
        @Override public byte[] properties(){return properties.clone();}
        public Grenades.Flight flight(){return new Grenades.Flight(lifetime,-gravity*400,bounce,friction,drag);}
        public World.Vec velocity(Throw request){double yaw=Math.toRadians(request.yaw()),pitch=Math.toRadians(Math.max(-90,absolute?absolutePitch:request.pitch()+pitchOffset));double s=speed*.75*20;return new World.Vec(-Math.sin(yaw)*Math.cos(pitch)*s,-Math.sin(pitch)*s,Math.cos(yaw)*Math.cos(pitch)*s);}
    }
    public static int start(byte[] body)throws IOException{if(body.length!=4)throw new IOException("grenade_start_size");int mode=Wire.input(body).readInt();if(mode<0||mode>2)throw new IOException("grenade_start_mode");return mode;}
    public static Throw read(byte[] body)throws IOException{if(body.length!=16)throw new IOException("grenade_throw_size");var in=Wire.input(body);int charge=in.readInt(),prediction=in.readInt();float yaw=in.readFloat(),pitch=in.readFloat();if(charge<0||charge>12000||prediction< -1||!Float.isFinite(yaw)||Math.abs(yaw)>360000||!Float.isFinite(pitch)||Math.abs(pitch)>90)throw new IOException("grenade_throw_bounds");return new Throw(charge,prediction,yaw,pitch);}
    /** Native 1184331861: hand offset around the posture eye, including the forward quarter metre. */
    public static World.Vec origin(double[] eye,float yaw,float pitch){double y=Math.toRadians(yaw),p=Math.toRadians(pitch);return new World.Vec(eye[0]-Math.cos(y)*.1-Math.sin(y)*.25*Math.cos(p),eye[1]-.15,eye[2]-Math.sin(y)*.1+Math.cos(y)*.25*Math.cos(p));}
    private static int angle(float v){return (int)(v*256d/360)&255;}
    private static int coord(double v){return (int)Math.ceil(v*32);}
    public static byte[] subtype(int item,Profile profile){return Wire.bytes(out->{out.writeInt(item);out.writeBoolean(true);out.write(profile.properties());out.writeBoolean(directed(item));out.writeInt(-1);out.writeBoolean(false);out.writeInt(-1);out.writeFloat(3);out.writeByte(-1);});}
    public static byte[] spawn(int entity,int item,World.Vec p,Profile profile){return spawn(entity,item,p,profile,null);}
    public static byte[] spawn(int entity,int item,World.Vec p,Profile profile,World.Vec velocity){byte[] subtype=subtype(item,profile);float[] direction=directed(item)?heading(velocity):new float[]{0,0};return Wire.dynamic(SPAWN,Wire.bytes(out->{out.writeByte(PROJECTILE_TYPE);out.writeByte(angle(direction[0])^26);out.writeInt(coord(p.y())-(int)2315843772L);out.writeInt((coord(p.z())+716018694)^0xc7a787d8);out.writeInt(Integer.rotateRight(coord(p.x()),15)-1385050365);out.writeInt(entity-1630978953);out.writeByte(((angle(direction[1])>>>3)|(angle(direction[1])<<5))+211);Wire.varint(out,subtype.length);out.write(subtype);out.write(new byte[2]);}));}

    public static byte[] update(int entity,World.Vec p,World.Vec v){return Wire.dynamic(UPDATE,Wire.bytes(out->{out.writeInt(entity);out.writeFloat((float)p.x());out.writeFloat((float)p.y());out.writeFloat((float)p.z());out.writeFloat((float)v.x()/20);out.writeFloat((float)v.y()/20);out.writeFloat((float)v.z()/20);}));}
    /** Native projectile tick owns frame interpolation and derives directed heading from velocity.
     * Feeding its actor into the player spline creates a second position writer and visible rewinds. */
    public static byte[] update(int entity,World.Vec p,World.Vec v,int tick){return update(entity,p,v);}
    public static byte[] update(int entity,World.Vec p,World.Vec v,int tick,int item){return update(entity,p,v);}
    public static byte[] prediction(int entity,int prediction){return Wire.dynamic(PREDICTION,Wire.bytes(out->{out.writeInt(entity);out.writeInt(prediction);}));}
    public static byte[] remove(int entity){return Wire.frame(2028,Wire.bytes(out->out.writeInt(entity)));}
    public static float damage(ExplosiveProfile profile,World.Vec origin,double[] feet,Map<String,Float> stats){if(!profile.kind().equals("FRAG"))return 0;double dx=feet[0]-origin.x(),dy=feet[1]+.9-origin.y(),dz=feet[2]-origin.z(),d=Math.sqrt(dx*dx+dy*dy+dz*dz);if(d>=profile.radius()||profile.radius()==0)return 0;double fraction=Math.max(0,Math.min(1,(d-profile.minimumRadius())/Math.max(.001,profile.radius()-profile.minimumRadius())));double raw=profile.strength()+(profile.minimumStrength()-profile.strength())*fraction;return (float)(Math.max(0,raw)*100/(100+Math.max(-95,stats.getOrDefault("explosion_dmg_factor",0f)))*CombatModifiers.received(stats));}
    public record Damage(float primary,float secondary){public float total(){return primary+secondary;}}
    /** Same radial falloff for both channels; secondary uses its own native resistance bank. */
    public static Damage damageChannels(ExplosiveProfile profile,BlastRules rules,World.Vec origin,double[] feet,Map<String,Float> stats){
        if(!profile.kind().equals("FRAG"))return new Damage(0,0);double dx=feet[0]-origin.x(),dy=feet[1]+.9-origin.y(),dz=feet[2]-origin.z(),d=Math.sqrt(dx*dx+dy*dy+dz*dz);if(d>=profile.radius()||profile.radius()==0)return new Damage(0,0);
        double fraction=Math.max(0,Math.min(1,(d-profile.minimumRadius())/Math.max(.001,profile.radius()-profile.minimumRadius())));float primary=(float)Math.max(0,profile.strength()+(profile.minimumStrength()-profile.strength())*fraction),secondary=(float)(rules.secondaryAmount()*(1-fraction)),received=(float)CombatModifiers.received(stats);
        return new Damage(primary*BlastRules.factor(rules.primary(),stats)*received,secondary*BlastRules.factor(rules.secondary(),stats)*received);
    }
    private GrenadePackets(){}
}
