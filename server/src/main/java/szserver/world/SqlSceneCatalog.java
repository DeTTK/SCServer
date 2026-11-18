package szserver.world;

import com.google.gson.*;
import szserver.util.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** SQL lookup decisions exported offline. Loader-owned only; never opens SQLite. */
final class SqlSceneCatalog {
    static final long MAX_BYTES=256L*1024*1024;
    record Candidate(String asset,UUID id,int priority){}
    final Map<UUID,Terrain.SceneHome> homes=new HashMap<>();
    final Map<Long,List<Candidate>> candidates=new HashMap<>();
    final LinkedHashMap<UUID,NativePortal> portals=new LinkedHashMap<>();
    long rows;
    static SqlSceneCatalog read(Path root,String sourceDigest)throws IOException{
        Path file=root.resolve("scene-sql-catalog.jsonl");if(!Files.exists(file))return null;
        if(Files.size(file)<1||Files.size(file)>MAX_BYTES)throw new IOException("scene_sql_catalog_file_limit");
        SqlSceneCatalog catalog=new SqlSceneCatalog();
        try(BufferedReader in=Files.newBufferedReader(file,StandardCharsets.UTF_8)){
            JsonObject header=JsonParser.parseString(line(in)).getAsJsonObject();
            if(!"sz-scene-sql-catalog".equals(Json.text(header,"format",64))||Json.integer(header,"version",1,1)!=1||!sourceDigest.equals(Json.text(header,"registrySha256",64)))throw new IOException("scene_sql_catalog_registry_or_format");
            Map<String,Set<Integer>> priorities=new HashMap<>();
            for(String raw;(raw=line(in))!=null;){
                if(++catalog.rows>3000000)throw new IOException("scene_sql_catalog_row_limit");JsonObject row=JsonParser.parseString(raw).getAsJsonObject();String kind=Json.text(row,"kind",32);UUID id=UUID.fromString(Json.text(row,"id",36));
                if(kind.equals("home")||kind.equals("template")){
                    int x=Json.integer(row,"x",-szserver.world.MapBounds.CHUNK,szserver.world.MapBounds.CHUNK),z=Json.integer(row,"z",-szserver.world.MapBounds.CHUNK,szserver.world.MapBounds.CHUNK);
                    if(kind.equals("home")){catalog.homes.putIfAbsent(id,new Terrain.SceneHome(x,z));if(catalog.homes.size()>SceneCatalog.MAX_IDENTITIES)throw new IOException("scene_sql_catalog_home_limit");}
                    else{String asset=Json.text(row,"asset",1024);int priority=Json.integer(row,"priority",0,31);if(!priorities.computeIfAbsent(asset,k->new HashSet<>()).add(priority))throw new IOException("scene_sql_catalog_template_duplicate");if(priorities.size()>32768)throw new IOException("scene_sql_catalog_asset_limit");catalog.candidates.computeIfAbsent(BlockOverlays.key(x,z),k->new ArrayList<>()).add(new Candidate(asset,id,priority));}
                }else if(kind.equals("portal")){
                    String asset=Json.text(row,"asset",1024),behavior=Json.text(row,"behaviour",32);if(!NativePortal.behaviour(behavior))throw new IOException("scene_sql_catalog_portal_behavior");JsonArray position=row.getAsJsonArray("position");if(position.size()!=3)throw new IOException("scene_sql_catalog_portal_position");double[] xyz=new double[3];for(int i=0;i<3;i++){xyz[i]=position.get(i).getAsDouble();if(!Double.isFinite(xyz[i])||Math.abs(xyz[i])>szserver.world.MapBounds.HORIZONTAL)throw new IOException("scene_sql_catalog_portal_position");}double scale=row.get("scale").getAsDouble();if(!Double.isFinite(scale)||scale<=0)throw new IOException("scene_sql_catalog_portal_scale");
                    NativePortal portal=new NativePortal(id,asset,behavior,new World.Vec(xyz[0],xyz[1],xyz[2]),scale,NativePortal.Parameters.read(Json.object(row,"settings")));
                    if(catalog.portals.putIfAbsent(id,portal)!=null||catalog.portals.size()>SceneCatalog.MAX_IDENTITIES)throw new IOException("scene_sql_catalog_portal_duplicate_or_limit");
                }else throw new IOException("scene_sql_catalog_row_kind");
            }
        }return catalog;
    }
    /** Bound allocations before readLine can allocate an attacker-sized line. */
    private static String line(Reader in)throws IOException{StringBuilder row=new StringBuilder();int c;while((c=in.read())!=-1&&c!='\n'){if(row.length()>=128*1024)throw new IOException("scene_sql_catalog_line_limit");row.append((char)c);}return c==-1&&row.isEmpty()?null:row.toString();}
}
