package szserver.inventory;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Loot presets from the client, with persisted shuffle state and legacy compatibility. */
public final class LootboxCatalog {
    public static final String RESOURCE="szserver/inventory/lootbox-tables.json.gz";
    public record Prize(int id,int count,int metadata,JsonObject tags) {}
    public record Draw(List<Prize> prizes,JsonObject progress) {}
    public interface NativeTables {
        boolean has(String name);
        int size();
        Map<String,String> malformed();
        void validate(String name);
        void draw(String name,Random random,List<Prize> output);
    }
    private final ItemCatalog items;
    private final NativeTables nativeTables;
    private final Map<Integer,JsonObject> boxes=new TreeMap<>();
    private final Map<Integer,String> unavailable=new TreeMap<>();
    private final JsonObject loot,bags;
    private final Map<String,String> bagHashes=new HashMap<>();
    public LootboxCatalog(ItemCatalog items,Path root)throws Exception {
        this(items,read(root),null);
    }
    public LootboxCatalog(ItemCatalog items,Path root,NativeTables nativeTables)throws Exception {
        this(items,read(root),nativeTables);
    }
    public LootboxCatalog(ItemCatalog items,JsonObject data) {
        this(items,data,null);
    }
    private LootboxCatalog(ItemCatalog items,JsonObject data,NativeTables nativeTables) {
        this.items=items;
        this.nativeTables=nativeTables;
        loot=data.has("loot")?data.getAsJsonObject("loot"):new JsonObject();bags=data.has("bags")?data.getAsJsonObject("bags"):new JsonObject();
        for(int id=1;id<65536;id++) {
            ItemCatalog.Definition item;try{item=items.get(id);}catch(IllegalArgumentException absent){continue;}
            if(!item.type().equals("lootbox"))continue;
            JsonObject definition=item.properties().deepCopy();boxes.put(id,definition);
            try{validateBox(definition);}catch(IllegalArgumentException error){unavailable.put(id,error.getMessage());}
        }
    }
    private static JsonObject read(Path root)throws Exception {
        Path path=root.resolve("fixtures/lootbox-tables.json.gz");
        InputStream raw=Files.isRegularFile(path)?Files.newInputStream(path):LootboxCatalog.class.getClassLoader().getResourceAsStream(RESOURCE);
        if(raw==null)return new JsonObject();
        try(raw;InputStream zip=new GZIPInputStream(raw)) {
            byte[] bytes=zip.readNBytes(32*1024*1024+1);if(bytes.length>32*1024*1024)throw new IOException("lootbox_resource_limit");
            JsonObject value=JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if(!value.has("version")||value.get("version").getAsInt()!=1)throw new IOException("lootbox_resource_version");return value;
        }
    }
    public boolean isBox(int id){return boxes.containsKey(id);}
    public int key(int id){return box(id).get("key_item_id").getAsInt();}
    public boolean tradeable(int id){return Set.of("none","dupsa").contains(text(box(id),"bind_state","none").toLowerCase(Locale.ROOT));}
    public void available(int id){box(id);if(unavailable.containsKey(id))throw new IllegalArgumentException("lootbox_unavailable:"+unavailable.get(id));}
    public Map<String,Object> metrics(){return Map.of("cases",boxes.size(),"enabled",boxes.size()-unavailable.size(),"unavailable",Map.copyOf(unavailable),"lootTables",loot.size(),"nativeTables",nativeTables==null?0:nativeTables.size(),"nativeUnreadable",nativeTables==null?Map.of():nativeTables.malformed(),"shuffleBags",bags.size());}
    private JsonObject box(int id){JsonObject value=boxes.get(id);if(value==null)throw new IllegalArgumentException("not_a_lootbox");return value;}
    private void validateBox(JsonObject box) {
        Set<String> visiting=new HashSet<>();
        if(box.has("shufflebag"))validateBag(box.get("shufflebag").getAsString(),visiting,0);
        else {JsonElement ref=box.get("loot");if(ref==null)throw new IllegalArgumentException("missing_loot_table");if(ref.isJsonArray())for(var row:ref.getAsJsonArray())validateLoot(row.getAsString().split(":",2)[1]);else validateLoot(ref.getAsString());}
        if(box.has("bonusLoot"))for(var row:box.getAsJsonObject("bonusLoot").entrySet())validateLoot(row.getValue().getAsJsonObject().get("loot").getAsString());
        int key=box.get("key_item_id").getAsInt();if(key!=-1)items.get(key);
    }
    private void validateLoot(String name) {
        if(nativeTables!=null&&nativeTables.has(name)){nativeTables.validate(name);return;}
        JsonObject table=loot.getAsJsonObject(name);if(table==null)throw new IllegalArgumentException("missing_loot_table:"+name);
        JsonArray groups=table.getAsJsonArray("groups");if(groups.size()>128)throw new IllegalArgumentException("loot_group_limit");
        for(var group:groups){JsonObject g=group.getAsJsonObject();number(g,"groupDropProbability",0,128);JsonArray entries=g.getAsJsonArray("entryList");if(entries.size()>8192)throw new IllegalArgumentException("loot_entry_limit");float total=0;for(var entry:entries){JsonObject e=entry.getAsJsonObject();total+=number(e,"weight",0,Float.MAX_VALUE);validatePrize(e);}if(!Float.isFinite(total))throw new IllegalArgumentException("invalid_loot_weight");}
    }
    private void validatePrize(JsonObject row){int id=row.get("id").getAsInt();if(id<=0)return;items.get(id);int count=row.get("stackSize").getAsInt(),variance=integer(row,"stackSizeVariance",0);if(count<1||count>1000000||variance<0||variance>1000000||integer(row,"itemDamage",0)<0||integer(row,"itemDamage",0)>65535)throw new IllegalArgumentException("invalid_loot_stack");}
    private void validateBag(String name,Set<String> visiting,int depth) {
        if(depth>16||!visiting.add(name))throw new IllegalArgumentException("shufflebag_cycle");JsonArray bag=bags.getAsJsonArray(name);if(bag==null||bag.isEmpty()||bag.size()>4096)throw new IllegalArgumentException("missing_shufflebag:"+name);
        long total=0;for(var entry:bag){JsonObject e=entry.getAsJsonObject();int amount=integer(e,"amount",0);if(amount<1)throw new IllegalArgumentException("shufflebag_amount");total+=amount;if(e.has("stack"))validatePrize(e.getAsJsonObject("stack"));else if(e.has("loot"))validateLoot(e.get("loot").getAsString());else if(e.has("bag"))validateBag(e.get("bag").getAsString(),visiting,depth+1);else throw new IllegalArgumentException("shufflebag_entry");}
        if(total>Integer.MAX_VALUE)throw new IllegalArgumentException("shufflebag_amount_limit");visiting.remove(name);
        try{bagHashes.put(name,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bag.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));}catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
    public Draw draw(int id,int count,String rank,JsonObject previous,Random random) {
        available(id);if(count<1||count>500)throw new IllegalArgumentException("lootbox_count");JsonObject box=box(id),state=previous.deepCopy();
        JsonObject bagState=state.has("bags")?state.getAsJsonObject("bags"):new JsonObject(),bonus=state.has("bonus")?state.getAsJsonObject("bonus"):new JsonObject();
        List<Prize> prizes=new ArrayList<>();
        for(int n=0;n<count;n++) {
            if(box.has("shufflebag"))bag(box.get("shufflebag").getAsString(),bagState,random,prizes,0);
            else table(select(box.get("loot"),rank),random,prizes);
            if(box.has("bonusLoot"))for(var entry:box.getAsJsonObject("bonusLoot").entrySet()) {
                String key=id+":"+entry.getKey();JsonObject rule=entry.getValue().getAsJsonObject();int misses=integer(bonus,key,0),guarantee=integer(rule,"guaranteedRoll",10000);JsonArray probabilities=rule.getAsJsonArray("probability");if(probabilities.isEmpty()||misses<0||misses>1000000)throw new IllegalArgumentException("lootbox_bonus_state");
                float chance=probabilities.get(Math.min(misses,probabilities.size()-1)).getAsFloat();if(!Float.isFinite(chance)||chance<0||chance>1)throw new IllegalArgumentException("lootbox_bonus_probability");
                boolean won=misses+1>=guarantee||random.nextFloat()<chance;
                if(won)table(rule.get("loot").getAsString(),random,prizes);bonus.addProperty(key,won?0:misses+1);
            }
            if(prizes.size()>4096)throw new IllegalArgumentException("lootbox_reward_limit");
        }
        state.add("bags",bagState);state.add("bonus",bonus);state.addProperty("opened",Math.addExact(state.has("opened")?state.get("opened").getAsLong():0,count));
        return new Draw(List.copyOf(prizes),state);
    }
    private static String select(JsonElement ref,String rank) {
        if(!ref.isJsonArray())return ref.getAsString();List<String> ranks=List.of("picklock","newbie","stalker","veteran","master","legend");int level=ranks.indexOf(rank.toLowerCase(Locale.ROOT));if(level<0)throw new IllegalArgumentException("lootbox_rank");String selected=null;
        for(var e:ref.getAsJsonArray()){String[] parts=e.getAsString().split(":",2);if(parts.length==1){selected=parts[0];continue;}String[] range=parts[0].toLowerCase(Locale.ROOT).split("\\.\\.");int first=ranks.indexOf(range[0]),last=ranks.indexOf(range[range.length-1]);if(level>=first&&level<=last)selected=parts[1];}
        if(selected==null)throw new IllegalArgumentException("lootbox_rank_table_missing");return selected;
    }
    private void table(String name,Random random,List<Prize> prizes) {
        if(nativeTables!=null&&nativeTables.has(name)){nativeTables.draw(name,random,prizes);return;}
        JsonObject table=loot.getAsJsonObject(name);if(table==null)throw new IllegalArgumentException("missing_loot_table:"+name);
        for(var group:table.getAsJsonArray("groups")){JsonObject g=group.getAsJsonObject();JsonArray entries=g.getAsJsonArray("entryList");for(float left=g.get("groupDropProbability").getAsFloat();left>0;left-=1){if(random.nextFloat()>left||entries.isEmpty())continue;float total=0;for(var entry:entries)total+=entry.getAsJsonObject().get("weight").getAsFloat();float pick=random.nextFloat()*total,sum=0;JsonObject selected=entries.get(entries.size()-1).getAsJsonObject();for(var entry:entries){JsonObject e=entry.getAsJsonObject();sum+=e.get("weight").getAsFloat();if(sum>=pick){selected=e;break;}}prize(selected,random,prizes);}}
    }
    private void bag(String name,JsonObject state,Random random,List<Prize> prizes,int depth) {
        if(depth>16)throw new IllegalArgumentException("shufflebag_depth");JsonArray papers=bags.getAsJsonArray(name);String hash=bagHashes.get(name);JsonObject saved=state.has(name)?state.getAsJsonObject(name):new JsonObject();
        if(saved.has("hash")&&!saved.get("hash").getAsString().equals(hash))throw new IllegalArgumentException("shufflebag_definition_changed");
        JsonArray remaining=saved.has("remaining")?saved.getAsJsonArray("remaining"):new JsonArray();if(remaining.isEmpty())for(var paper:papers)remaining.add(paper.getAsJsonObject().get("amount").getAsInt());
        if(remaining.size()!=papers.size())throw new IllegalArgumentException("shufflebag_state_size");int total=0;for(int n=0;n<remaining.size();n++){int count=remaining.get(n).getAsInt();if(count<0||count>papers.get(n).getAsJsonObject().get("amount").getAsInt())throw new IllegalArgumentException("shufflebag_state_amount");total=Math.addExact(total,count);}
        if(total==0){for(int n=0;n<papers.size();n++)remaining.set(n,papers.get(n).getAsJsonObject().get("amount"));for(var value:remaining)total=Math.addExact(total,value.getAsInt());}
        int pick=random.nextInt(total),index=0;while(pick>=remaining.get(index).getAsInt()){pick-=remaining.get(index).getAsInt();index++;}
        remaining.set(index,new JsonPrimitive(remaining.get(index).getAsInt()-1));saved.addProperty("hash",hash);saved.add("remaining",remaining);state.add(name,saved);
        JsonObject paper=papers.get(index).getAsJsonObject();if(paper.has("stack"))prize(paper.getAsJsonObject("stack"),random,prizes);else if(paper.has("loot"))table(paper.get("loot").getAsString(),random,prizes);else bag(paper.get("bag").getAsString(),state,random,prizes,depth+1);
    }
    private void prize(JsonObject row,Random random,List<Prize> prizes){int id=row.get("id").getAsInt();if(id<=0)return;int variance=integer(row,"stackSizeVariance",0),count=Math.addExact(row.get("stackSize").getAsInt(),variance>0?random.nextInt(variance):0);prizes.add(new Prize(id,count,integer(row,"itemDamage",0),row.has("tag")?row.getAsJsonObject("tag").deepCopy():new JsonObject()));}
    private static int integer(JsonObject object,String name,int fallback){return object.has(name)?object.get(name).getAsInt():fallback;}
    private static String text(JsonObject object,String name,String fallback){return object.has(name)?object.get(name).getAsString():fallback;}
    private static float number(JsonObject object,String name,float min,float max){float value=object.get(name).getAsFloat();if(!Float.isFinite(value)||value<min||value>max)throw new IllegalArgumentException("lootbox_numeric_field:"+name);return value;}
}
