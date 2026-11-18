package szserver.inventory;

import com.google.gson.*;
import szserver.net.Wire;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Pure physical reload rules from the active legacy server. Native construction and IO belong to callers. */
public final class PhysicalReload {
    public static final String REQUEST="szmods.bridge.VrWeapon",REPLY="szmods.bridge.VrWeaponReply";
    public static final int ENABLE=1,REMOVE=2,DROP=3,TAKE=4,SEAT=5,RACK=6,DISABLE=7,
        SHELL_TAKE=8,SHELL_SEAT=9,PUMP=10,EJECT=11,OPEN=12,CLOSE=13,EMPTY=14,
        CHAMBER_0=15,CHAMBER_1=16,BACK=17,FORWARD=18;
    public enum Mechanism {STRAIGHT_MAG,ROCK_MAG,BOLT_MAG,BELT,SINGLE_BREECH,FUEL,ROCKET,ROTARY_MAG,BREAK_ACTION,LEVER_TUBE,TUBE,TUBE_SEMI,REVOLVER,CELL;
        boolean slotted(){return this==BREAK_ACTION||this==SINGLE_BREECH;}
        boolean tube(){return slotted()||this==TUBE||this==TUBE_SEMI||this==LEVER_TUBE;}
        boolean manual(){return this==TUBE||this==LEVER_TUBE||this==BOLT_MAG||this==SINGLE_BREECH;}
        boolean gated(){return this==BELT||this==REVOLVER||slotted();}
    }
    public record Request(long sequence,int operation,int ammunition) {
        public Request {if(sequence<1||sequence>0xffffffffL||operation<1||operation>18||ammunition<0||ammunition>65535)throw new IllegalArgumentException("invalid_vr_weapon_operation");}
        public static Request read(byte[] bytes)throws IOException {
            if(bytes.length!=11)throw new IOException("invalid_vr_weapon_packet");var in=Wire.input(bytes);
            if(in.readInt()!=0x56525731)throw new IOException("invalid_vr_weapon_packet");
            return new Request(Integer.toUnsignedLong(in.readInt()),in.readUnsignedByte(),in.readUnsignedShort());
        }
    }
    public record Cycle(UUID identity,int phase,int oldAmmo,int oldRounds,int heldAmmo,boolean retained,
                        boolean chambered,boolean opened,int live,int spent,boolean extracted,long sequence) {
        public Cycle {Objects.requireNonNull(identity);if(phase<0||phase>4||oldAmmo<0||oldAmmo>65535||oldRounds<0||oldRounds>10000||heldAmmo<0||heldAmmo>65535||live<0||live>3||spent<0||spent>3||(live&spent)!=0||sequence<0||sequence>0xffffffffL)throw new IllegalArgumentException("invalid_vr_cycle");}
        public boolean canFire(){return phase==0&&!opened&&chambered;}
        public Cycle reconnect(){return new Cycle(identity,phase,oldAmmo,oldRounds,heldAmmo,retained,chambered,opened,live,spent,extracted,0);}
        public Cycle shot(Mechanism m){int bit=m.slotted()?live&-live:0;return new Cycle(identity,phase,oldAmmo,oldRounds,heldAmmo,retained,m.manual()?false:chambered,opened,live&~bit,spent|bit,extracted,sequence);}
        public JsonObject json(){JsonObject j=new JsonObject();j.addProperty("identity",identity.toString());j.addProperty("phase",phase);j.addProperty("oldAmmo",oldAmmo);j.addProperty("oldRounds",oldRounds);j.addProperty("heldAmmo",heldAmmo);j.addProperty("retained",retained);j.addProperty("chambered",chambered);j.addProperty("opened",opened);j.addProperty("live",live);j.addProperty("spent",spent);j.addProperty("extracted",extracted);j.addProperty("sequence",sequence);return j;}
        public static Cycle read(JsonObject j){return new Cycle(UUID.fromString(j.get("identity").getAsString()),j.get("phase").getAsInt(),j.get("oldAmmo").getAsInt(),j.get("oldRounds").getAsInt(),j.get("heldAmmo").getAsInt(),j.get("retained").getAsBoolean(),j.get("chambered").getAsBoolean(),j.get("opened").getAsBoolean(),j.get("live").getAsInt(),j.get("spent").getAsInt(),j.get("extracted").getAsBoolean(),j.get("sequence").getAsLong());}
    }
    public record Result(Cycle cycle,int ammunition,int rounds,int consume,boolean accepted,String reason,boolean disabled,int wirePhase,int wireAmmo) {
        public byte[] reply(Request r){return Wire.dynamic(REPLY,Wire.bytes(out->{out.writeInt(0x56525731);out.writeInt((int)r.sequence);out.writeByte(r.operation);out.writeBoolean(accepted);out.writeByte(wirePhase);out.writeShort(wireAmmo);out.writeShort(rounds);}));}
    }
    public static Map<Integer,Mechanism> catalogue(Path path)throws IOException {
        if(Files.size(path)>65536)throw new IOException("vr_catalogue_limit");Map<Integer,Mechanism> result=new HashMap<>();
        for(JsonElement e:JsonParser.parseString(Files.readString(path)).getAsJsonArray()){JsonObject r=e.getAsJsonObject();int id=r.get("id").getAsInt();if(id<1||id>65535||result.put(id,Mechanism.valueOf(r.get("mechanism").getAsString()))!=null)throw new IOException("invalid_vr_catalogue");}
        return Map.copyOf(result);
    }
    public static Cycle saved(JsonObject gameplay,UUID id){JsonObject states=gameplay.has("physicalReload")?gameplay.getAsJsonObject("physicalReload"):null;return states!=null&&states.has(id.toString())?Cycle.read(states.getAsJsonObject(id.toString())):null;}
    /** Earlier Java records also stored DISABLE as a cycle. Only marked active records auto-activate;
     * legacy held original ammunition remains guarded and recoverable via explicit ENABLE. */
    public static Cycle activeSaved(JsonObject gameplay,UUID id){
        Cycle cycle=saved(gameplay,id);if(cycle==null)return null;
        JsonObject row=gameplay.getAsJsonObject("physicalReload").getAsJsonObject(id.toString());
        return cycle.retained()||row.has("enabled")&&row.get("enabled").getAsBoolean()?cycle:null;
    }
    /** Preserve held original ammunition through crashes; only non-retained history is eligible for eviction. */
    public static JsonObject progress(JsonObject gameplay,UUID id,Cycle value){
        JsonObject states=gameplay.has("physicalReload")?gameplay.getAsJsonObject("physicalReload").deepCopy():new JsonObject();String key=id.toString();
        if(value==null)states.remove(key);else{if(!states.has(key)&&states.size()>=64){String evict=states.entrySet().stream().filter(e->!e.getValue().getAsJsonObject().get("retained").getAsBoolean()).map(Map.Entry::getKey).findFirst().orElseThrow(()->new IllegalArgumentException("vr_retained_magazine_limit"));states.remove(evict);}JsonObject row=value.json();row.addProperty("enabled",true);states.add(key,row);}
        JsonObject result=new JsonObject();result.add("physicalReload",states);return result;
    }
    public static Result apply(Mechanism m,UUID id,int capacity,Set<Integer> compatible,int ammo,int rounds,int available,Request r,Cycle previous) {
        if(capacity<1||capacity>10000||rounds<0||rounds>capacity||available<0||m.slotted()&&capacity>2)throw new IllegalArgumentException("invalid_vr_weapon_capacity");
        Cycle c=previous;
        if(r.operation==ENABLE&&(c==null||c.sequence==0||r.sequence==1||!c.identity.equals(id))){
            if(c!=null&&c.identity.equals(id)&&c.retained){ammo=c.oldAmmo;rounds=c.oldRounds;}
            int live=m.slotted()?(1<<rounds)-1:0,spent=0;
            if(c!=null&&c.identity.equals(id)&&Integer.bitCount(c.live)==rounds){live=c.live;spent=c.spent;}
            c=new Cycle(id,0,0,0,0,false,rounds>0,false,live,spent,false,0);
        }
        if(rounds>capacity)throw new IllegalArgumentException("vr_retained_magazine_capacity_changed");
        if(c==null||!c.identity.equals(id))throw new IllegalArgumentException("vr_weapon_identity_changed");
        if(r.sequence<=c.sequence)throw new IllegalArgumentException("duplicate_vr_operation");
        int phase=c.phase,oldAmmo=c.oldAmmo,oldRounds=c.oldRounds,held=c.heldAmmo,live=c.live,spent=c.spent,consume=0;
        boolean retained=c.retained,chamber=c.chambered,opened=c.opened,extracted=c.extracted,accepted=false;String reason="invalid_vr_reload_phase";int op=r.operation;
        if(op==ENABLE){accepted=true;reason="enabled";}
        else if(op==OPEN&&m.gated()&&!opened&&(phase==0||m==Mechanism.REVOLVER&&(phase==1||phase==3))){opened=true;if(m.slotted()){spent=0;if(m==Mechanism.SINGLE_BREECH)live=0;rounds=Integer.bitCount(live);chamber=false;}accepted=true;reason="action_opened";}
        else if(op==CLOSE&&m.gated()&&opened&&(phase==0||phase==3||m==Mechanism.REVOLVER&&phase==1)){opened=false;if(m.slotted())chamber=rounds>0;accepted=true;reason="action_closed";}
        else if(op==EMPTY&&m==Mechanism.REVOLVER&&opened&&phase==0){rounds=0;phase=1;retained=false;oldRounds=0;chamber=false;accepted=true;reason="cylinder_emptied";}
        else if(op==EMPTY&&m==Mechanism.BREAK_ACTION&&opened&&phase==0){live=spent=rounds=0;chamber=false;accepted=true;reason="chambers_emptied";}
        else if(m.tube()&&op==SHELL_TAKE&&phase==0&&(!m.gated()||opened)&&rounds<capacity&&compatible.contains(r.ammunition)){if(available>0&&(rounds==0||ammo==r.ammunition)){held=r.ammunition;phase=4;accepted=true;reason="shell_taken";}else reason="no_compatible_shell";}
        else if(m.tube()&&op==DROP&&phase==4){phase=0;accepted=true;reason="shell_returned";}
        else if(m.tube()&&(op==SHELL_SEAT||m.slotted()&&(op==CHAMBER_0||op==CHAMBER_1))&&phase==4&&(!m.gated()||opened)&&rounds<capacity){int slot=op==SHELL_SEAT?firstFree(live|spent,capacity):op-CHAMBER_0;if(m.slotted()&&(slot<0||slot>=capacity||((live|spent)&(1<<slot))!=0))reason="chamber_occupied";else if(available>0){ammo=held;rounds++;consume=1;phase=0;if(m.slotted())live|=1<<slot;accepted=true;reason="shell_inserted";}else reason="no_ammunition";}
        else if(op==BACK&&!Set.of(Mechanism.BREAK_ACTION,Mechanism.SINGLE_BREECH,Mechanism.REVOLVER,Mechanism.CELL,Mechanism.FUEL,Mechanism.ROCKET,Mechanism.ROTARY_MAG).contains(m)&&!opened&&(phase==0||phase==3)&&!extracted){if(phase==0&&chamber&&rounds>0)rounds--;chamber=false;extracted=true;accepted=true;reason="action_extracted";}
        else if(op==FORWARD&&!opened&&phase==0&&extracted){extracted=false;chamber=rounds>0;accepted=true;reason="action_chambered";}
        else if((m.manual()||m==Mechanism.TUBE_SEMI)&&op==PUMP&&!opened&&phase==0&&(rounds>0||!chamber)){if(chamber)rounds--;chamber=rounds>0;accepted=true;reason="pumped";}
        else if(!m.tube()&&!Set.of(Mechanism.CELL,Mechanism.FUEL,Mechanism.ROCKET,Mechanism.REVOLVER).contains(m)&&op==EJECT&&!opened&&phase==0&&rounds>0){rounds--;chamber=rounds>0;accepted=true;reason="live_round_ejected";}
        else if(!m.tube()&&op==REMOVE&&(!m.gated()||opened)&&(phase==0||phase==3)){if(phase==0){oldAmmo=ammo;oldRounds=rounds;retained=true;}phase=2;chamber=false;rounds=0;accepted=true;reason="removed";}
        else if(!m.tube()&&op==DROP&&phase==2){oldRounds=0;retained=false;phase=1;accepted=true;reason="dropped";}
        else if(!m.tube()&&op==TAKE&&phase==1&&compatible.contains(r.ammunition)){if(available>0){held=r.ammunition;retained=false;phase=2;accepted=true;reason="taken";}else reason="no_ammunition";}
        else if(!m.tube()&&op==SEAT&&(!m.gated()||opened)&&phase==2){phase=3;accepted=true;reason="seated_requires_bolt";}
        else if(!m.tube()&&op==RACK&&!opened&&phase==3){int loaded=retained?oldRounds:Math.min(capacity,available);if(loaded>0){ammo=retained?oldAmmo:held;rounds=loaded;consume=retained?0:loaded;phase=0;oldRounds=0;retained=false;chamber=true;extracted=false;accepted=true;reason="ready";}else reason="no_ammunition";}
        else if(op==DISABLE){if(retained){ammo=oldAmmo;rounds=oldRounds;}retained=false;oldRounds=0;accepted=true;reason="disabled";}
        Cycle next=new Cycle(id,phase,oldAmmo,oldRounds,held,retained,chamber,opened,live,spent,extracted,r.sequence);
        return new Result(next,ammo,rounds,consume,accepted,reason,op==DISABLE,opened||phase==0&&!chamber?5:phase,m.slotted()?live|(spent<<2):ammo);
    }
    private static int firstFree(int mask,int capacity){for(int i=0;i<Math.min(capacity,2);i++)if((mask&(1<<i))==0)return i;return -1;}
}
