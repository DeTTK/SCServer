package szserver.migration;

import com.google.gson.*;
import szserver.persistence.DeveloperBlocks;
import szserver.util.Json;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** Validate the logical Store.blocks table before any imported realm is published. */
public final class LegacyDeveloperBlockImport {
    private LegacyDeveloperBlockImport(){}
    public record Plan(LegacyEventPlan events,int rows,int batches,long bytes) implements AutoCloseable {
        public void install(DeveloperBlocks store)throws Exception{events.visit(event->{Map<DeveloperBlocks.Position,String> values=new LinkedHashMap<>();for(var element:event.getAsJsonArray("rows")){var row=element.getAsJsonObject();var p=row.getAsJsonArray("position");values.put(new DeveloperBlocks.Position(p.get(0).getAsInt(),p.get(1).getAsInt(),p.get(2).getAsInt()),row.get("block_id").getAsString());}store.setBatch(values).get(10,TimeUnit.SECONDS);});}
        @Override public void close()throws Exception{events.close();}
    }
    private static DeveloperBlocks.Position position(JsonObject row){int[] p=new int[3];String[] keys={"x","y","z"};for(int i=0;i<3;i++){JsonElement value=row.get(keys[i]);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()||!value.getAsString().matches("-?(0|[1-9][0-9]*)"))throw new IllegalArgumentException("legacy_developer_block_position");try{p[i]=value.getAsBigDecimal().intValueExact();}catch(ArithmeticException error){throw new IllegalArgumentException("legacy_developer_block_position",error);}}return new DeveloperBlocks.Position(p[0],p[1],p[2]);}
    private static String id(JsonObject row){JsonElement value=row.get("block_id");if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IllegalArgumentException("legacy_developer_block_id");return DeveloperBlocks.id(value.getAsString());}
    private static JsonObject event(JsonArray rows){JsonObject event=new JsonObject();event.addProperty("type","legacy_developer_blocks");event.add("rows",rows);return event;}
    public static Plan plan(LegacySnapshot snapshot,Path scratch)throws Exception{
        try(var out=new LegacyEventPlan.Builder(scratch,"developer-blocks-plan-");var sorted=new LegacySortedRows(out.directory,8)){
            snapshot.visit("blocks",row->{position(row);id(row);sorted.add(row);});
            int rows=0,batches=0;long bytes=0;JsonArray batch=new JsonArray();
            try(var cursor=sorted.cursor()){for(JsonObject row;(row=cursor.next())!=null;){JsonObject saved=new JsonObject();saved.add("position",position(row).json());saved.addProperty("block_id",id(row));bytes+=Json.bytes(saved).length;if(++rows>DeveloperBlocks.ROW_LIMIT||bytes>DeveloperBlocks.BYTE_LIMIT)throw new IllegalArgumentException("legacy_developer_block_capacity");batch.add(saved);if(batch.size()==DeveloperBlocks.BATCH_LIMIT){out.append(event(batch));batches++;batch=new JsonArray();}}}
            if(!batch.isEmpty()){out.append(event(batch));batches++;}sorted.close();return new Plan(out.seal(),rows,batches,bytes);
        }catch(java.io.IOException error){if(error.getMessage()!=null&&error.getMessage().startsWith("legacy_scene_duplicate"))throw new IllegalArgumentException("legacy_developer_block_duplicate",error);throw error;}
    }
}
