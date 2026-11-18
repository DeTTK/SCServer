package szserver.world;

import java.util.*;
import szserver.inventory.ItemStack;

/** Worker-prepared native equipment roots and wire attributes; immutable on both owners. */
public record ActorPresentation(Map<Integer,String> roots,Map<Integer,String> attributes) {
    public static final ActorPresentation EMPTY=new ActorPresentation(Map.of(),Map.of());
    public ActorPresentation {
        Objects.requireNonNull(roots);Objects.requireNonNull(attributes);
        if(roots.size()>3||attributes.size()>18)throw new IllegalArgumentException("actor_presentation_size");
        int bytes=0;
        for(var entry:roots.entrySet()){
            if(!Set.of(1,3,6).contains(entry.getKey())||entry.getValue()==null||entry.getValue().length()>40000)throw new IllegalArgumentException("actor_presentation_root");
            var item=ItemStack.read(com.google.gson.JsonParser.parseString(entry.getValue()).getAsJsonObject());
            if(item.count()!=1)throw new IllegalArgumentException("actor_presentation_count");
            bytes+=entry.getValue().length();
        }
        for(var entry:attributes.entrySet()){
            int key=entry.getKey();String value=entry.getValue();
            if(!(key>=55&&key<=66||key==89||key==91||key>=124&&key<=127)||value==null||value.length()>12000)throw new IllegalArgumentException("actor_presentation_attribute");
            byte[] raw=Base64.getDecoder().decode(value);if(!Base64.getEncoder().encodeToString(raw).equals(value))throw new IllegalArgumentException("actor_presentation_encoding");
            bytes+=raw.length;
        }
        if(bytes>100000)throw new IllegalArgumentException("actor_presentation_budget");
        roots=Map.copyOf(roots);attributes=Map.copyOf(attributes);
    }
    public static ActorPresentation prepared(Map<Integer,ItemStack> roots,Map<Integer,byte[]> attributes){
        Map<Integer,String> saved=new TreeMap<>(),encoded=new TreeMap<>();
        roots.forEach((key,value)->saved.put(key,value.json().toString()));
        attributes.forEach((key,value)->encoded.put(key,value==null?"":Base64.getEncoder().encodeToString(value)));
        return new ActorPresentation(saved,encoded);
    }
    /** Empty encoded values are the native nullable-attribute deletion marker. */
    public Map<Integer,byte[]> wire(){
        Map<Integer,byte[]> result=new TreeMap<>();
        attributes.forEach((key,value)->result.put(key,value.isEmpty()?null:Base64.getDecoder().decode(value)));
        return result;
    }
}
