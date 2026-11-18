package szserver.commands;

import com.google.gson.JsonObject;
import szserver.util.Json;
import szserver.net.CommandSupport;
import szserver.net.CommandNumbers;
import szserver.world.World;
import java.util.*;
import java.util.concurrent.*;

/** One transport-owned editor session. Futures are polled; transport never waits for IO or WorldLoop. */
public final class WorldEditChat {
    private World.Block first,second;
    private String clipboard,lastEdit;
    private World.Block clipboardOffset,undoFirst,undoSecond;
    private CompletableFuture<JsonObject> pending;
    private String action;
    private World.Block pendingFirst,pendingSecond,pendingOffset;
    public static boolean matches(String text){return CommandSupport.verb(text).equals("/wedit");}
    private static int integer(String text,int min,int max){return CommandNumbers.bounded(text,min,max,"worldedit_coordinate_bounds");}
    private static World.Block position(World.Vec point){if(!Double.isFinite(point.x())||!Double.isFinite(point.y())||!Double.isFinite(point.z())||Math.floor(point.x())<-szserver.world.MapBounds.HORIZONTAL||Math.floor(point.x())>szserver.world.MapBounds.HORIZONTAL||point.y()<0||point.y()>=256||Math.floor(point.z())<-szserver.world.MapBounds.HORIZONTAL||Math.floor(point.z())>szserver.world.MapBounds.HORIZONTAL)throw new IllegalArgumentException("worldedit_position_bounds");return new World.Block((int)Math.floor(point.x()),(int)Math.floor(point.y()),(int)Math.floor(point.z()));}
    private void selection(JsonObject request){if(first==null||second==null)throw new IllegalArgumentException("worldedit_selection_incomplete");request.add("min",Json.tree(new World.Block(Math.min(first.x(),second.x()),Math.min(first.y(),second.y()),Math.min(first.z(),second.z()))));request.add("max",Json.tree(new World.Block(Math.max(first.x(),second.x()),Math.max(first.y(),second.y()),Math.max(first.z(),second.z()))));}
    private static World.Block translated(World.Block p,World.Block offset){long x=(long)p.x()+offset.x(),y=(long)p.y()+offset.y(),z=(long)p.z()+offset.z();if(x<-szserver.world.MapBounds.HORIZONTAL||x>szserver.world.MapBounds.HORIZONTAL||y<0||y>255||z<-szserver.world.MapBounds.HORIZONTAL||z>szserver.world.MapBounds.HORIZONTAL)throw new IllegalArgumentException("worldedit_destination_bounds");return new World.Block((int)x,(int)y,(int)z);}
    public String accept(String text,World.Vec player,boolean permitted,CommandService commands){
        if(!permitted)return "worldedit_requires_admin";
        if(pending!=null)return "worldedit_busy";
        try{
            if(text.length()>256)throw new IllegalArgumentException("worldedit_command_length");
            String[] words=CommandSupport.split(text).toArray(String[]::new);if(words.length==0||!words[0].equalsIgnoreCase("/wedit"))throw new IllegalArgumentException("not_worldedit_command");String verb=words.length==1?"help":words[1].toLowerCase(Locale.ROOT);
            if(verb.equals("help"))return "/wedit select 1|2 [x y z], copy, cut, paste, remove, fill id [metadata], move dx dy dz, back";
            if(verb.equals("select")){
                if(words.length!=3&&words.length!=6)throw new IllegalArgumentException("worldedit_select_arguments");int point=integer(words[2],1,2);World.Block p=words.length==3?position(player):new World.Block(integer(words[3],-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL),integer(words[4],0,255),integer(words[5],-szserver.world.MapBounds.HORIZONTAL,szserver.world.MapBounds.HORIZONTAL));
                if(point==1)first=p;else second=p;return "WorldEdit: select "+point+" "+p.x()+" "+p.y()+" "+p.z();
            }
            JsonObject request=new JsonObject();request.addProperty("commandId",UUID.randomUUID().toString());pendingFirst=first;pendingSecond=second;pendingOffset=null;
            switch(verb){
                case "copy","cut"->{if(words.length!=2)throw new IllegalArgumentException("worldedit_arguments");selection(request);World.Block anchor=position(player);JsonObject min=request.getAsJsonObject("min");pendingOffset=new World.Block(min.get("x").getAsInt()-anchor.x(),min.get("y").getAsInt()-anchor.y(),min.get("z").getAsInt()-anchor.z());request.addProperty("type",verb);}
                case "paste"->{if(words.length!=2)throw new IllegalArgumentException("worldedit_arguments");if(clipboard==null)throw new IllegalArgumentException("worldedit_clipboard_empty");request.addProperty("type","paste");request.addProperty("clipboard",clipboard);request.add("position",Json.tree(translated(position(player),clipboardOffset)));}
                case "remove","fill"->{if(verb.equals("remove")?words.length!=2:words.length<3||words.length>4)throw new IllegalArgumentException("worldedit_fill_arguments");selection(request);request.addProperty("type","fill");request.addProperty("blockId",verb.equals("remove")?0:integer(words[2],0,4095));request.addProperty("metadata",words.length==4?integer(words[3],0,15):0);if(verb.equals("remove"))request.addProperty("sceneSelection",true);}
                case "move"->{if(words.length!=5)throw new IllegalArgumentException("worldedit_move_arguments");selection(request);World.Block offset=new World.Block(integer(words[2],-szserver.world.MapBounds.OFFSET,szserver.world.MapBounds.OFFSET),integer(words[3],-255,255),integer(words[4],-szserver.world.MapBounds.OFFSET,szserver.world.MapBounds.OFFSET));if(offset.x()==0&&offset.y()==0&&offset.z()==0)throw new IllegalArgumentException("invalid_worldedit_offset");pendingFirst=translated(first,offset);pendingSecond=translated(second,offset);request.addProperty("type","move");request.add("offset",Json.tree(offset));}
                case "back"->{if(words.length!=2)throw new IllegalArgumentException("worldedit_arguments");if(lastEdit==null)throw new IllegalArgumentException("worldedit_history_empty");request.addProperty("type","undo");request.addProperty("target",lastEdit);}
                default->throw new IllegalArgumentException("worldedit_unknown_command");
            }
            action=verb;pending=commands.submit(request);return "WorldEdit: pending "+verb;
        }catch(IllegalArgumentException error){return error.getMessage();}
    }
    public String poll(){
        if(pending==null||!pending.isDone())return null;
        try{
            JsonObject result=pending.join();String id=result.get("commandId").getAsString();
            if(action.equals("copy")||action.equals("cut")){clipboard=id;clipboardOffset=pendingOffset;}
            if(action.equals("back")){first=undoFirst;second=undoSecond;lastEdit=null;}
            else if(!action.equals("copy")){undoFirst=first;undoSecond=second;lastEdit=id;first=pendingFirst;second=pendingSecond;}
            return "WorldEdit: committed "+action+"; cells="+result.get("cells").getAsInt()+(result.has("sceneObjects")?"; objects="+result.get("sceneObjects").getAsInt():"")+(result.has("sceneSkipped")?"; skippedStateful="+result.get("sceneSkipped").getAsInt():"");
        }catch(CompletionException error){Throwable cause=error.getCause();return "WorldEdit: failed "+action+"; "+Objects.toString(cause.getMessage(),cause.getClass().getSimpleName());}
        finally{pending=null;pendingOffset=null;}
    }
    public boolean busy(){return pending!=null;}
}
