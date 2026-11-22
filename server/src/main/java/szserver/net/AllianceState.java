package szserver.net;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Persistent, editable alliance menu configuration (packet 1667).
 *
 * <p>Every alliance keeps its own readmission/transfer fee and six bonus percentages. The values are
 * stored in {@code data/alliance-settings.json}, can be edited by hand and applied in game with
 * {@code /alliance reload}, or changed live with {@code /alliance <faction> <key> <value>}. Bonuses
 * can be hidden with {@code /alliance <faction> remove <key>} and restored with
 * {@code /alliance <faction> add <key> [value]}.</p>
 */
public final class AllianceState {
    /** Wire IDs: keep the bundle order of {@code alliances.json}. */
    public static final List<String> ALLIANCES=List.of("stalkers","bandits","duty","freedom","merc","covenant");
    /** Bonus keys in wire order with their menu meaning. */
    public static final List<String> BONUSES=List.of("farm","delivery","quest","discount","buyer","ffa");
    private static final List<String> TITLES=List.of("добыча","доставка","задания","расходники","скупщик","FFA");
    public static final int DEFAULT_FEE=100_000;
    /** Percentage used when {@code add} is called without an explicit value. */
    public static final int DEFAULT_BONUS=5;
    private static final Map<String,String> BONUS_ALIASES=Map.ofEntries(
            Map.entry("farm","farm"),Map.entry("ресурсы","farm"),Map.entry("добыча","farm"),Map.entry("ферма","farm"),
            Map.entry("delivery","delivery"),Map.entry("доставка","delivery"),
            Map.entry("quest","quest"),Map.entry("задания","quest"),Map.entry("квесты","quest"),
            Map.entry("discount","discount"),Map.entry("скидка","discount"),Map.entry("расходники","discount"),
            Map.entry("buyer","buyer"),Map.entry("скупщик","buyer"),Map.entry("продажа","buyer"),
            Map.entry("ffa","ffa"),Map.entry("ффа","ffa"),Map.entry("кэшбэк","ffa"));

    /** One alliance row: readmission/transfer fee and the six menu percentages. */
    public static final class Row {
        int fee=DEFAULT_FEE;
        final float[] bonuses={10f,10f,10f,10f,10f,5f};
    }

    private final Path file;
    private final Map<String,Row> rows=new LinkedHashMap<>();

    public AllianceState(Path dataDirectory)throws IOException{
        file=Objects.requireNonNull(dataDirectory).resolve("alliance-settings.json");
        defaults();
        if(Files.isRegularFile(file))read();
        else write();
    }

    public Path file(){return file;}

    private void defaults(){rows.clear();for(String id:ALLIANCES)rows.put(id,new Row());}

    private void read()throws IOException{
        JsonObject root;
        try{root=JsonParser.parseString(Files.readString(file)).getAsJsonObject();}
        catch(RuntimeException error){throw new IOException("alliance_settings_json: "+error.getMessage(),error);}
        Map<String,Row> parsed=new LinkedHashMap<>();
        for(String id:ALLIANCES){
            Row row=new Row();
            if(root.has(id)){
                JsonObject entry=root.getAsJsonObject(id);
                row.fee=integer(entry,"fee",row.fee);
                JsonObject bonuses=entry.has("bonuses")&&entry.get("bonuses").isJsonObject()?entry.getAsJsonObject("bonuses"):entry;
                for(int i=0;i<BONUSES.size();i++)if(bonuses.has(BONUSES.get(i)))row.bonuses[i]=number(bonuses,BONUSES.get(i),row.bonuses[i]);
            }
            parsed.put(id,row);
        }
        rows.clear();rows.putAll(parsed);
    }

    private void write()throws IOException{
        JsonObject root=new JsonObject();
        for(var entry:rows.entrySet()){
            JsonObject row=new JsonObject();
            row.addProperty("fee",entry.getValue().fee);
            for(int i=0;i<BONUSES.size();i++)row.addProperty(BONUSES.get(i),entry.getValue().bonuses[i]);
            root.add(entry.getKey(),row);
        }
        Files.createDirectories(file.getParent());
        Path temporary=file.resolveSibling(file.getFileName()+".incoming");
        Files.writeString(temporary,new GsonBuilder().setPrettyPrinting().create().toJson(root)+System.lineSeparator());
        Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }

    public void reload()throws IOException{read();}

    public void close(){}

    /** Applies one key ({@code fee} or a bonus key) and persists the file. */
    public String set(String faction,String key,double value)throws IOException{
        return apply(faction,key,value,false);
    }

    /**
     * Adds (shows) a bonus row for one alliance, keeping the previous value when none is given.
     *
     * <p>The selection screen draws a dynamic perk only when its percentage is non-zero, so a
     * bonus is "added" by writing a non-zero value and "removed" by writing zero.</p>
     */
    public String add(String faction,String key)throws IOException{
        return add(faction,key,null);
    }

