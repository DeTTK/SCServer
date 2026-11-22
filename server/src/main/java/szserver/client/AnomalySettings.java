package szserver.client;

import java.io.*;
import java.util.*;
import szserver.net.Wire;

/** Native anomaly interaction-menu DTO. This RPC contains typed fields, not named NBT. */
public record AnomalySettings(String name,float chance,boolean dropDisabled,String preset,
                              int cooldown,float strength,boolean setVelocity,boolean individual) {
    public static final Set<String> BEHAVIORS=Set.of("carousel","circus","electra","funnel","lighter","tramp","stasis","mirror","simple_anomaly","puh","steam","tesla");
    public AnomalySettings {
        if(name==null||name.length()>256||preset==null||preset.length()>256||!Float.isFinite(chance)||chance<0||chance>1||cooldown<0||cooldown>72000||!Float.isFinite(strength)||strength<0||strength>100)throw new IllegalArgumentException("anomaly_settings_range");
    }
    public static boolean supported(String behavior){return BEHAVIORS.contains(behavior);}
    public static AnomalySettings read(byte[] payload)throws IOException{
        if(payload.length>2048)throw new IOException("anomaly_settings_size");
        try(var in=new DataInputStream(new ByteArrayInputStream(payload))){var result=new AnomalySettings(in.readUTF(),in.readFloat(),in.readBoolean(),in.readUTF(),in.readInt(),in.readFloat(),in.readBoolean(),in.readBoolean());if(in.available()!=0)throw new IOException("anomaly_settings_trailing");return result;}
    }
    public byte[] bytes(){return Wire.bytes(out->{out.writeUTF(name);out.writeFloat(chance);out.writeBoolean(dropDisabled);out.writeUTF(preset);out.writeInt(cooldown);out.writeFloat(strength);out.writeBoolean(setVelocity);out.writeBoolean(individual);});}
    public static AnomalySettings from(String name,byte[] nbt){var m=SceneNbt.read(nbt).values();return new AnomalySettings(name,number(m,"ChanceOfWork",1).floatValue(),number(m,"isArtefactDropDisabled",0).intValue()!=0,text(m,"overriddenPresetId",""),number(m,"cooldown_tick",60).intValue(),number(m,"trampoline_strength",1).floatValue(),number(m,"trampoline_set_velocity",0).intValue()!=0,number(m,"use_individual_cooldown_for_players",0).intValue()!=0);}
    public byte[] apply(byte[] nbt,boolean trampoline){var root=SceneNbt.read(nbt);var m=new LinkedHashMap<>(root.values());m.put("ChanceOfWork",SceneNbt.number(5,chance));m.put("isArtefactDropDisabled",SceneNbt.number(1,dropDisabled?1:0));m.put("overriddenPresetId",SceneNbt.string(preset));if(trampoline){m.put("cooldown_tick",SceneNbt.number(3,cooldown));m.put("trampoline_strength",SceneNbt.number(5,strength));m.put("trampoline_set_velocity",SceneNbt.number(1,setVelocity?1:0));m.put("use_individual_cooldown_for_players",SceneNbt.number(1,individual?1:0));}return SceneNbt.write(new SceneNbt.Root(root.name(),m));}
    private static Number number(Map<String,SceneNbt.Tag> m,String key,Number fallback){var tag=m.get(key);return tag!=null&&tag.value() instanceof Number n?n:fallback;}
    private static String text(Map<String,SceneNbt.Tag> m,String key,String fallback){var tag=m.get(key);return tag!=null&&tag.value() instanceof String s?s:fallback;}
    public record CaptureEcho(UUID identity,int phase){}
    /** 804507492: UUID precedes the phase; this echo grants no simulation authority. */
    public static CaptureEcho captureEcho(byte[] body)throws IOException{if(body.length!=20)throw new IOException("anomaly_force_echo_size");try(var in=Wire.input(body)){UUID id=new UUID(in.readLong(),in.readLong());int phase=in.readInt();if(phase<0||phase>100000)throw new IOException("anomaly_force_echo_phase");return new CaptureEcho(id,phase);}}
    public record Save(UUID identity,AnomalySettings settings){}
    public record Strength(UUID identity,float value){public Strength{if(!Float.isFinite(value)||value<0||value>100)throw new IllegalArgumentException("anomaly_strength_range");}}
    public static Strength strength(byte[] body)throws IOException{if(body.length!=20)throw new IOException("anomaly_strength_size");try(var in=new DataInputStream(new ByteArrayInputStream(body))){return new Strength(new UUID(in.readLong(),in.readLong()),in.readFloat());}}
    public static Save save(byte[] body)throws IOException{if(body.length<20||body.length>2068)throw new IOException("anomaly_save_size");try(var in=new DataInputStream(new ByteArrayInputStream(body))){UUID id=new UUID(in.readLong(),in.readLong());int n=in.readInt();if(n!=in.available())throw new IOException("anomaly_save_length");return new Save(id,read(in.readNBytes(n)));}}
}
