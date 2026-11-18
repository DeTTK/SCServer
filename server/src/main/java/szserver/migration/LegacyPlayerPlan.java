package szserver.migration;

import com.google.gson.*;
import szserver.persistence.CharacterLocations;
import szserver.util.Json;
import java.nio.file.*;
import java.util.*;

/** UUID-sorted compatibility plan. All rows validated before destination creation, bounded journal batches. */
public final class LegacyPlayerPlan {
    private LegacyPlayerPlan(){}
    public record Plan(LegacyEventPlan events,int rows,int batches,long bytes) implements AutoCloseable {
        public void install(CharacterLocations locations)throws Exception{events.visit(event->{Map<UUID,JsonObject> values=new LinkedHashMap<>();for(var element:event.getAsJsonArray("rows")){JsonObject row=element.getAsJsonObject();values.put(UUID.fromString(row.get("scope").getAsString()),row.getAsJsonObject("player"));}locations.importLegacyPlayers(values).get();});}
        @Override public void close()throws Exception{events.close();}
    }
    public static Plan read(LegacySnapshot snapshot,Set<UUID> characters,Path scratch)throws Exception{
        try(var builder=new LegacyEventPlan.Builder(scratch,"legacy-players-");var sorted=new LegacySortedRows(builder.directory,12)){
            int[] count={0},batches={0};long[] bytes={0};snapshot.visit("native_authority_players",row->{UUID id=LegacyPlayerImport.validate(row,characters);JsonObject value=new JsonObject();value.addProperty("object_id",id.toString());value.add("player",row);sorted.add(value);count[0]++;});
            JsonArray part=new JsonArray();JsonObject event=new JsonObject();event.addProperty("type","legacy-players");event.add("rows",part);
            sorted.visit(value->{JsonObject row=new JsonObject();row.addProperty("scope",value.get("object_id").getAsString());row.add("player",value.getAsJsonObject("player"));part.add(row);
                byte[] encoded=Json.bytes(event);if(part.size()>CharacterLocations.BATCH||encoded.length>65536){part.remove(part.size()-1);emit(builder,event,bytes,batches);while(!part.isEmpty())part.remove(part.size()-1);part.add(row);encoded=Json.bytes(event);}
                if(encoded.length>65536)throw new IllegalArgumentException("legacy_player_transaction_limit");
            });if(!part.isEmpty())emit(builder,event,bytes,batches);return new Plan(builder.seal(),count[0],batches[0],bytes[0]);
        }
    }
    private static void emit(LegacyEventPlan.Builder builder,JsonObject event,long[] bytes,int[] batches)throws Exception{byte[] body=Json.bytes(event);Json.parse(body);bytes[0]+=body.length;builder.append(event);batches[0]++;}
}
