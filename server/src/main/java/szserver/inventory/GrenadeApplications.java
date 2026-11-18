package szserver.inventory;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Target-local durable application ledger. Retirement requires the authoritative area-store view. */
public final class GrenadeApplications {
    public static final String KEY="grenadeApplications";
    public record Key(UUID projectile,int profile){public Key{Objects.requireNonNull(projectile);if(profile<0||profile>=16)throw new IllegalArgumentException("grenade_application_profile");}}
    private record Entry(Key key,String source){}
    public record Claim(GrenadeApplications after,boolean applied){}
    private final Map<Key,Entry> entries;
    public GrenadeApplications(){this(Map.of());}
    private GrenadeApplications(Map<Key,Entry> entries){if(entries.size()>GrenadeAreaStore.LIMIT)throw new IllegalArgumentException("grenade_application_limit");this.entries=Map.copyOf(entries);}
    public boolean contains(UUID projectile,int profile){return entries.containsKey(new Key(projectile,profile));}
    public int size(){return entries.size();}
    /** Call on the preparation worker. Same UUID with a changed durable source is rejected. */
    public Claim claim(GrenadeAreaStore.Activation source,int profile,Set<UUID> live)throws Exception{
        if(profile>=source.profiles().size()||!live.contains(source.projectile()))throw new IllegalArgumentException("grenade_application_source");
        Key key=new Key(source.projectile(),profile);String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.json().toString().getBytes(StandardCharsets.UTF_8)));
        Entry previous=entries.get(key);if(previous!=null){if(!previous.source().equals(fingerprint))throw new IllegalArgumentException("grenade_application_source_changed");return new Claim(this,false);}
        Map<Key,Entry> retained=new LinkedHashMap<>();entries.forEach((k,v)->{if(live.contains(k.projectile()))retained.put(k,v);});
        if(retained.size()>=GrenadeAreaStore.LIMIT)throw new IllegalArgumentException("grenade_application_limit");retained.put(key,new Entry(key,fingerprint));return new Claim(new GrenadeApplications(retained),true);
    }
    public JsonObject json(){JsonObject root=new JsonObject();root.addProperty("version",1);JsonArray rows=new JsonArray();entries.values().stream().sorted(Comparator.comparing((Entry e)->e.key().projectile()).thenComparingInt(e->e.key().profile())).forEach(e->{JsonObject row=new JsonObject();row.addProperty("projectile",e.key().projectile().toString());row.addProperty("profile",e.key().profile());row.addProperty("source",e.source());rows.add(row);});root.add("entries",rows);return root;}
    public static GrenadeApplications read(JsonObject progress){
        if(!progress.has(KEY))return new GrenadeApplications();JsonObject value=progress.getAsJsonObject(KEY);
        if(!value.keySet().equals(Set.of("version","entries"))||GrenadeReceipt.integer(value,"version")!=1)throw new IllegalArgumentException("grenade_application_version");
        JsonArray rows=value.getAsJsonArray("entries");if(rows.size()>GrenadeAreaStore.LIMIT)throw new IllegalArgumentException("grenade_application_limit");Map<Key,Entry> entries=new LinkedHashMap<>();
        for(JsonElement raw:rows){JsonObject row=raw.getAsJsonObject();if(!row.keySet().equals(Set.of("projectile","profile","source")))throw new IllegalArgumentException("grenade_application_fields");Key key=new Key(UUID.fromString(row.get("projectile").getAsString()),Math.toIntExact(GrenadeReceipt.integer(row,"profile")));String fingerprint=row.get("source").getAsString();if(!fingerprint.matches("[0-9a-f]{64}")||entries.putIfAbsent(key,new Entry(key,fingerprint))!=null)throw new IllegalArgumentException("grenade_application_entry");}
        return new GrenadeApplications(entries);
    }
}
