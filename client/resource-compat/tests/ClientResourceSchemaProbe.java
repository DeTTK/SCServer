import com.google.gson.*;
import gamecompat.ClientResourceSchema;
import java.nio.file.*;
import java.util.*;

/** Old and current schema contracts, without mutating either JSON input. */
public final class ClientResourceSchemaProbe {
    static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        for(String name:List.of("1740810107","813156312","683052146","gamecompat.MultisetArtefact"))Class.forName(name,false,ClientResourceSchemaProbe.class.getClassLoader()).getDeclaredMethods();
        JsonObject current=JsonParser.parseString("{\"pvpWinnerShopExp\":1,\"pvpLoserShopExp\":1}").getAsJsonObject();String raw=current.toString();
        require(ClientResourceSchema.arenaInt(current,"pvpWinnerReward")==0,"Shop XP must not be interpreted as a currency reward");require(ClientResourceSchema.arenaInt(current,"pvpLoserReward")==0,"Removed loser coin reward");require(current.toString().equals(raw),"Current input mutated");
        JsonObject old=JsonParser.parseString("{\"pvpWinnerReward\":140,\"pvpLoserReward\":70}").getAsJsonObject();require(ClientResourceSchema.arenaInt(old,"pvpWinnerReward")==140,"Old reward contract changed");
        require(ClientResourceSchema.arenaInt(new JsonObject(),"pvpWinnerReward")==0,"Missing optional currency reward must stay zero");
        try{ClientResourceSchema.arenaInt(new JsonObject(),"requiredUnknownField");throw new AssertionError("Required schema error hidden");}catch(IllegalArgumentException expected){}
        JsonObject icons=JsonParser.parseString("{\"icon\":[\"a.svg\",\"b.svg\"]}").getAsJsonObject();require(ClientResourceSchema.modifierIcon(icons,"icon","").equals("a.svg"),"Primary icon");require(icons.getAsJsonArray("icon").size()==2,"Icons removed from the parsed definition");
        require(ClientResourceSchema.modifierIcon(JsonParser.parseString("{\"icon\":\"old.svg\"}").getAsJsonObject(),"icon","").equals("old.svg"),"Old icon changed");
        require(ClientResourceSchema.modifierIcon(JsonParser.parseString("{\"icon\":[12,\"a\",{}]}").getAsJsonObject(),"icon","").equals("a"),"Unknown optional icon entries must not hide the valid icon");
        for(String json:List.of("{\"icon\":{\"future\":true}}","{\"icon\":[12,{}]}","{\"icon\":null}","{}"))require(ClientResourceSchema.modifierIcon(JsonParser.parseString(json).getAsJsonObject(),"icon","fallback").equals("fallback"),"Optional unknown icon fallback");
        System.out.println("PASS complete transformed native classes verify; old/new PvP quantities; required field errors preserved; future optional icon shapes tolerated; JSON input unchanged");
    }
}
