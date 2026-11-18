package szserver.net;

import java.util.*;

/** Legacy character preferences and the native alliance IDs use different plural forms. */
public final class Factions {
    private static final Map<String,String> NATIVE=Map.of("stalker","stalkers","bandit","bandits","merc","merc","freedom","freedom","duty","duty","covenant","covenant");
    public static String normalize(String value){String faction=value.strip().toLowerCase(Locale.ROOT);if(!NATIVE.containsKey(faction))throw new IllegalArgumentException("invalid_faction");return faction;}
    public static String nativeName(String faction){return NATIVE.get(normalize(faction));}
    public static Map<Integer,byte[]> attributes(String faction){String selected=normalize(faction);byte[] value=Wire.bytes(out->out.writeUTF(nativeName(selected)));Map<Integer,byte[]> attributes=new LinkedHashMap<>();attributes.put(104,value);attributes.put(105,Set.of("stalker","bandit").contains(selected)?null:value.clone());return attributes;}
}