    /** Adds (shows) a bonus row for one alliance, keeping the previous value when none is given. */
    public String add(String faction,String key,Double value)throws IOException{
        String id=Factions.nativeName(faction);
        String bonus=bonusKey(key);
        if(bonus==null){
            if(value==null)throw new IllegalArgumentException("Для стоимости перехода нужно значение");
            return apply(faction,key,value,false);
        }
        Row row=rows.get(id);
        if(row==null)throw new IllegalArgumentException("Неизвестная фракция: "+faction);
        int index=BONUSES.indexOf(bonus);
        double target=value!=null?value:row.bonuses[index]!=0?row.bonuses[index]:DEFAULT_BONUS;
        return apply(faction,key,target,false);
    }

    /** Removes (hides) a bonus row: the client does not draw zero percentages. */
    public String remove(String faction,String key)throws IOException{
        return apply(faction,key,0,true);
    }

    private String apply(String faction,String key,double value,boolean removed)throws IOException{
        String id=Factions.nativeName(faction);Row row=rows.get(id);
        if(row==null)throw new IllegalArgumentException("Неизвестная фракция: "+faction);
        String bonus=bonusKey(key);
        boolean feeReference=bonus==null&&isFee(key);
        if(!feeReference&&bonus==null)throw new IllegalArgumentException("Ключ: fee, add, remove или "+String.join(", ",BONUSES));
        if(!Double.isFinite(value))throw new IllegalArgumentException("Некорректное значение");
        String reply;
        if(bonus==null){
            int fee=(int)Math.round(value);
            if(fee<0||fee>100_000_000)throw new IllegalArgumentException("Стоимость: 0..100000000");
            row.fee=fee;reply=removed?"Стоимость перехода "+id+": 0 ₽ (бесплатно)":"Стоимость перехода "+id+": "+fee+" ₽";
        }else{
            if(value<-1000||value>1000)throw new IllegalArgumentException("Бонус: -1000..1000 %");
            int index=BONUSES.indexOf(bonus);
            float previous=row.bonuses[index];
            row.bonuses[index]=(float)value;
            String title=id+" "+TITLES.get(index);
            reply=removed||value==0?"Бонус "+title+" убран (строка скрыта в меню)":
                    previous==0?"Бонус "+title+" добавлен: "+trim(value)+"%":
                    "Бонус "+title+": "+trim(value)+"%";
        }
        write();
        return reply;
    }

    private static boolean isFee(String key){
        String k=key.toLowerCase(Locale.ROOT);
        return k.equals("fee")||k.equals("стоимость")||k.equals("переход");
    }

    private static String bonusKey(String key){
        return BONUS_ALIASES.get(key.toLowerCase(Locale.ROOT));
    }

    /** Human-readable table for chat. */
    public String describe(){
        StringBuilder text=new StringBuilder("Фракции: стоимость перехода и бонусы (%):");
        for(var entry:rows.entrySet()){
            Row row=entry.getValue();text.append('\n').append(entry.getKey()).append(" — ").append(row.fee).append(" ₽, активно");
            int active=0;
            for(int i=0;i<BONUSES.size();i++)if(row.bonuses[i]!=0)active++;
            text.append(' ').append(active).append('/').append(BONUSES.size()).append(':');
            for(int i=0;i<BONUSES.size();i++)text.append(' ').append(BONUSES.get(i)).append(' ')
                    .append(row.bonuses[i]==0?"выкл":trim(row.bonuses[i])+"%");
        }
        return text.append("\nФайл: ").append(file).toString();
    }

    /** Bonus keys that are currently drawn in the menu for one alliance. */
    public List<String> active(String faction){
        Row row=rows.get(Factions.nativeName(faction));
        if(row==null)throw new IllegalArgumentException("Неизвестная фракция: "+faction);
        List<String> result=new ArrayList<>();
        for(int i=0;i<BONUSES.size();i++)if(row.bonuses[i]!=0)result.add(BONUSES.get(i));
        return List.copyOf(result);
    }

    /** Native alliance selection snapshot for one player. */
    public byte[] frame(){
        return Wire.frame(1667,Wire.bytes(out->{
            out.writeInt(ALLIANCES.size());
            for(String id:ALLIANCES){
                Row row=rows.get(id);
                out.writeUTF(id);
                out.writeUTF("");            // opaque label, unused by the selection screen
                state(out,row);
                out.writeInt(0);             // no per-key overrides, the default state is used
            }
            out.writeBoolean(false);         // ask the client to refresh open menus
        }));
    }

    private static void state(DataOutput out,Row row)throws IOException{
        out.writeBoolean(false);             // recruitment suspended
        out.writeBoolean(false);             // locked for non-members
        for(float bonus:row.bonuses)out.writeFloat(bonus);
        out.writeInt(row.fee);
        out.writeBoolean(true);              // no expiry instant
    }

    private static int integer(JsonObject o,String name,int fallback){
        try{return o.has(name)?o.get(name).getAsInt():fallback;}catch(RuntimeException error){throw new IllegalArgumentException("alliance_settings_"+name);}
    }
    private static float number(JsonObject o,String name,float fallback){
        try{return o.has(name)?o.get(name).getAsFloat():fallback;}catch(RuntimeException error){throw new IllegalArgumentException("alliance_settings_"+name);}
    }
    private static String trim(double value){return value==Math.rint(value)?Long.toString((long)value):Double.toString(value);}
}