package szserver.migration;

import com.google.gson.*;
import szserver.net.EnvironmentCommands.Weather;
import szserver.persistence.WorldEnvironment.State;
import szserver.util.Json;
import java.util.*;
import java.nio.file.Path;
import szserver.persistence.Journal;
import szserver.persistence.WorldEventStore;

/** NativeAuthority's current environment plus the archived historical world-event cursor. */
public final class LegacyEnvironmentImport {
    private LegacyEnvironmentImport(){}
    private static long number(JsonObject row,String key,long min){JsonElement value=row.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("legacy_environment_integer");try{long result=value.getAsBigDecimal().longValueExact();if(result<min)throw new IllegalArgumentException("legacy_environment_integer");return result;}catch(ArithmeticException failure){throw new IllegalArgumentException("legacy_environment_integer",failure);}}
    public record Plan(State state,LegacyEventPlan data,int rows,long allocationHead) implements AutoCloseable{public void visit(Journal.Recovery action)throws Exception{data.visit(action);}public void close()throws Exception{data.close();}}
    public static State read(LegacySnapshot snapshot)throws Exception{try(Plan plan=plan(snapshot,Path.of(System.getProperty("java.io.tmpdir")))){return plan.state();}}
    public static Plan plan(LegacySnapshot snapshot,Path scratch)throws Exception{
        if(snapshot.count("native_authority_world")==0){if(snapshot.count("native_authority_world_events")>0)throw new IllegalArgumentException("legacy_world_events_without_environment");return new Plan(null,LegacyEventPlan.empty(),0,0);}
        if(snapshot.count("native_authority_world")!=1)throw new IllegalArgumentException("legacy_world_singleton");JsonObject[] world={null};snapshot.visit("native_authority_world",row->world[0]=row);
        if(number(world[0],"singleton",1)!=1)throw new IllegalArgumentException("legacy_world_singleton");String weather=Json.text(world[0],"weather",16);if(!Set.of("clear","rain","thunder").contains(weather))throw new IllegalArgumentException("legacy_world_weather");double seconds=world[0].get("weather_until").getAsDouble(),wall=world[0].get("updated_wall").getAsDouble();if(!Double.isFinite(seconds)||seconds<0||seconds>=Long.MAX_VALUE/1000.||!Double.isFinite(wall)||wall<0)throw new IllegalArgumentException("legacy_world_deadline");long currentRevision=number(world[0],"revision",0),allocation=snapshot.sqliteSequence("native_authority_world_events");
        long head=0;int count=0;try(var builder=new LegacyEventPlan.Builder(scratch,"world-events-plan-");var sorted=new LegacySortedRows(builder.directory,3)){
            snapshot.visit("native_authority_world_events",row->{number(row,"sequence",1);number(row,"revision",0);number(row,"server_tick",0);if(Json.text(row,"event_type",64).isEmpty())throw new IllegalArgumentException("legacy_world_event_type");Json.parse(Json.text(row,"payload_json",16384).getBytes(java.nio.charset.StandardCharsets.UTF_8));sorted.add(row);});
            JsonArray batch=new JsonArray();int bytes=0;try(var cursor=sorted.cursor()){for(JsonObject row;(row=cursor.next())!=null;){long sequence=number(row,"sequence",1),revision=number(row,"revision",0);if(sequence<=head)throw new IllegalArgumentException("legacy_world_event_duplicate");if(revision>currentRevision)throw new IllegalArgumentException("legacy_world_event_revision");JsonObject value=Json.tree(Map.of("sequence",sequence,"revision",revision,"serverTick",number(row,"server_tick",0),"type",Json.text(row,"event_type",64)));value.add("payload",Json.parse(Json.text(row,"payload_json",16384).getBytes(java.nio.charset.StandardCharsets.UTF_8)));WorldEventStore.validateRow(value);int size=szserver.persistence.WorldEventCodec.bytes(value).length;if(!batch.isEmpty()&&(batch.size()>=128||bytes+size>47000)){emit(builder,batch);batch=new JsonArray();bytes=0;}batch.add(value);bytes+=size;head=sequence;count++;}}if(!batch.isEmpty())emit(builder,batch);if(allocation<head)throw new IllegalArgumentException("legacy_world_event_sequence_head");State state=new State(number(world[0],"world_time",Long.MIN_VALUE),Weather.read(weather),(long)Math.ceil(seconds*1000),currentRevision,head);return new Plan(state,builder.seal(),count,allocation);
        }
    }
    private static void emit(LegacyEventPlan.Builder builder,JsonArray rows)throws Exception{JsonObject event=new JsonObject();event.addProperty("type","world_event_seed");event.add("rowsData",szserver.persistence.WorldEventCodec.encode(rows));builder.append(event);}
}
