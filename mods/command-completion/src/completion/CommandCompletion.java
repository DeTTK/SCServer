package completion;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import com.google.gson.*;
import szmods.api.*;
import static completion.Native.*;

public final class CommandCompletion implements ClientMod {
 record Catalog(Object connection,byte[] bytes){}
 final Queue<Catalog> incoming=new ConcurrentLinkedQueue<>();
 final Map<String,String> commands=new TreeMap<>();
 final List<Completion.Suggestion> clones=new ArrayList<>();
 final List<String> players=new ArrayList<>();
 final TreeSet<String> animations=new TreeSet<>();
 final Map<String,Object> animationPacks=new TreeMap<>();
 final Set<String> indexed=new HashSet<>();
 Object game,connection,screen;long lastRequest;int selected;boolean hidden,cycled;
 String lastInput="",cycleInput="";int lastCursor=-1; boolean medicinePressed;
 String lastCatalog="";
 Completion.Result result,cycleResult;
 public String lastError="";public int renderedRows;public long catalogUpdates;
 public void onFrame(ClientFrame frame)throws Exception {
  game=frame.game();Object next=call(game,"transient","1219970978");
  if(next!=connection){connection=next;commands.clear();clones.clear();players.clear();lastCatalog="";lastRequest=0;screen=null;}
  Catalog packet;while((packet=incoming.poll())!=null)if(packet.connection==connection){
   String json=new String(packet.bytes,java.nio.charset.StandardCharsets.UTF_8);if(json.equals(lastCatalog))continue;lastCatalog=json;
   JsonObject data=JsonParser.parseString(json).getAsJsonObject();
   if(data.get("version").getAsInt()!=1)continue;
   commands.clear();data.getAsJsonObject("commands").entrySet().forEach(e->commands.put(e.getKey(),e.getValue().getAsString()));
   clones.clear();Map<String,Integer> counts=new HashMap<>();
   for(JsonElement e:data.getAsJsonArray("clones"))counts.merge(e.getAsJsonObject().get("name").getAsString(),1,Integer::sum);
   for(JsonElement e:data.getAsJsonArray("clones")){JsonObject c=e.getAsJsonObject();String name=c.get("name").getAsString(),code=c.get("code").getAsString();
    if(counts.get(name)==1)clones.add(new Completion.Suggestion(name,"клон #"+code));
    clones.add(new Completion.Suggestion(code,name));}
   players.clear();for(JsonElement e:data.getAsJsonArray("players"))players.add(e.getAsString());
   lastCursor=-1;catalogUpdates++;
  }
  Object current=call(game,"float","622415611");
  if(current==null||!current.getClass().getName().equals("922139741")){screen=null;return;}
  if(screen!=current){screen=current;hidden=cycled=false;lastCursor=-1;selected=0;lastRequest=0;}
  if(connection!=null&&System.nanoTime()-lastRequest>1_000_000_000L){
   Object packetOut=Class.forName("szmods.bridge.CommandRequest").getConstructor(byte[].class).newInstance((Object)new byte[]{1});
   Object envelope=Class.forName("385693344").getConstructor(Class.forName("499058674")).newInstance(packetOut);
   call(connection,"else","void",envelope);lastRequest=System.nanoTime();
  }
  Object input=field(screen,"interface");String text=(String)call(input,"enum","java.lang.String");
  if(text.startsWith("/anim"))indexAnimations();
  update(input);
 }
 void indexAnimations()throws Exception{
  Object player=field(game,"e");if(player==null)return;
  if(!indexed.contains("@character")){
   Object component=call(Class.forName("1478690128"),"new","1579323378",player);
   Object animator=call(component,"static","1805188833");if(!(Boolean)call(animator,"private","boolean"))return;
   Object rig=call(animator,"super","387875964");
   for(Object clip:(Collection<?>)call(rig,"volatile","java.util.Collection"))animations.add((String)field(clip,"new"));
   animations.addAll(List.of("STAND","CROUCH","PRONE","SIT","CLIMB","LADDER","JUMP","KNOCKDOWN","FLY","LOGOUT","ZIPLINE"));indexed.add("@character");lastCursor=-1;
  }
  Map<?,?> registry=(Map<?,?>)call(Class.forName("298888696"),"try","java.util.Map");
  // One pack per frame; native loader performs IO asynchronously.
  for(var e:registry.entrySet()){
   String pack=e.getKey().toString();if(indexed.contains(pack))continue;
   Object handle=e.getValue();
   if(!animationPacks.containsKey(pack)){animationPacks.put(pack,handle);call(handle,"synchronized","void",(Consumer<Object>)(v)->{});return;}
   Object data=call(handle,"byte","java.lang.Object");if(data==null)continue;
   Object library=call(data,"const","1255466622");
   for(Object clip:(Collection<?>)call(library,"super","java.util.List")){
    String name=(String)field(clip,"new");if((pack+":"+name).length()<=63)animations.add(pack+":"+name);
    if(pack.equals("npc_generic")&&name.length()<=63)animations.add(name);
   }indexed.add(pack);lastCursor=-1;return;
  }
 }
 void update(Object input)throws Exception{
  String text=(String)call(input,"enum","java.lang.String");int cursor=(Integer)call(input,"public","int");
  if(!text.equals(lastInput)||cursor!=lastCursor){
   hidden=false;cycled=false;selected=0;lastInput=text;lastCursor=cursor;
   result=Completion.suggest(text,cursor,commands,clones,players,animations);
  }
 }
 public void onInput(InputSnapshot input)throws Exception{
  boolean down=input.keyDown(67);
  boolean rising=down&&!medicinePressed;
  medicinePressed=down;
  if(!rising||game==null||connection==null)return;
  // Typing C in chat or an editor must never consume an inventory item.
  if(!(Boolean)call(game,"this","boolean"))return;
  Object player=call(game,"throws","961798934");
  Object capabilities=call(player,"final","769798186");
  if((Boolean)call(capabilities,"import","boolean"))return; // C remains noclip in DEV.
  // C is the stock fast-use medicine key. Send only the edge; the server
  // validates the inventory item, cooldown and health before consuming it.
  Object packet=Class.forName("szmods.bridge.MedicineUse").getConstructor(byte[].class).newInstance((Object)new byte[]{1});
  Object envelope=Class.forName("385693344").getConstructor(Class.forName("499058674")).newInstance(packet);
  call(connection,"else","void",envelope);
 }
 public Object onClientHook(String hook,Object...args)throws Exception{
  if(hook.equals("chat.catalog.v1")){if(incoming.size()<8)incoming.offer(new Catalog(args[0],(byte[])args[1]));return true;}
  if(hook.equals("chat.replace-tab.v1"))return true;
  if(hook.equals("chat.key.v1"))return key(args[0],(Integer)args[1]);
  if(hook.equals("chat.render.v1")){render(args[0]);return true;}
  return null;
 }
 boolean key(Object chat,int key)throws Exception{
  Object input=field(chat,"interface");update(input);
  if(key==258){
   if(result==null||result.values().isEmpty())return true;
   boolean reverse=(Boolean)call(Class.forName("877572775"),"interface","boolean",340)||(Boolean)call(Class.forName("877572775"),"interface","boolean",344);
   if(!cycled){cycleResult=result;cycleInput=lastInput;cycled=true;if(reverse)selected=Math.floorMod(selected-1,cycleResult.values().size());}else selected=Math.floorMod(selected+(reverse?-1:1),cycleResult.values().size());
   String replacement=cycleResult.replace(cycleInput,selected);
   call(input,"short","void",replacement);
   int cursor=cycleResult.start()+replacement.length()-cycleInput.length()+cycleResult.end()-cycleResult.start();
   call(input,"void","void",cursor);lastInput=replacement;lastCursor=cursor;hidden=false;return true;
  }
  if(!hidden&&result!=null&&!result.values().isEmpty()){
   if(key==265||key==264){selected=Math.floorMod(selected+(key==265?-1:1),result.values().size());return true;}
   if(key==256){hidden=true;return true;}
  }
  return false;
 }
 void render(Object chat)throws Exception{
  if(game==null||call(game,"float","622415611")!=chat)return;
  Object input=field(chat,"interface");update(input);renderedRows=0;
  if(hidden||result==null||!lastInput.startsWith("/"))return;
  Object font=Class.forName("36549396").getField("class").get(input);
  Object pos=call(input,"extends","1500420601");int x=(Integer)field(pos,"class"),bottom=(Integer)field(pos,"abstract")-3;
  int row=Math.max(17,(Integer)call(font,"while","int")+5),count=Math.min(8,result.values().size());
  int start=Math.max(0,Math.min(selected-7,result.values().size()-count));int width=460;
  int top=bottom-(count+1)*row;
  call(font,"extends","void",(double)x,(double)top,(double)width,(double)((count+1)*row),0xe51a2028);
  String header=result.values().isEmpty()?result.usage():"Tab / Shift+Tab   ↑↓   "+(selected+1)+" / "+result.values().size();
  call(font,"implements","int",header,x+7,top+3,0xff9caab8);
  for(int i=0;i<count;i++){
   int index=start+i,y=top+(i+1)*row;Completion.Suggestion value=result.values().get(index);
   if(index==selected)call(font,"extends","void",(double)x,(double)y,(double)width,(double)row,0xff31465a);
   String label=value.value();if(label.length()>58)label=label.substring(0,55)+"...";
   call(font,"implements","int",label,x+7,y+3,index==selected?0xffb6ebff:0xffe2e7ed);renderedRows++;
  }
 }
 public Map<String,Object> inspect(){return Map.of("input",lastInput,"selected",selected,"rows",renderedRows,"commands",commands.size(),"animations",animations.size(),"clones",clones.size(),"suggestions",result==null?List.of():result.values().stream().limit(20).map(Completion.Suggestion::value).toList(),"error",lastError);}
 public void onUnload()throws Exception{for(Object handle:animationPacks.values())call(handle,"char","void");animationPacks.clear();}
}
