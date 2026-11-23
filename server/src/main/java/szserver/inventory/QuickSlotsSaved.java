package szserver.inventory;

import com.google.gson.*;
import java.util.*;

/** Bounded native settings payload in the existing character inventory journal. */
public final class QuickSlotsSaved {
    public static final String KEY="nativeQuickSlots";
    public static byte[] read(JsonObject gameplay){
        if(!gameplay.has(KEY))return new byte[0];JsonObject value=gameplay.getAsJsonObject(KEY);if(value.get("version").getAsInt()!=1)throw new IllegalArgumentException("quick_slots_version");String encoded=value.get("data").getAsString();if(encoded.length()>21848)throw new IllegalArgumentException("quick_slots_size");byte[] raw=Base64.getDecoder().decode(encoded);if(raw.length>16384)throw new IllegalArgumentException("quick_slots_size");return raw;
    }
    public static JsonObject progress(byte[] raw){if(raw.length>16384)throw new IllegalArgumentException("quick_slots_size");JsonObject value=new JsonObject();value.addProperty("version",1);value.addProperty("data",Base64.getEncoder().encodeToString(raw));JsonObject progress=new JsonObject();progress.add(KEY,value);return progress;}
    private QuickSlotsSaved(){}
}
