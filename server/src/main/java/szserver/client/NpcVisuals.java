package szserver.client;

import com.google.gson.*;
import szserver.net.*;
import szserver.world.World;
import java.io.*;import java.nio.*;import java.nio.file.*;import java.security.*;import java.util.*;

/** Original HumanMale profiles, exported off-line. No native loading or disk work on a gameplay tick. */
public final class NpcVisuals {
    public record Profile(int index,String key,String skin,String animations,int type,int uuidOffset,byte[] data) {
        public Profile {data=data.clone();if(index<0||index>255||type!=24||uuidOffset<4||uuidOffset>data.length-16||data.length>8192)throw new IllegalArgumentException("npc_profile_bounds");}
        @Override public byte[] data(){return data.clone();}
        public byte[] forIdentity(UUID id){byte[] bytes=data();ByteBuffer.wrap(bytes).putLong(uuidOffset,id.getMostSignificantBits()).putLong(uuidOffset+8,id.getLeastSignificantBits());return bytes;}
    }
    private static final long PREFIX=0x6e70632d00004000L,MASK=~(255L<<16);
    private static volatile List<Profile> profiles;
    private static byte[] fists;
    public static synchronized void prepare(Path runtime)throws Exception {
        if(profiles!=null)return;
        JsonObject json;try(var in=NpcVisuals.class.getResourceAsStream("npc-visual-profiles.json")){if(in==null)throw new IOException("npc_profiles_missing");byte[] raw=in.readNBytes(65537);if(raw.length>65536)throw new IOException("npc_profiles_limit");json=JsonParser.parseString(new String(raw,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();}
        if(json.get("version").getAsInt()!=1||!json.get("nativeProfileClass").getAsString().equals("936207013"))throw new IOException("npc_profiles_version");
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(RuntimeFiles.read(runtime.resolve("classes/936207013.class"))));if(!sha.equals(json.get("nativeProfileSha256").getAsString()))throw new IOException("npc_profiles_runtime_changed");
        var result=new ArrayList<Profile>();for(var value:json.getAsJsonArray("profiles")){var r=value.getAsJsonObject();var p=new Profile(r.get("index").getAsInt(),r.get("key").getAsString(),r.get("skin").getAsString(),r.get("animations").getAsString(),r.get("type").getAsInt(),r.get("uuidOffset").getAsInt(),Base64.getDecoder().decode(r.get("data").getAsString()));if(p.index()!=result.size())throw new IOException("npc_profile_order");result.add(p);}
        if(result.size()!=10)throw new IOException("npc_profile_count");fists=Base64.getDecoder().decode(json.get("nativeFists").getAsString());if(fists.length<8||fists.length>64||ByteBuffer.wrap(fists).getInt()!=21059)throw new IOException("npc_fists_appearance");profiles=List.copyOf(result);
    }
    public static List<Profile> profiles(){var value=profiles;if(value==null)throw new IllegalStateException("npc_profiles_not_prepared");return value;}
    /** Publish a worker-prepared profile; no native decoding on a gameplay owner. */
    public static synchronized void install(Profile profile){var next=new ArrayList<>(profiles());next.removeIf(p->p.index()==profile.index());if(next.stream().anyMatch(p->p.key().equals(profile.key())))throw new IllegalArgumentException("npc_profile_duplicate_key");next.add(profile);next.sort(Comparator.comparingInt(Profile::index));profiles=List.copyOf(next);}
    public static UUID identity(String key){Profile p=profiles().stream().filter(v->v.key().equals(key)).findFirst().orElseThrow(()->new IllegalArgumentException("npc_preset_unknown: "+key));UUID random=UUID.randomUUID();return new UUID(PREFIX|((long)p.index()<<16),random.getLeastSignificantBits());}
    public static boolean managed(String id){try{return (UUID.fromString(id).getMostSignificantBits()&MASK)==PREFIX;}catch(IllegalArgumentException e){return false;}}
    public static Profile profile(String id){
        UUID uuid=UUID.fromString(id);if(ZombieHorde.owns(id))return profiles().get(1+Math.floorMod(uuid.hashCode(),6));
        if(!managed(id))return null;int index=(int)((uuid.getMostSignificantBits()>>>16)&255);return profiles().stream().filter(p->p.index()==index).findFirst().orElseThrow(()->new IllegalArgumentException("npc_identity_profile"));
    }
    public static byte[] spawn(int entity,String name,double[] position,float yaw,float pitch,World.View actor){
        return spawn(entity,name,position,yaw,pitch,actor,0);
    }
    public static byte[] spawn(int entity,String name,double[] position,float yaw,float pitch,World.View actor,int tick){
        Profile p=profile(actor.id());if(p==null)return GamePackets.spawn(entity,name,position,yaw,pitch);
        byte[] spawn=Wire.dynamic("1510484702",Wire.bytes(out->{byte[] data=p.forIdentity(UUID.fromString(actor.id()));out.writeByte(p.type());out.writeByte(angle(yaw)^26);out.writeInt(coord(position[1])-(int)2315843772L);out.writeInt((coord(position[2])+716018694)^0xc7a787d8);out.writeInt(Integer.rotateRight(coord(position[0]),15)-1385050365);out.writeInt(entity-1630978953);out.writeByte(ror(angle(pitch),3)+211);Wire.varint(out,data.length);out.write(data);out.write(new byte[2]);}));
        // The zombie graph has a separate Fists branch. An empty hand selects NONE and cannot punch.
        // 21059 uses the native melee appearance codec (names + attachments), even for empty/default state.
        return p.animations().equals("npcZombie")?Wire.concat(spawn,GamePackets.attributes(tick,entity,Map.of(27,fists.clone()))):spawn;
    }
    /** Attribute 201 belongs to player aiming and is not declared by the native NPC entity. */
    public static byte[] vitals(int tick,int entity,float hp,float maximum,boolean aiming,World.View actor){return profile(actor.id())==null?GamePackets.vitals(tick,entity,hp,maximum,aiming):GamePackets.vitals(tick,entity,hp,maximum);}
    /** Player slots and native NPC equipment have different attribute IDs, but share the original stack codec. */
    public static Map<Integer,byte[]> npcEquipment(Map<Integer,byte[]> appearance){
        Map<Integer,byte[]> result=new LinkedHashMap<>();
        for(var mapping:Map.of(56,26,61,27,58,28).entrySet())if(appearance.containsKey(mapping.getKey()))result.put(mapping.getValue(),appearance.get(mapping.getKey()));
        return Collections.unmodifiableMap(result);
    }
    public static byte[] attributes(int tick,int entity,Map<Integer,byte[]> values,World.View actor){return GamePackets.attributes(tick,entity,profile(actor.id())==null?values:npcEquipment(values));}
    private static int coord(double v){return (int)Math.ceil(v*32);}
    private static int angle(float v){return (int)(v*256d/360)&255;}
    private static int ror(int v,int bits){return ((v>>>bits)|(v<<(8-bits)))&255;}
    private NpcVisuals(){}
}
