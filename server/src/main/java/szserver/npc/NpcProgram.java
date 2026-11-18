package szserver.npc;

import com.google.gson.*;
import szserver.world.*;
import java.util.*;

/** Validated authoring data. The director only steers the existing native actor controller. */
public record NpcProgram(String name,boolean loop,List<Step> steps) {
    public record Step(String action,World.Vec point,ActorLocomotion.Gait gait,int ticks,float yaw,String state,boolean enabled){}
    public NpcProgram{if(name==null||!name.matches("[a-zA-Z0-9_-]{1,40}")||steps==null||steps.isEmpty()||steps.size()>64)throw new IllegalArgumentException("program_name_or_steps");steps=List.copyOf(steps);}
    public static NpcProgram parse(JsonObject json){
        String name=json.get("name").getAsString();boolean loop=json.has("loop")&&json.get("loop").getAsBoolean();List<Step> steps=new ArrayList<>();
        for(JsonElement row:json.getAsJsonArray("steps")){
            JsonObject s=row.getAsJsonObject();String op=s.get("action").getAsString();World.Vec p=null;ActorLocomotion.Gait gait=ActorLocomotion.Gait.JOG;int ticks=0;float yaw=0;String state="";boolean enabled=false;
            switch(op){
                case "move"->{p=new World.Vec(number(s,"x"),number(s,"y"),number(s,"z"));if(Math.abs(p.x())>30_000_000||Math.abs(p.z())>30_000_000||p.y()<0||p.y()>255)throw new IllegalArgumentException("program_point_bounds");gait=ActorLocomotion.Gait.valueOf(s.get("gait").getAsString());if(!Set.of(ActorLocomotion.Gait.SLOW,ActorLocomotion.Gait.WALK,ActorLocomotion.Gait.JOG,ActorLocomotion.Gait.SPRINT).contains(gait))throw new IllegalArgumentException("program_gait");ticks=duration(s,120);}
                case "wait","follow","look"->{ticks=duration(s,2);if(op.equals("look")){yaw=(float)number(s,"yaw");if(Math.abs(yaw)>360)throw new IllegalArgumentException("program_yaw");}}
                case "state"->{state=s.get("state").getAsString();if(!Set.of("KNOCKDOWN","FLY","LOGOUT").contains(state))throw new IllegalArgumentException("program_state");enabled=s.get("enabled").getAsBoolean();}
                case "stop"->{}
                default->throw new IllegalArgumentException("unsupported_npc_action:"+op);
            }
            steps.add(new Step(op,p,gait,ticks,yaw,state,enabled));
        }
        return new NpcProgram(name,loop,steps);
    }
    private static double number(JsonObject o,String key){double v=o.get(key).getAsDouble();if(!Double.isFinite(v))throw new IllegalArgumentException("program_nonfinite");return v;}
    private static int duration(JsonObject o,double fallback){double s=o.has("seconds")?number(o,"seconds"):fallback;if(s<.05||s>300)throw new IllegalArgumentException("program_duration");return (int)Math.ceil(s*20);}
    public JsonObject json(){JsonObject o=new JsonObject();o.addProperty("name",name);o.addProperty("loop",loop);JsonArray list=new JsonArray();for(Step s:steps){JsonObject a=new JsonObject();a.addProperty("action",s.action);if(s.point!=null){a.addProperty("x",s.point.x());a.addProperty("y",s.point.y());a.addProperty("z",s.point.z());a.addProperty("gait",s.gait.name());}if(s.ticks>0)a.addProperty("seconds",s.ticks/20.);if(s.action.equals("look"))a.addProperty("yaw",s.yaw);if(s.action.equals("state")){a.addProperty("state",s.state);a.addProperty("enabled",s.enabled);}list.add(a);}o.add("steps",list);return o;}
}
