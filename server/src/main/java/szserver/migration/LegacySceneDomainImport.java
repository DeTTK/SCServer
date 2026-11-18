package szserver.migration;

import com.google.gson.*;
import szserver.commands.SceneDomainImport;
import szserver.util.Json;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.nio.file.*;
import szserver.persistence.Journal;

/** Legacy scene-domain rows and cursor history. They are metadata, never an automatic replacement of SZR geometry. */
public final class LegacySceneDomainImport {
    public record Plan(Path directory,Journal.Prefix prefix,int objects,int tombstones,int changes,long head) implements AutoCloseable{
        public long records(){return prefix==null?0:prefix.records();}
        public void visit(Journal.Recovery sink)throws Exception{if(prefix==null)return;Path path=directory.resolve("plan.sjournal");if(Files.size(path)!=prefix.end())throw new IllegalArgumentException("legacy_scene_domain_plan_changed");try(Journal journal=Journal.streaming(path)){if(!journal.matches(prefix))throw new IllegalArgumentException("legacy_scene_domain_plan_changed");journal.replay(sink).get();}}
        @Override public void close()throws Exception{
            if(directory==null)return;
            Files.deleteIfExists(directory.resolve("plan.sjournal"));Files.deleteIfExists(directory.resolve("plan.sjournal.lock"));
            // SceneDomain's disposable disk caches leave their root identity marker
            // after all leases close. This is our private offline plan directory.
            Path guard=directory.resolve(".derived-cache.guard");
            if(Files.exists(guard,LinkOption.NOFOLLOW_LINKS)){
                if(!Files.isRegularFile(guard,LinkOption.NOFOLLOW_LINKS)||Files.size(guard)!=24)throw new java.io.IOException("legacy_scene_domain_cache_guard");
                var marker=java.nio.ByteBuffer.wrap(Files.readAllBytes(guard));
                if(marker.getInt()!=0x53444352||marker.getInt()!=1)throw new java.io.IOException("legacy_scene_domain_cache_guard");
                Files.delete(guard);
            }
            Files.deleteIfExists(directory);
        }
    }
    private static String world(JsonObject row,String source){String value=Json.text(row,"world_id",1024);if(!value.equals(source))throw new IllegalArgumentException("legacy_scene_domain_world_unmapped");return value;}
    private static JsonElement encoded(JsonObject row,String key){String text=Json.text(row,key,16384);return Json.parse(("{\"value\":"+text+"}").getBytes(StandardCharsets.UTF_8)).get("value");}
    private static long integer(JsonObject row,String key){try{var value=row.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new ArithmeticException();long n=new java.math.BigDecimal(value.getAsString()).longValueExact();if(n<1)throw new ArithmeticException();return n;}catch(ArithmeticException error){throw new IllegalArgumentException("legacy_scene_domain_integer",error);}}
    public static Plan read(LegacySnapshot snapshot,LegacySceneImport.Context context,String namespace)throws Exception{
        return read(snapshot,context,namespace,Path.of("var"));
    }
    public static Plan read(LegacySnapshot snapshot,LegacySceneImport.Context context,String namespace,Path scratch)throws Exception{
        long count=(long)snapshot.count("scene_objects")+snapshot.count("scene_object_tombstones")+snapshot.count("scene_object_changes");if(count==0)return new Plan(null,null,0,0,0,0);if(context==null||context.sourceWorld()==null||context.sourceWorld().isBlank())throw new IllegalArgumentException("legacy_scene_domain_source_world_required");String source=context.sourceWorld();Files.createDirectories(scratch);Path directory=Files.createTempDirectory(scratch,"scene-domain-plan-");long[] head={0};boolean accepted=false;
        try(LegacySortedRows objects=new LegacySortedRows(directory,1);LegacySortedRows tombstones=new LegacySortedRows(directory,2);LegacySortedRows changes=new LegacySortedRows(directory,3)){
            snapshot.visit("scene_objects",row->{world(row,source);JsonObject object=row.deepCopy();object.add("transform",encoded(row,"transform_json"));object.remove("transform_json");SceneDomainImport.validateRow(object,1,source);objects.add(object);});
            snapshot.visit("scene_object_tombstones",row->{world(row,source);SceneDomainImport.validateRow(row,2,source);tombstones.add(row);});
            snapshot.visit("scene_object_changes",row->{world(row,source);JsonObject change=encoded(row,"payload_json").getAsJsonObject();if(!change.keySet().equals(Set.of("world_id","object_id","operation","revision","asset_id","transform","parent_id")))throw new IllegalArgumentException("legacy_scene_domain_payload_fields");for(String key:List.of("world_id","object_id","operation","revision"))if(!row.get(key).equals(change.get(key)))throw new IllegalArgumentException("legacy_scene_domain_payload_mismatch");long sequence=integer(row,"sequence");change.addProperty("sequence",sequence);change.add("created_at",row.get("created_at"));SceneDomainImport.validateRow(change,3,source);changes.add(change);head[0]=Math.max(head[0],sequence);});
            Journal.Prefix prefix;try(Journal journal=Journal.streaming(directory.resolve("plan.sjournal"));SceneDomainImport.Stream stream=new SceneDomainImport.Stream(namespace,source,directory,event->journal.append(event).get())){
                objects.visit(row->stream.row(1,row));tombstones.visit(row->stream.row(2,row));changes.visit(row->stream.row(3,row));stream.finish();prefix=journal.prefix().get();
            }
            accepted=true;return new Plan(directory,prefix,snapshot.count("scene_objects"),snapshot.count("scene_object_tombstones"),snapshot.count("scene_object_changes"),head[0]);
        }finally{if(!accepted)new Plan(directory,null,0,0,0,0).close();}
    }
    private LegacySceneDomainImport(){}
}
