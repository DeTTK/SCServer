package szserver.world;

import com.google.gson.*;
import java.util.*;
import java.nio.*;

/** Immutable precompiled authored portal settings, independent of native object registry IDs. */
public record NativePortal(UUID id,String asset,String behaviour,World.Vec position,double scale,Parameters parameters) {
    public static boolean behaviour(String value){return Set.of("teleport_bubble","teleport_generic","teleport_invisible").contains(value);}
    public record Destination(World.Vec coordinates,double weight,String location){}
    public record Parameters(boolean interaction,double size,boolean horizontalOffset,boolean verticalOffset,double cooldown,List<Destination> destinations){
        public Parameters{destinations=List.copyOf(destinations);}
        public static Parameters read(JsonObject settings){
            if(settings==null)return null;List<Destination> rows=new ArrayList<>();JsonElement locations=settings.get("locations");if(locations!=null&&locations.isJsonArray()){
                for(JsonElement element:locations.getAsJsonArray()){if(!element.isJsonObject())continue;JsonObject row=element.getAsJsonObject();if(!row.has("coords")||!row.get("coords").isJsonObject())continue;JsonObject coords=row.getAsJsonObject("coords");World.Vec target;
                    try{target=new World.Vec(coords.get("x").getAsDouble(),coords.get("y").getAsDouble(),coords.get("z").getAsDouble());}catch(RuntimeException invalid){target=null;}
                    double weight=Math.max(0,row.has("weight")?number(row,"weight",0,false):1);rows.add(new Destination(target,weight,row.has("loc")&&row.get("loc").isJsonPrimitive()?row.get("loc").getAsString():""));
                }
            }
            return new Parameters(flag(settings,"teleportOnInteraction"),Math.abs(number(settings,"size",1,true)),flag(settings,"saveHorizontalOffsetFromCenter"),flag(settings,"saveVerticalOffsetFromCenter"),Math.max(1.5,Math.min(30,number(settings,"teleportRecoveryDuration",0,true))),rows);
        }
        private static boolean flag(JsonObject object,String name){JsonElement value=object.get(name);if(value==null||value.isJsonNull())return false;try{return value.getAsJsonPrimitive().isBoolean()?value.getAsBoolean():new java.math.BigDecimal(value.getAsString()).intValue()!=0;}catch(RuntimeException invalid){throw new IllegalArgumentException("portal_flag:"+name,invalid);}}
        private static double number(JsonObject object,String name,double fallback,boolean zeroFallback){try{JsonElement value=object.get(name);if(value==null||value.isJsonNull())return fallback;double number=value.getAsDouble();if(!Double.isFinite(number))return fallback;return zeroFallback&&number==0?fallback:number;}catch(RuntimeException invalid){return fallback;}}
    }
    public static NativePortal from(SceneObject object,String behaviour){if(!behaviour(behaviour)||object.portalParameters()==null)return null;return new NativePortal(object.identity(),object.asset(),behaviour,new World.Vec(object.x(),object.y(),object.z()),object.scale(),object.portalParameters());}
    /** Same last-authored portal choice as the native catalog. Nonportal copies
     * do not erase another physical factory's already parsed portal settings. */
    public static NativePortal from(ScenePayload.Entry entry,java.util.function.Function<String,String> behaviors){
        if(entry==null)return null;NativePortal result=null;for(var occurrence:entry.occurrences()){var object=occurrence.object();NativePortal portal=from(object,behaviors.apply(object.asset()));if(portal!=null)result=portal;}return result;
    }
    public double radius(){double base=behaviour.equals("teleport_generic")?1.5:2;return Math.max(.75,Math.min(16,base*Math.max(.1,scale)*parameters.size()));}
    public boolean contains(World.Vec p,boolean interaction){if(parameters.interaction()!=interaction)return false;double x=p.x()-position.x(),y=p.y()+.9-position.y(),z=p.z()-position.z(),radius=interaction?6:radius();return x*x+y*y+z*z<=radius*radius;}
    public double distanceSquared(World.Vec p){double x=p.x()-position.x(),y=p.y()-position.y(),z=p.z()-position.z();return x*x+y*y+z*z;}
    public Destination choose(double selector){List<Destination> rows=parameters.destinations();if(rows.isEmpty())return null;double total=rows.stream().mapToDouble(Destination::weight).sum();if(!Double.isFinite(total))throw new IllegalArgumentException("portal_weight_overflow");if(total<=0)return rows.get(0);double point=Math.min(total,Math.max(0,selector*total));for(Destination destination:rows){point-=destination.weight();if(point<=0)return destination;}return rows.get(rows.size()-1);}
    public World.Vec target(World.Vec source,Destination destination){World.Vec p=destination.coordinates();if(p==null)throw new IllegalArgumentException("invalid_teleport_destination");return szserver.net.TeleportCommands.warpPosition(new World.Vec(p.x()+(parameters.horizontalOffset()?source.x()-position.x():0),p.y()+(parameters.verticalOffset()?source.y()-position.y():0),p.z()+(parameters.horizontalOffset()?source.z()-position.z():0)));}
    public record Activation(NativePortal portal,World.Vec target,String destination){}
}
