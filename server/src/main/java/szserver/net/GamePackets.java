package szserver.net;

import com.github.luben.zstd.Zstd;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Compatibility boundary for the promoted client wire layout. */
public final class GamePackets {
    public static final String WORLD_KEY="java-arena";
    public static byte[] trajectory(int shooter,double[] origin,double[] end,Integer target){return Wire.dynamic("1103215333",Wire.bytes(out->{out.writeByte(32|(target==null?0:2));out.writeInt(shooter);for(double v:origin)out.writeFloat((float)v);for(double v:end)out.writeFloat((float)v);if(target!=null)out.writeInt(target);out.writeByte(1);out.writeFloat(1);}));}
    private final byte[] ready;
    private final byte[] flatData;
    public GamePackets(Path template)throws Exception{ready=Base64.getMimeDecoder().decode(Files.readString(template));ByteBuffer b=ByteBuffer.wrap(ready);if(ready.length<206||b.getInt(8)<162||b.getInt(8)+45>ready.length)throw new IllegalArgumentException("world_template_layout");
        // Single bedrock layer at Y=0, biome and light arrays in native section order.
        byte[] raw=new byte[12288+256+4];Arrays.fill(raw,0,256,(byte)7);Arrays.fill(raw,8192,10240,(byte)255);flatData=Zstd.compress(raw,3);
    }
    public byte[] ready(double[] pos){byte[] copy=ready.clone();ByteBuffer b=ByteBuffer.wrap(copy);for(int i=0;i<3;i++){b.putDouble(12+i*8,pos[i]);b.putDouble(36+i*8,pos[i]);}double[] box={pos[0]-.3,pos[1],pos[2]-.3,pos[0]+.3,pos[1]+1.8,pos[2]+.3};for(int i=0;i<6;i++)b.putDouble(68+i*8,box[i]);for(int i=0;i<3;i++)b.putDouble(124+i*8,0);b.putInt(166,0).putInt(170,0);return Wire.dynamic("899750365",copy);}
    public byte[] config(){int offset=12+ByteBuffer.wrap(ready).getInt(8);return Arrays.copyOfRange(ready,offset,offset+32);}
    /** A captured ready template's server tick belongs to its old session. */
    public byte[] ready(double[] pos,int serverTick){byte[] frame=ready(pos);ByteBuffer in=ByteBuffer.wrap(frame);try{Wire.varint(in);Wire.varint(in);int name=in.getShort()&65535;in.position(in.position()+name);in.putInt(in.position()+4,serverTick);return frame;}catch(java.io.IOException error){throw new IllegalArgumentException("world_ready_frame",error);}}
    /** Native snapshot camera angles follow its 48-byte bounding box at offsets104/108. */
    public byte[] ready(double[] pos,int serverTick,float yaw,float pitch){if(!Float.isFinite(yaw)||!Float.isFinite(pitch))throw new IllegalArgumentException("world_ready_view");byte[] frame=ready(pos,serverTick);ByteBuffer in=ByteBuffer.wrap(frame);try{Wire.varint(in);Wire.varint(in);int name=in.getShort()&65535;in.position(in.position()+name);in.putFloat(in.position()+116,yaw).putFloat(in.position()+120,pitch);return frame;}catch(java.io.IOException error){throw new IllegalArgumentException("world_ready_frame",error);}}
    public byte[] ready(double[] pos,int serverTick,float yaw,float pitch,byte[] movementConfiguration){
        PlayerMovementConfiguration.validate(movementConfiguration);byte[] frame=ready(pos,serverTick,yaw,pitch);ByteBuffer in=ByteBuffer.wrap(frame);
        try{Wire.varint(in);Wire.varint(in);int name=in.getShort()&65535;in.position(in.position()+name);int body=in.position(),offset=body+12+in.getInt(body+8);System.arraycopy(movementConfiguration,0,frame,offset,32);return frame;}
        catch(java.io.IOException error){throw new IllegalArgumentException("world_ready_frame",error);}
    }
    public byte[] respawn(double[] pos){return Wire.dynamic("895652713",Wire.bytes(o->{o.writeInt(1);o.writeBoolean(false);o.writeInt(0);o.writeInt(0);o.writeInt(0);o.writeInt(3);o.write(new byte[3]);}));}
    public byte[] ready(double[] pos,int serverTick,float yaw,float pitch,byte[] configuration,float scale){
        new playerscale.ScaleValue(scale);byte[] frame=ready(pos,serverTick,yaw,pitch,configuration);if(scale==1)return frame;
        ByteBuffer in=ByteBuffer.wrap(frame);try{Wire.varint(in);Wire.varint(in);int name=in.getShort()&65535;in.position(in.position()+name);int body=in.position();double[] box=new playerscale.ScaleValue(scale).body(pos,.6,1.8);for(int i=0;i<6;i++)in.putDouble(body+68+i*8,box[i]);return frame;}
        catch(java.io.IOException error){throw new IllegalArgumentException("scaled_world_ready_frame",error);}
    }
    public static byte[] respawnOptions(){return Wire.dynamic("1367061301",Wire.bytes(o->{o.writeByte(1);o.writeUTF("spawn");o.writeByte(0);o.writeByte(255);o.writeUTF("Точка возрождения");o.writeBoolean(true);}));}
    private static void instant(java.io.DataOutput o,long millis)throws java.io.IOException{o.writeByte(0);o.writeLong(millis/1000);o.writeInt((int)(millis%1000)*1000000);}
    public static byte[] death(String killer,double[] pos){long now=System.currentTimeMillis();return death(killer,pos,now,now+5000);}
    public static byte[] death(String killer,double[] pos,long now,long available){return Wire.dynamic("1695366012",Wire.bytes(o->{instant(o,now);for(double v:pos)o.writeFloat((float)v);o.writeFloat(0);o.writeByte(0);o.writeByte(255);o.writeUTF("Убит игроком "+killer);o.writeUTF("bullet");o.writeUTF("stalkers");o.writeInt(0);o.writeByte(0);o.writeFloat(0);o.writeFloat(0);o.writeUTF("");o.writeFloat(0);o.writeBoolean(false);o.writeDouble(0);o.writeInt(0);o.writeInt(0);o.writeFloat(0);instant(o,available);o.write(new byte[3]);o.writeInt(0);}));}
    public static byte[] receivedDamage(double[] source){return Wire.dynamic("1549992761",Wire.bytes(out->{out.writeFloat((float)source[0]);out.writeFloat((float)source[2]);out.writeByte(1);}));}
    public static byte[] killNotification(String victim){return Wire.dynamic("422143441",Wire.bytes(out->{out.writeUTF("");out.writeUTF(victim);out.writeInt(0);}));}
    private static void participant(java.io.DataOutput out,String name)throws java.io.IOException{out.writeInt(0);out.writeUTF(name);out.writeUTF("stalkers");out.writeByte(0);}
    public static byte[] killFeed(String victim,String killer){return Wire.dynamic("1839311935",Wire.bytes(out->{participant(out,victim);out.writeBoolean(false);participant(out,killer);}));}
    private static void vector(java.io.DataOutput out,double[] values)throws java.io.IOException{if(values==null||values.length!=3)throw new IllegalArgumentException("corpse_vector");for(double value:values){if(!Double.isFinite(value)||Math.abs(value)>Float.MAX_VALUE)throw new IllegalArgumentException("corpse_vector");out.writeFloat((float)value);}}
    /** Native type30 players/type31 HumanMale NPCs copy the living renderer pose before removal. */
    public static byte[] corpse(int entity,int source,String name,double[] position,float yaw,double[] velocity,double[] impact,double[] impulse,szserver.inventory.ItemStack armor){
        return corpse(entity,source,name,position,yaw,velocity,impact,impulse,armor,null);
    }
    public static byte[] corpse(int entity,int source,String name,double[] position,float yaw,double[] velocity,double[] impact,double[] impulse,szserver.inventory.ItemStack armor,String npcSkin){
        return corpse(entity,source,name,position,yaw,velocity,impact,impulse,armor,npcSkin,1);
    }
    public static byte[] corpse(int entity,int source,String name,double[] position,float yaw,double[] velocity,double[] impact,double[] impulse,szserver.inventory.ItemStack armor,String npcSkin,float scale){
        new playerscale.ScaleValue(scale);
        if(entity<=0||source<0||!Float.isFinite(yaw))throw new IllegalArgumentException("corpse_identity");
        // 1688394027.static multiplies native inherited velocity by 20 before Bullet.
        double[] nativeVelocity=corpseVelocity(velocity);float modelYaw=corpseYaw(yaw);
        byte[] subtype=Wire.bytes(out->{out.writeBoolean(false);out.writeInt(source);out.writeByte(0);out.writeByte(255);out.writeUTF(name);out.writeBoolean(false);out.writeBoolean(false);vector(out,position);out.writeFloat(modelYaw);out.writeFloat(scale);out.writeBoolean(true);out.writeBoolean(true);vector(out,nativeVelocity);vector(out,impulse);vector(out,new double[3]);vector(out,impact==null?position:impact);out.writeBoolean(false);out.writeByte(0);out.writeUTF("");out.write(InventoryPackets.stack(armor==null?null:armor.count(1)));if(npcSkin!=null)out.writeUTF(npcSkin);});
        for(double value:position)if(Math.abs(value)>30_000_001)throw new IllegalArgumentException("corpse_position");
        return Wire.dynamic("1510484702",Wire.bytes(out->{out.writeByte(npcSkin==null?30:31);out.writeByte(angle(modelYaw)^26);out.writeInt(coord(position[1])-(int)2315843772L);out.writeInt((coord(position[2])+716018694)^0xc7a787d8);out.writeInt(Integer.rotateRight(coord(position[0]),15)-1385050365);out.writeInt(entity-1630978953);out.writeByte(ror8(0,3)+211);Wire.varint(out,subtype.length);out.write(subtype);out.write(new byte[2]);}));
    }
    /** Living renderer uses 180 - heading; native Bullet initial pose uses -corpseYaw. */
    public static float corpseYaw(float heading){if(!Float.isFinite(heading))throw new IllegalArgumentException("corpse_heading");float normalized=heading%360;return normalized>=0?normalized-180:normalized+180;}
    public static double[] corpseVelocity(double[] metresPerSecond){
        if(metresPerSecond==null||metresPerSecond.length!=3)throw new IllegalArgumentException("corpse_velocity");
        double[] result=new double[3];for(int i=0;i<3;i++){double v=metresPerSecond[i];if(!Double.isFinite(v)||Math.abs(v)>1000)throw new IllegalArgumentException("corpse_velocity");result[i]=v/20.;}return result;
    }
    public static double[] bulletImpulse(double[] direction,double rawDamage){if(direction==null||direction.length!=3||!Double.isFinite(rawDamage))throw new IllegalArgumentException("corpse_impulse");double length=0;for(double value:direction){if(!Double.isFinite(value))throw new IllegalArgumentException("corpse_impulse");length=Math.hypot(length,value);}if(length<1e-9)return new double[3];double strength=Math.min(3,Math.max(.25,rawDamage*.04));return new double[]{direction[0]/length*strength,direction[1]/length*strength,direction[2]/length*strength};}
    public static byte[] context(UUID id,String locationName){return context(id,locationName,false);}
    public static byte[] context(UUID id,String locationName,boolean proximityVoice){return Wire.dynamic("1009703493",Wire.bytes(o->{o.writeByte(1);o.writeUTF(WORLD_KEY);o.writeByte(0);o.writeByte(0);o.writeByte(0);o.writeByte(255);o.writeUTF(locationName);o.writeInt(1);o.writeLong(id.getMostSignificantBits());o.writeLong(id.getLeastSignificantBits());o.writeInt(0);o.writeByte(0);o.writeByte(0);o.writeFloat(1);o.writeUTF(WORLD_KEY);o.write(new byte[5]);o.writeUTF(WORLD_KEY);o.write(new byte[3]);o.writeBoolean(proximityVoice);}));}
    /** ImGui Scene Editor checks the named developer permission, independently of gameplay mode. */
    public static byte[] developerPermissions(boolean sceneEditor){
        byte[] compressed=Zstd.compress(Wire.bytes(out->{out.writeInt(1);out.writeUTF("scene_editor");out.writeBoolean(sceneEditor);}));
        return Wire.dynamic("1314659692",Wire.bytes(out->{out.writeInt(compressed.length);out.write(compressed);}));
    }
    public static byte[] snapshot(){return Wire.frame(11,Wire.bytes(o->{o.writeInt(1);o.writeByte(3);o.writeByte(0);o.writeInt(0);o.writeInt(3);o.write(new byte[3]);}));}
    public static byte[] velocityReset(){return szserver.client.TeleportPackets.velocityReset();}
    public byte[] chunk(int x,int z){return terrainChunk(chunkRecord(x,z));}
    public byte[] chunkRecord(int x,int z){UUID revision=UUID.nameUUIDFromBytes(("java-arena-v1:"+x+":"+z).getBytes(java.nio.charset.StandardCharsets.UTF_8));return Wire.bytes(o->{o.writeInt(x);o.writeInt(z);o.writeBoolean(true);o.writeInt(1);o.writeInt(0);o.writeLong(revision.getMostSignificantBits());o.writeLong(revision.getLeastSignificantBits());o.writeInt(12544);o.writeInt(flatData.length);o.write(flatData);o.writeByte(0);o.write(new byte[]{1,1,0,0,0,0});o.writeInt(x);o.writeInt(z);o.writeInt(0);});}
    public static byte[] block(int x,int y,int z,int id,int metadata){return Wire.dynamic("1429233302",Wire.bytes(out->{out.writeInt(x);out.writeByte(y);out.writeInt(z);out.writeShort(id);out.writeByte(metadata);}));}
    public static byte[] terrainChunk(byte[] record){return Wire.dynamic("91232058",Wire.bytes(out->{out.writeInt(1);out.write(record);}));}
    private static int coord(double v){return (int)Math.ceil(v*32);}
    private static int angle(float v){return (int)(v*256d/360)&255;}
    private static int ror8(int v,int bits){return ((v>>>bits)|(v<<(8-bits)))&255;}
    public static byte[] spawn(int id,String name,double[] pos,float yaw,float pitch){return Wire.dynamic("348227069",Wire.bytes(o->{o.writeUTF(name);o.writeByte(angle(yaw)^26);o.writeInt(coord(pos[1])-(int)2315843772L);o.writeInt((coord(pos[2])+716018694)^0xc7a787d8);o.writeInt(Integer.rotateRight(coord(pos[0]),15)-1385050365);o.writeInt(id-1630978953);o.writeByte(ror8(angle(pitch),3)+211);o.write(new byte[3]);}));}
    public static byte[] movement(int tick,int id,double[] pos,float yaw,float pitch){return movement(tick,id,pos,yaw,pitch,new byte[0]);}
    public static byte[] movement(int tick,int id,double[] pos,float yaw,float pitch,byte[] animation){return movements(tick,List.of(movementRecord(id,pos,null,yaw,pitch,animation)));}
    /** Native bit 1 resets the remote interpolation window at a teleport boundary. */
    public static byte[] teleportMovement(int tick,int id,double[] pos,float yaw,float pitch){return movements(tick,List.of(movementRecord(id,pos,null,yaw,pitch,new byte[0],true)));}
    /** A record without transforms still advances the native interpolation clock. */
    public static byte[] movementRecord(int id,double[] pos,double[] previous,Float yaw,Float pitch,byte[] animation){
        return movementRecord(id,pos,previous,yaw,pitch,animation,false);
    }
    private static byte[] movementRecord(int id,double[] pos,double[] previous,Float yaw,Float pitch,byte[] animation,boolean snap){
        if(id<2||(yaw==null)!=(pitch==null))throw new IllegalArgumentException("movement_identity_rotation");
        if(pos!=null&&(pos.length!=3||Arrays.stream(pos).anyMatch(v->!Double.isFinite(v)||Math.abs(v)>30_000_001)))throw new IllegalArgumentException("movement_position");
        if(previous!=null&&(previous.length!=3||Arrays.stream(previous).anyMatch(v->!Double.isFinite(v)||Math.abs(v)>30_000_001)))throw new IllegalArgumentException("movement_previous");
        if(yaw!=null&&(!Float.isFinite(yaw)||!Float.isFinite(pitch)))throw new IllegalArgumentException("movement_rotation");
        if(animation==null||animation.length>256)throw new IllegalArgumentException("movement_animation");
        int[] delta=new int[3];boolean relative=pos!=null&&previous!=null;
        if(relative)for(int i=0;i<3;i++){delta[i]=coord(pos[i])-coord(previous[i]);if(delta[i]<-128||delta[i]>127)relative=false;}
        final boolean small=relative;int flags=(pos==null?0:small?32:64)|(yaw==null?0:8)|(animation.length==0?0:16)|(snap?1:0);
        byte[] payload=Wire.bytes(o->{
            if(small){o.writeByte(4);o.writeByte(delta[2]+78);o.writeByte(delta[0]^0x48);o.writeByte((delta[1]^0x51)-113);}
            else if(pos!=null){o.writeByte(31);o.writeInt(coord(pos[2])^0x39a5b0fc);o.writeInt(coord(pos[1])-1598565613);o.writeInt(coord(pos[0])^0x169209b5);}
            if(yaw!=null){o.writeByte(4);o.writeByte((angle(pitch)^80)+92);o.writeByte(angle(yaw)+61);}o.write(animation);
        });
        return Wire.bytes(o->{o.writeByte(200);o.writeByte(flags+3);o.writeInt(id^0x039c785f);Wire.varint(o,payload.length);o.write(payload);});
    }
    public static byte[] movements(int tick,List<byte[]> records){
        if(records.isEmpty()||records.size()>64)throw new IllegalArgumentException("movement_batch_count");
        int size=records.stream().mapToInt(r->r.length).sum();if(size>32768)throw new IllegalArgumentException("movement_batch_bytes");
        return Wire.dynamic("456314467",Wire.bytes(o->{o.writeByte(11);Wire.varint(o,size);o.writeInt(tick-564612337);for(byte[] record:records)o.write(record);}));
    }
    private static int animationAngle(float value){double normalized=(value+180)%360;if(normalized<0)normalized+=360;return (int)(normalized/360*255);}
    public static byte[] animation(int loco,int previous,int mode,double speed,boolean ground,float yaw){int mask=63|(1<<6)|(1<<9)|(1<<11)|(1<<12)|(1<<13)|(1<<23)|(1<<24);return Wire.bytes(o->{o.writeByte(1);Wire.varint(o,mask);o.writeByte(loco);o.writeByte(previous);o.writeByte(mode);o.writeByte((int)(speed/12*255));o.writeByte(127);o.writeByte(127);o.writeByte(127);o.writeBoolean(ground);o.writeByte(animationAngle(yaw));o.writeByte(127);o.writeByte(0);o.writeByte(127);o.writeByte(127);});}
    public byte[] tick(int tick,int ack){return tick(tick,ack,config());}
    public byte[] tick(int tick,int ack,byte[] movementConfiguration){if(movementConfiguration!=null)PlayerMovementConfiguration.validate(movementConfiguration);return Wire.dynamic("540966960",Wire.bytes(o->{o.writeInt(tick);o.writeInt(ack);o.writeBoolean(movementConfiguration==null);if(movementConfiguration!=null)o.write(movementConfiguration);}));}
    public static byte[] attributes(int tick,int id,Map<Integer,byte[]> attrs){byte[] values=Wire.bytes(o->{Wire.varint(o,attrs.size());for(var e:attrs.entrySet()){o.writeShort(e.getKey());o.writeBoolean(e.getValue()==null);if(e.getValue()!=null)o.write(e.getValue());}});byte[] records=Wire.bytes(o->{o.writeByte(73);o.writeInt(Integer.rotateLeft(id-115771960,2)-582719528);o.writeByte(252);Wire.varint(o,values.length);o.write(values);});return Wire.dynamic("456314467",Wire.bytes(o->{o.writeByte(10);o.writeInt((tick+1861859542)^1976320645);Wire.varint(o,records.length);o.write(records);}));}
    public static byte[] vitals(int tick,int id,float hp){return vitals(tick,id,hp,100);}
    public static byte[] vitals(int tick,int id,float hp,float maximum){return attributes(tick,id,Map.of(3,Wire.bytes(o->o.writeFloat(hp)),4,Wire.bytes(o->o.writeFloat(maximum))));}
    public static byte[] vitals(int tick,int id,float hp,float maximum,boolean aiming){return attributes(tick,id,Map.of(3,Wire.bytes(o->o.writeFloat(hp)),4,Wire.bytes(o->o.writeFloat(maximum)),201,new byte[]{(byte)(aiming?1:0)}));}
    public static byte[] effects(int tick,List<CharacterEffects.Effect> removed,Collection<CharacterEffects.Effect> added,Map<Integer,szserver.client.GameItems.Medicine> medicines,Map<String,Integer> ordinals,long now){
        return effects(tick,removed,added,medicines,ordinals,now,1);
    }
    public static byte[] effects(int tick,List<CharacterEffects.Effect> removed,Collection<CharacterEffects.Effect> added,Map<Integer,szserver.client.GameItems.Medicine> medicines,Map<String,Integer> ordinals,long now,int kind){
        if(kind<0||kind>255)throw new IllegalArgumentException("native_effect_kind");
        byte[] payload=Wire.bytes(o->{Wire.varint(o,3);Wire.varint(o,removed.size()+added.size());
            for(var effect:removed){o.writeBoolean(false);Wire.varint(o,effect.id());}
            for(var effect:added){o.writeBoolean(true);Wire.varint(o,effect.id());var medicine=effect.definition(medicines);Map<Integer,Float> bank=new TreeMap<>();medicine.stats().forEach((key,value)->{if(value!=0&&ordinals.containsKey(key))bank.put(ordinals.get(key),value);});Wire.varint(o,bank.size());for(var entry:bank.entrySet()){o.writeByte(entry.getKey());o.writeFloat(entry.getValue());}
                o.writeByte(kind);Wire.varint(o,0);Wire.varint(o,medicine.group()==null?3:4);
                o.writeByte(1);o.writeInt((int)((now-1735689600000L)/250));Wire.varint(o,Math.max(1,(int)Math.ceil((effect.until()-now)/250d)));
                o.writeByte(2);o.writeShort(effect.item());if(medicine.group()!=null){o.writeByte(5);o.writeUTF(medicine.group());}o.writeByte(6);o.writeByte(Math.max(-128,Math.min(127,medicine.priority())));
            }o.writeBoolean(false);
        });return effectRecords(tick,payload);
    }
    public static byte[] effectRecords(int tick,byte[] payload){byte[] records=Wire.bytes(o->{o.writeByte(73);o.writeInt(Integer.rotateLeft(-115771960,2)-582719528);o.writeByte(10);Wire.varint(o,payload.length);o.write(payload);});
        return Wire.dynamic("456314467",Wire.bytes(o->{o.writeByte(10);o.writeInt((tick+1861859542)^1976320645);Wire.varint(o,records.length);o.write(records);}));
    }
    public static byte[] stack(int id,int count,byte[] state){return Wire.bytes(o->{if(count<=0){o.writeBoolean(false);return;}o.writeBoolean(true);o.writeShort(id);o.writeInt(count);o.writeShort(0);o.writeBoolean(state.length>0);o.write(state);o.writeByte(0);});}
    public static byte[] inventory(int aggregate,int container,int item,int count,byte[] state){byte[] raw=Wire.bytes(o->{o.writeByte(aggregate);o.writeShort(1);o.writeByte(aggregate);o.writeByte(container);o.writeShort(0);o.write(stack(item,count,state));});byte[] compressed=Zstd.compress(raw,3);return Wire.dynamic("998343761",Wire.bytes(o->{o.writeShort(0);o.writeShort(-1);o.writeInt(compressed.length);o.write(compressed);}));}
    public static byte[] firearmAppearance(){return HexFormat.of().parseHex("00004e24000000ff0000000000");}
    public static byte[] point(int aggregate,int container,int item,int count,byte[] state){return Wire.dynamic("1090368269",Wire.bytes(o->{o.writeShort(0);o.writeShort(-1);o.writeBoolean(true);o.write(stack(item,count,state));o.writeByte(aggregate);o.writeByte(container);o.writeShort(0);}));}
    public static byte[] counter(int rounds){return Wire.dynamic("1337483670",Wire.bytes(o->{o.writeByte(1);o.writeInt(rounds);}));}
    public static Map<Integer,byte[]> hand(UUID identity,byte[] state,boolean holstered){var attrs=new TreeMap<Integer,byte[]>();attrs.put(89,new byte[]{0,0});attrs.put(91,new byte[]{(byte)(holstered?1:0)});attrs.put(127,new byte[]{(byte)(holstered?0:1)});attrs.put(124,holstered?null:new byte[]{0,6,0,0});attrs.put(125,holstered?null:Wire.bytes(o->{o.writeLong(identity.getMostSignificantBits());o.writeLong(identity.getLeastSignificantBits());}));attrs.put(126,holstered?null:stack(20004,1,state));return attrs;}
    public static byte[] chat(String text){return ChatPackets.system(text,System.currentTimeMillis());}
}
