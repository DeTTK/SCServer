package szserver.inventory;

import com.google.gson.*;
import szserver.client.FpvPackets;
import java.nio.*;
import java.security.*;
import java.util.*;

/** Small durable debit marker, independent of the transient drone simulation/lifecycle. */
public record FpvReceipt(UUID character,UUID drone,int slot,FpvPackets.Vector position,long beforeRevision,long afterRevision) {
    public static final String PROGRESS="fpvLastPlacementV1";
    public FpvReceipt {
        Objects.requireNonNull(character);Objects.requireNonNull(drone);Objects.requireNonNull(position);
        if(slot<0||slot>=160||beforeRevision<0||beforeRevision==Long.MAX_VALUE||afterRevision!=beforeRevision+1)throw new IllegalArgumentException("fpv_receipt_bounds");
        if(Math.abs(position.x())>30_000_000||Math.abs(position.y())>30_000_000||Math.abs(position.z())>30_000_000)throw new IllegalArgumentException("fpv_position_out_of_bounds");
    }
    public JsonObject json(){JsonObject r=new JsonObject();r.addProperty("character",character.toString());r.addProperty("drone",drone.toString());r.addProperty("slot",slot);r.addProperty("x",position.x());r.addProperty("y",position.y());r.addProperty("z",position.z());r.addProperty("before",beforeRevision);r.addProperty("after",afterRevision);return r;}
    public JsonObject progress(){JsonObject r=new JsonObject();r.add(PROGRESS,json());return r;}
    private static long integer(JsonObject r,String key){try{return r.get(key).getAsBigDecimal().longValueExact();}catch(RuntimeException malformed){throw new IllegalArgumentException("fpv_receipt_integer",malformed);}}
    public static FpvReceipt read(JsonObject r){
        if(!r.keySet().equals(Set.of("character","drone","slot","x","y","z","before","after")))throw new IllegalArgumentException("fpv_receipt_fields");
        try{return new FpvReceipt(UUID.fromString(r.get("character").getAsString()),UUID.fromString(r.get("drone").getAsString()),Math.toIntExact(integer(r,"slot")),new FpvPackets.Vector(r.get("x").getAsFloat(),r.get("y").getAsFloat(),r.get("z").getAsFloat()),integer(r,"before"),integer(r,"after"));}
        catch(RuntimeException malformed){throw new IllegalArgumentException("fpv_receipt_invalid",malformed);}
    }
    public static FpvReceipt pending(UUID character,JsonObject progress){if(!progress.has(PROGRESS))return null;FpvReceipt receipt=read(progress.getAsJsonObject(PROGRESS));if(!receipt.character().equals(character))throw new IllegalArgumentException("fpv_receipt_character_mismatch");return receipt;}
    public String key(){return key(character,drone);}
    public static String key(UUID character,UUID drone){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ByteBuffer.allocate(32).putLong(character.getMostSignificantBits()).putLong(character.getLeastSignificantBits()).putLong(drone.getMostSignificantBits()).putLong(drone.getLeastSignificantBits()).array()));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
