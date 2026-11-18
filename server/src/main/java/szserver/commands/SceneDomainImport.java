package szserver.commands;

import com.google.gson.JsonObject;
import szserver.util.Json;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.nio.file.Path;

/** Offline scene-domain baseline builder; ordinary game commands cannot submit this event type. */
public final class SceneDomainImport {
    @FunctionalInterface public interface Sink{void event(JsonObject event)throws Exception;}
    /** Sorted offline rows become bounded validated events without retaining the complete plan. */
    public static final class Stream implements AutoCloseable {
        private final String namespace,source;private final Sink sink;private final SceneDomain validation;
        private final List<JsonObject> batch=new ArrayList<>();private int kind,index,bytes;private boolean finished;
        public Stream(String namespace,String source,Path scratch,Sink sink){this.namespace=namespace;this.source=source;this.sink=sink;validation=new SceneDomain(scratch);}
        public void row(int nextKind,JsonObject row)throws Exception{if(finished||nextKind<1||nextKind>3||nextKind<kind)throw new IllegalArgumentException("scene_import_stream_order");SceneDomain.validate(row,nextKind,source);int size=SceneDomainCodec.rowBytes(row);if(size>48000)throw new IllegalArgumentException("scene_domain_row_limit");if(kind!=nextKind||batch.size()==32||bytes+size>48000)flush();kind=nextKind;batch.add(row);bytes+=size;}
        private void flush()throws Exception{if(batch.isEmpty())return;var rows=List.copyOf(batch);JsonObject event=Json.tree(Map.of("type","scene_store_import","sceneDomainNamespace",namespace,"sceneDomainWorld",source,"importBatch",index++));SceneDomainCodec.save(event,new SceneDomainCodec.Patch(kind==1?rows:List.of(),kind==2?rows:List.of(),kind==3?rows:List.of()));validation.apply(event,true);String digest=Json.fingerprint(event);event.addProperty("commandId",UUID.nameUUIDFromBytes(digest.getBytes(StandardCharsets.UTF_8)).toString());event.addProperty("request",digest);sink.event(event);batch.clear();bytes=0;}
        public int finish()throws Exception{if(!finished){flush();finished=true;}return index;}
        @Override public void close(){validation.close();}
    }
    public static List<JsonObject> events(String namespace,String source,List<JsonObject> objects,List<JsonObject> tombstones,List<JsonObject> changes){
        try(SceneDomain validation=new SceneDomain()){List<JsonObject> result=new ArrayList<>();int index=0;
        for(int kind=0;kind<3;kind++){List<JsonObject> rows=List.of(objects,tombstones,changes).get(kind);for(int at=0;at<rows.size();at+=32){var batch=List.copyOf(rows.subList(at,Math.min(at+32,rows.size())));JsonObject event=Json.tree(Map.of("type","scene_store_import","sceneDomainNamespace",namespace,"sceneDomainWorld",source,"importBatch",index++));SceneDomainCodec.save(event,new SceneDomainCodec.Patch(kind==0?batch:List.of(),kind==1?batch:List.of(),kind==2?batch:List.of()));validation.apply(event,true);String digest=Json.fingerprint(event);event.addProperty("commandId",UUID.nameUUIDFromBytes(digest.getBytes(StandardCharsets.UTF_8)).toString());event.addProperty("request",digest);result.add(event);}}
        return List.copyOf(result);}
    }
    public static void validateRow(JsonObject row,int kind,String world){SceneDomain.validate(row,kind,world);}
    private SceneDomainImport(){}
}
