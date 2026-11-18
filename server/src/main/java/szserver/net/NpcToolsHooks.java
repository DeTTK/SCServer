package szserver.net;

import com.google.gson.*;
import szserver.client.NpcVisuals;
import szserver.commands.CommandService;
import szserver.world.*;
import java.lang.reflect.*;import java.util.*;import java.util.concurrent.*;

/** Developer chat adapter. Durable work uses CommandService; no waiting or IO on the network owner. */
public final class NpcToolsHooks {
    private static final Map<Object,Map<Object,Editor>> editors=Collections.synchronizedMap(new WeakHashMap<>());
    private static final class Editor {String selected;boolean pending;final List<World.Vec> route=new ArrayList<>();}
    private static Object field(Object o,String name){try{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(ReflectiveOperationException e){throw new IllegalStateException("npc_adapter_field:"+name,e);}}
    private static Object call(Object o,String name,Object...args){search:for(Method m:o.getClass().getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length){for(int i=0;i<args.length;i++)if(args[i]!=null&&!m.getParameterTypes()[i].isInstance(args[i]))continue search;try{m.setAccessible(true);return m.invoke(o,args);}catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new IllegalStateException("npc_adapter_call:"+name,e.getCause());}catch(ReflectiveOperationException e){throw new IllegalStateException("npc_adapter_call:"+name,e);}}throw new IllegalStateException("npc_adapter_method:"+name);}
    private static Editor editor(Object server,Object session){synchronized(editors){return editors.computeIfAbsent(server,k->new WeakHashMap<>()).computeIfAbsent(session,k->new Editor());}}
    private static boolean connected(Object server,Object session){return ((Map<?,?>)field(server,"sessions")).get(field(session,"address"))==session&&Boolean.TRUE.equals(field(session,"ready"));}
    private static void say(Object server,Object session,String message){if(connected(server,session))call(server,"enqueueOrRetire",session,GamePackets.chat(message));}
    private static World.Vec position(Object session){double[] p=(double[])field(session,"pos");return new World.Vec(p[0],p[1],p[2]);}
    private static double distance(World.View e,World.Vec p){return Math.hypot(Math.hypot(e.x()-p.x(),e.z()-p.z()),e.y()-p.y());}
    private static String shortId(String id){return id.substring(id.length()-8);}
    private static World.View selected(Editor editor,WorldLoop world){if(editor.selected==null)throw new IllegalArgumentException("Сначала /npc spawn <пресет> или /npc select nearest");return world.snapshot().entities().stream().filter(e->e.id().equals(editor.selected)).findFirst().orElseThrow(()->new IllegalArgumentException("Выбранный NPC уже удалён"));}
    private static JsonObject command(String operation,String id){var r=new JsonObject();r.addProperty("type",operation);r.addProperty("commandId",UUID.randomUUID().toString());r.addProperty("entityId",id);return r;}
    private static JsonObject vector(World.Vec p){var j=new JsonObject();j.addProperty("x",p.x());j.addProperty("y",p.y());j.addProperty("z",p.z());return j;}
    private static double number(String s){try{double v=Double.parseDouble(s);if(!Double.isFinite(v))throw new NumberFormatException();return v;}catch(NumberFormatException e){throw new IllegalArgumentException("Некорректное число: "+s);}}
    private static World.Vec point(List<String> words,int start,World.Vec fallback){if(words.size()==start)return fallback;if(words.size()!=start+3)throw new IllegalArgumentException("Укажи x y z либо оставь координаты пустыми");return new World.Vec(number(words.get(start)),number(words.get(start+1)),number(words.get(start+2)));}
    private static void count(List<String> words,int n){if(words.size()!=n)throw new IllegalArgumentException("Неверные аргументы. /npc help");}
    private static void await(Object server,Object session,Editor editor,CompletableFuture<?> future,Runnable success,String message){
        editor.pending=true;future.whenComplete((result,error)->{
            try{call(server,"adminTask",(Runnable)()->{editor.pending=false;if(!connected(server,session))return;if(error==null){success.run();say(server,session,message);}else{Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();say(server,session,"NPC: "+Objects.toString(cause.getMessage(),cause.getClass().getSimpleName()));}});}catch(RejectedExecutionException ignored){/* disconnected/stopped owner cannot receive an acknowledgement */}
        });
    }
    public static boolean chat(Object server,Object session,String text){
        if(!CommandSupport.verb(text).equals("/npc"))return false;
        try{
            if(!connected(server,session))return true;
            UUID character=(UUID)call(server,"character",session);if(!Boolean.TRUE.equals(call(field(server,"playerRules"),"entitled",character)))throw new IllegalArgumentException("Управление NPC требует прав администратора");
            List<String> words=CommandSupport.words(text);String op=words.size()<2?"help":words.get(1).toLowerCase(Locale.ROOT);Editor e=editor(server,session);
            WorldLoop world=(WorldLoop)field(server,"world");CommandService commands=(CommandService)field(server,"commands");World.Vec here=position(session);
            if(op.equals("help")){say(server,session,"/npc presets | spawn <пресет> [x y z] | list | select <nearest/UUID/конец UUID> | info\n/npc goto [x y z] | follow [дистанция] | stop | patrol clear/add [x y z]/start\n/npc equip <броня или 0> <оружие или 0> | state <FLY/KNOCKDOWN/LOGOUT> <on/off> | remove");return true;}
            if(op.equals("presets")){count(words,2);say(server,session,"NPC пресеты: "+String.join(", ",NpcVisuals.profiles().stream().map(NpcVisuals.Profile::key).toList()));return true;}
            if(op.equals("list")){count(words,2);var nearby=world.snapshot().entities().stream().filter(v->!ZombieHorde.owns(v.id())&&distance(v,here)<=128).sorted(Comparator.comparingDouble(v->distance(v,here))).limit(12).toList();say(server,session,nearby.isEmpty()?"Поблизости NPC нет":"NPC рядом:\n"+String.join("\n",nearby.stream().map(v->shortId(v.id())+"  "+Math.round(distance(v,here))+" м  "+v.navigation()+(v.id().equals(e.selected)?" [выбран]":"")).toList()));return true;}
            if(e.pending)throw new IllegalArgumentException("Предыдущая команда NPC ещё выполняется");
            if(op.equals("select")){count(words,3);String id=words.get(2);var candidates=world.snapshot().entities().stream().filter(v->!ZombieHorde.owns(v.id())).filter(v->id.equals("nearest")?distance(v,here)<=32:v.id().equals(id)||id.matches("[0-9a-fA-F]{8,12}")&&v.id().endsWith(id.toLowerCase(Locale.ROOT))).sorted(Comparator.comparingDouble(v->distance(v,here))).toList();if(candidates.isEmpty()||!id.equals("nearest")&&candidates.size()!=1)throw new IllegalArgumentException("NPC не найден либо ID неоднозначен");e.selected=candidates.get(0).id();e.route.clear();say(server,session,"Выбран NPC "+e.selected);return true;}
            if(op.equals("spawn")){if(words.size()<3)throw new IllegalArgumentException("/npc spawn <пресет> [x y z]");String id=NpcVisuals.identity(words.get(2)).toString();double yaw=Math.toRadians(((Number)field(session,"yaw")).doubleValue());var at=point(words,3,new World.Vec(here.x()-Math.sin(yaw)*3,here.y(),here.z()+Math.cos(yaw)*3));var r=command("spawn",id);r.addProperty("kind","BOT");r.add("position",vector(at));await(server,session,e,commands.submit(r),()->{e.selected=id;e.route.clear();},"NPC создан и выбран: "+id);return true;}
            var actor=selected(e,world);String id=actor.id();
            if(op.equals("info")){count(words,2);var visual=NpcVisuals.profile(id);say(server,session,"NPC "+id+"\n"+(visual==null?"прежний player-бот":visual.key()+" / "+visual.animations())+"; HP="+actor.health()+"; "+actor.navigation()+"; маршрут="+actor.pathLength()+"; цель="+actor.goal());return true;}
            if(Set.of("goto","follow","stop","remove").contains(op)||op.equals("patrol")&&words.size()>2&&words.get(2).equals("start"))world.submit(w->{szserver.npc.NpcDirector.of(w).cancel(id);return null;});
            if(op.equals("patrol")){if(words.size()<3)throw new IllegalArgumentException("/npc patrol clear | add [x y z] | start");String action=words.get(2);if(action.equals("clear")){count(words,3);e.route.clear();say(server,session,"Черновик патруля очищен; действующий маршрут не изменён");return true;}if(action.equals("add")){if(e.route.size()>=16)throw new IllegalArgumentException("Не больше 16 точек");World.Vec p=point(words,3,here);e.route.add(p);say(server,session,"Точка патруля "+e.route.size()+": "+p);return true;}if(!action.equals("start"))throw new IllegalArgumentException("/npc patrol clear | add [x y z] | start");count(words,3);World.validatePatrol(e.route);var r=command("patrol",id);var points=new JsonArray();e.route.forEach(p->points.add(vector(p)));r.add("points",points);await(server,session,e,commands.submit(r),()->{},"Патруль сохранён и запущен: "+e.route.size()+" точек");return true;}
            if(op.equals("equip")){count(words,4);int armor=Integer.parseInt(words.get(2)),weapon=Integer.parseInt(words.get(3));if(armor<0||armor>65535||weapon<0||weapon>65535)throw new IllegalArgumentException("ID предмета: 0..65535");var r=new JsonObject();r.addProperty("entityId",id);var worn=new JsonArray();if(armor!=0)worn.add(armor);r.add("wornItemIds",worn);r.addProperty("heldItemId",weapon);await(server,session,e,commands.actorEquipment(r),()->{},"Экипировка NPC сохранена");return true;}
            if(op.equals("state")){count(words,4);var kind=ActorControllerEngine.ConditionKind.valueOf(words.get(2).toUpperCase(Locale.ROOT));if(!Set.of("on","off").contains(words.get(3)))throw new IllegalArgumentException("Укажи on или off");var r=new JsonObject();r.addProperty("entityId",id);r.addProperty("kind",kind.name());r.addProperty("enabled",words.get(3).equals("on"));await(server,session,e,commands.actorCondition(r),()->{},"Состояние NPC сохранено");return true;}
            JsonObject r;
            switch(op){
                case "goto"->{r=command("goal",id);r.add("position",vector(point(words,2,here)));}
                case "stop"->{count(words,2);r=command("goal",id);r.add("position",vector(new World.Vec(actor.x(),actor.y(),actor.z())));}
                case "follow"->{if(words.size()>3)throw new IllegalArgumentException("/npc follow [дистанция 1..6]");double d=words.size()==3?number(words.get(2)):2.5;if(d<1||d>6)throw new IllegalArgumentException("Дистанция: 1..6 м");r=command("follow",id);r.addProperty("target",character.toString());r.addProperty("distance",d);}
                case "remove"->{count(words,2);r=command("remove",id);}
                default->throw new IllegalArgumentException("Неизвестная команда NPC. /npc help");
            }
            await(server,session,e,commands.submit(r),()->{if(op.equals("remove")){e.selected=null;e.route.clear();}},"NPC: "+op+" выполнено");
        }catch(IllegalArgumentException error){say(server,session,Objects.toString(error.getMessage(),"Неверные аргументы NPC"));}
        return true;
    }
    private NpcToolsHooks(){}
}
