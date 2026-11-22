package szserver.net;

import com.google.gson.*;
import szserver.util.Workers;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Native alliance menu configuration. One bounded writer publishes immutable snapshots after durable saves. */
public final class AllianceState implements AutoCloseable {
    public static final List<String> ALLIANCES=List.of("stalkers","bandits","duty","freedom","merc","covenant");
    public static final List<String> BONUSES=List.of("farm","delivery","quest","discount","buyer","ffa");
    private static final List<String> TITLES=List.of("добыча","доставка","задания","расходники","скупщик","FFA");
    public static final int DEFAULT_FEE=100_000,DEFAULT_BONUS=5,MAX_FILE_BYTES=65536;
    private static final Map<String,String> ALIASES=Map.ofEntries(
        Map.entry("farm","farm"),Map.entry("ресурсы","farm"),Map.entry("добыча","farm"),Map.entry("ферма","farm"),
        Map.entry("delivery","delivery"),Map.entry("доставка","delivery"),Map.entry("quest","quest"),Map.entry("задания","quest"),Map.entry("квесты","quest"),
        Map.entry("discount","discount"),Map.entry("скидка","discount"),Map.entry("расходники","discount"),Map.entry("buyer","buyer"),Map.entry("скупщик","buyer"),Map.entry("продажа","buyer"),
        Map.entry("ffa","ffa"),Map.entry("ффа","ffa"),Map.entry("кэшбэк","ffa"));
    private record Row(int fee,List<Float> bonuses){}
    private record Snapshot(Map<String,Row> rows,long revision,byte[] frame){}
    @FunctionalInterface interface Save {void write(Path file,byte[] bytes)throws IOException;}
    private final Path file;private final Save save;private final ThreadPoolExecutor worker;private final Object admission=new Object();
    private volatile Snapshot snapshot;private boolean closing;
    public AllianceState(Path data)throws IOException{this(data,AllianceState::store);}
    AllianceState(Path data,Save save)throws IOException{
        offOwner();file=Objects.requireNonNull(data).resolve("alliance-settings.json");this.save=Objects.requireNonNull(save);
        Map<String,Row> rows;if(Files.exists(file))rows=read();else{rows=defaults();save.write(file,encode(rows));}
        snapshot=new Snapshot(rows,1,frame(rows));worker=Workers.pool("alliance-storage",1,32);
    }
    private static void offOwner(){String name=Thread.currentThread().getName();if(name.equals("native-game-owner")||name.equals("world-tick"))throw new IllegalStateException("alliance_blocking_on_owner");}
    public Path file(){return file;}
    public long revision(){return snapshot.revision();}
    public byte[] frame(){return snapshot.frame().clone();}
    private static Map<String,Row> defaults(){Map<String,Row> rows=new LinkedHashMap<>();for(String id:ALLIANCES)rows.put(id,new Row(DEFAULT_FEE,List.of(10f,10f,10f,10f,10f,5f)));return Map.copyOf(rows);}
    static String faction(String name){String key=name.strip().toLowerCase(Locale.ROOT);return ALLIANCES.contains(key)?key:Factions.nativeName(key);}
    private static int fee(double value){if(!Double.isFinite(value)||value<0||value>100_000_000||value!=Math.rint(value))throw new IllegalArgumentException("Стоимость: целое число 0..100000000");return (int)value;}
    private static float bonus(double value){if(!Double.isFinite(value)||value<-1000||value>1000)throw new IllegalArgumentException("Бонус: -1000..1000 %");return (float)value;}
    private static double numeric(JsonElement value){if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("alliance_settings_number");return value.getAsDouble();}
    private Map<String,Row> read()throws IOException{
        offOwner();byte[] raw;try(var in=Files.newInputStream(file)){raw=in.readNBytes(MAX_FILE_BYTES+1);}if(raw.length>MAX_FILE_BYTES)throw new IOException("alliance_settings_size_limit");
        try{
            JsonObject root=JsonParser.parseString(new String(raw,StandardCharsets.UTF_8)).getAsJsonObject();Map<String,Row> rows=new LinkedHashMap<>(defaults());
            for(String id:ALLIANCES)if(root.has(id)){
                JsonObject entry=root.getAsJsonObject(id);Row previous=rows.get(id);int fee=entry.has("fee")?jsonFee(entry.get("fee")):previous.fee();
                JsonObject values=entry.has("bonuses")?entry.getAsJsonObject("bonuses"):entry;List<Float> bonuses=new ArrayList<>(previous.bonuses());
                for(int i=0;i<BONUSES.size();i++)if(values.has(BONUSES.get(i)))bonuses.set(i,bonus(numeric(values.get(BONUSES.get(i)))));
                rows.put(id,new Row(fee,List.copyOf(bonuses)));
            }return Map.copyOf(rows);
        }catch(RuntimeException invalid){throw new IOException("Некорректный alliance-settings.json: "+invalid.getMessage(),invalid);}
    }
    private static int jsonFee(JsonElement value){numeric(value);try{int fee=value.getAsBigDecimal().intValueExact();return fee(fee);}catch(ArithmeticException error){throw new IllegalArgumentException("Стоимость: целое число 0..100000000",error);}}
    private static byte[] encode(Map<String,Row> rows){JsonObject root=new JsonObject();for(String id:ALLIANCES){Row value=rows.get(id);JsonObject row=new JsonObject();row.addProperty("fee",value.fee());for(int i=0;i<BONUSES.size();i++)row.addProperty(BONUSES.get(i),value.bonuses().get(i));root.add(id,row);}return (new GsonBuilder().setPrettyPrinting().create().toJson(root)+"\n").getBytes(StandardCharsets.UTF_8);}
    static void store(Path file,byte[] bytes)throws IOException{
        offOwner();Files.createDirectories(file.getParent());Path temporary=Files.createTempFile(file.getParent(),"alliance-",".incoming");
        try{try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temporary);}
    }
    @FunctionalInterface private interface Task {String run()throws Exception;}
    private CompletableFuture<String> submit(Task task){
        CompletableFuture<String> result=new CompletableFuture<>();synchronized(admission){
            if(closing)return CompletableFuture.failedFuture(new IllegalArgumentException("Настройки фракций закрываются"));
            try{worker.execute(()->{try{result.complete(task.run());}catch(Throwable error){result.completeExceptionally(error);}});}catch(RejectedExecutionException full){result.completeExceptionally(new IllegalArgumentException("Очередь настроек фракций заполнена, попробуйте позже"));}
        }return result;
    }
    public CompletableFuture<String> reload(){return submit(()->{Map<String,Row> rows=read();publish(rows);return "Настройки фракций перечитаны.\n"+describe();});}
    public CompletableFuture<String> set(String faction,String key,double value){return change(faction,key,value,false,false);}
    public CompletableFuture<String> add(String faction,String key){return add(faction,key,null);}
    public CompletableFuture<String> add(String faction,String key,Double value){return change(faction,key,value,true,false);}
    public CompletableFuture<String> remove(String faction,String key){return change(faction,key,0d,false,true);}
    private CompletableFuture<String> change(String name,String key,Double value,boolean add,boolean remove){return submit(()->{
        String id=faction(name),kind=key.toLowerCase(Locale.ROOT),canonical=ALIASES.get(kind);boolean price=Set.of("fee","стоимость","переход").contains(kind);
        if(!price&&canonical==null)throw new IllegalArgumentException("Ключ: fee или "+String.join(", ",BONUSES));
        Map<String,Row> rows=new LinkedHashMap<>(snapshot.rows());Row previous=rows.get(id);String reply;
        if(price){if(value==null)throw new IllegalArgumentException("Для стоимости перехода нужно значение");int fee=fee(value);rows.put(id,new Row(fee,previous.bonuses()));reply="Стоимость перехода "+id+": "+fee+" ₽";}
        else{int index=BONUSES.indexOf(canonical);float old=previous.bonuses().get(index);double chosen=add&&value==null?(old==0?DEFAULT_BONUS:old):Objects.requireNonNull(value);float number=bonus(chosen);List<Float> bonuses=new ArrayList<>(previous.bonuses());bonuses.set(index,number);rows.put(id,new Row(previous.fee(),List.copyOf(bonuses)));String title=id+" "+TITLES.get(index);reply=remove||number==0?"Бонус "+title+" убран (строка скрыта в меню)":old==0?"Бонус "+title+" добавлен: "+trim(number)+"%":"Бонус "+title+": "+trim(number)+"%";}
        Map<String,Row> next=Map.copyOf(rows);save.write(file,encode(next));publish(next);return reply;
    });}
    private void publish(Map<String,Row> rows){snapshot=new Snapshot(rows,Math.incrementExact(snapshot.revision()),frame(rows));}
    public List<String> active(String name){Row row=snapshot.rows().get(faction(name));List<String> result=new ArrayList<>();for(int i=0;i<BONUSES.size();i++)if(row.bonuses().get(i)!=0)result.add(BONUSES.get(i));return List.copyOf(result);}
    public String describe(){Map<String,Row> rows=snapshot.rows();StringBuilder text=new StringBuilder("Фракции: стоимость перехода и бонусы (%):");for(String id:ALLIANCES){Row row=rows.get(id);text.append('\n').append(id).append(" — ").append(row.fee()).append(" ₽:");for(int i=0;i<BONUSES.size();i++)text.append(' ').append(BONUSES.get(i)).append(' ').append(row.bonuses().get(i)==0?"выкл":trim(row.bonuses().get(i))+"%");}return text.append("\nФайл: ").append(file).toString();}
    private static byte[] frame(Map<String,Row> rows){return Wire.frame(1667,Wire.bytes(out->{out.writeInt(ALLIANCES.size());for(String id:ALLIANCES){Row row=rows.get(id);out.writeUTF(id);out.writeUTF("");out.writeBoolean(false);out.writeBoolean(false);for(float value:row.bonuses())out.writeFloat(value);out.writeInt(row.fee());out.writeBoolean(true);out.writeInt(0);}out.writeBoolean(false);}));}
    private static String trim(float value){return value==Math.rint(value)?Integer.toString((int)value):Float.toString(value);}
    @Override public void close()throws InterruptedException{offOwner();synchronized(admission){closing=true;}Workers.stop(worker);}
}
