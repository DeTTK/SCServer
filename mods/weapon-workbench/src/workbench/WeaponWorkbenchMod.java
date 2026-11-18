package workbench;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import szmods.api.*;
import static workbench.NativeAccess.*;

public final class WeaponWorkbenchMod implements ClientMod {
 final Inventory inventory=new Inventory();final WorkbenchPose pose=new WorkbenchPose();final NativeButtons buttons=new NativeButtons();
 ModContext context;Object game,player,weaponId,selectedSlot,oldModule;Inventory.Entry selected;long frame;double age,actionTime,waitAge;boolean active,closing,waiting,sent,closeAfter;
 boolean jDown,escDown,cancelled;String status="";long nextPoll,nextReport;int changes;
 SettingsRegistry.NumberSetting binding;
 String nativePreview;
 public void onLoad(ModContext ctx)throws Exception {context=ctx;Files.createDirectories(ctx.configDirectory());log("Local FP workbench prototype; stock attachment UI and transactions");}
 public void registerKeybinds(KeybindRegistry k){k.register("toggle","Модификация оружия в руках",74);}
 public void registerSettings(SettingsRegistry s){binding=s.registerTab("workbench","Оружие в руках").numberSetting("key","Клавиша (GLFW)",74,32,348);}
 public void registerHud(HudRegistry h){h.register("workbench",(hud,w,hg,p)->{if(active&&pose.weapon!=null&&!closing)try{buttons.draw(this,w,hg);}catch(Exception e){fail(e);}});}
 void log(String text){context.log(text);}
 boolean allowed(){return Boolean.getBoolean("sz.weaponWorkbench.local");}
 boolean busy(){return selectedSlot!=null;}
 double poseWeight(){return closing?1-LiftMotion.smoother((age-.65)/.30):LiftMotion.smoother(age/.18);}
 double blend(){return closing?1-LiftMotion.smoother(age/LiftMotion.EXIT):LiftMotion.smoother(age/LiftMotion.DURATION);}
 void open()throws Exception {
  if(!allowed()||player==null||call(game,"float","622415611")!=null)return;
  inventory.refresh(player);if(!inventory.firearm()){status="Возьмите огнестрельное оружие";return;}
  inventory.open();weaponId=Inventory.id(inventory.weapon);active=true;closing=waiting=sent=closeAfter=false;age=0;pose.clear();
  status="";buttons.build(this,(int)call(game,"while","int"),(int)call(game,"throws","int"));log("Opened: "+Inventory.name(inventory.weapon)+"; native buttons="+buttons.buttons.size());
 }
 void close()throws Exception{if(busy()){closeAfter=true;return;}if(closing)return;if(pose.motion==null){reset();return;}pose.beginExit();closing=true;age=0;buttons.release();}
 void reset(){if(active&&inventory.stockMenu!=null)try{call(inventory.stockMenu,"public","void");}catch(Exception ignored){}active=closing=waiting=sent=closeAfter=false;selectedSlot=null;selected=null;buttons.release();buttons.clear();pose.clear();}
 void fail(Exception e){status=e.toString();log("Prototype stopped: "+e);for(Throwable t=e.getCause();t!=null;t=t.getCause())log("Caused by "+t);reset();}
 void select(Object slot,Object item)throws Exception {
  if(!active||busy()||closing||age<LiftMotion.DURATION)return;
  inventory.refresh(player);if(!Objects.equals(weaponId,Inventory.id(inventory.weapon))){reset();return;}
  Inventory.Entry found=null;
  if(item!=null){for(Inventory.Entry e:inventory.candidates(slot))if(Inventory.number(e.item())==Inventory.number(item)&&(e.address()==null||Objects.equals(Inventory.id(e.item()),Inventory.id(item)))){found=e;break;}if(found==null)return;}
  if(item==null&&inventory.mounted(slot)==null)return;
  if(item!=null&&Inventory.number(item)==Inventory.number(inventory.mounted(slot)))return;
  selectedSlot=slot;selected=found;actionTime=waitAge=0;sent=waiting=true;status="Установка";
  inventory.send(slot,found);inventory.refresh(player);predicted=inventory.weapon;
 }
 public void onFrame(ClientFrame f){
  try{
   game=f.game();frame=f.number();Object p=get(game,"e");if(p!=player){reset();player=p;}
   if(!allowed()||player==null)return;
   boolean j=(boolean)call(type("877572775"),"szmods$original$interface","boolean",binding==null?74:(int)Math.round(binding.get()));
   boolean esc=(boolean)call(type("877572775"),"szmods$original$interface","boolean",256);
   if(j&&!jDown){if(active)close();else open();}if(esc&&!escDown&&active)close();jDown=j;escDown=esc;
   double dt=Math.max(0,Math.min(.1,f.deltaSeconds()));
   if(active){age+=dt;
    inventory.refresh(player);
    if(!Objects.equals(weaponId,Inventory.id(inventory.weapon))||call(game,"float","622415611")!=null||!(boolean)call(player,"super","boolean")){reset();return;}
    if(closing&&age>=LiftMotion.EXIT){reset();return;}
    if(busy()){
     if(waiting){waitAge+=dt;
      // The native acknowledgement must match this request's owner and sequence.
      Object current=inventory.mounted(selectedSlot);int expected=selected==null?0:Inventory.number(selected.item());
      if(Inventory.number(current)==expected&&authoritative()){waiting=false;changes++;status="Готово";}
      else if(waitAge>4){waiting=false;status="Ответ не подтверждён; повтор не отправлен";closeAfter=true;}
     }
     if(!waiting){selectedSlot=null;selected=null;sent=false;inventory.open();buttons.build(this,(int)call(game,"while","int"),(int)call(game,"throws","int"));if(closeAfter)close();}
    }
   }
   qa();
  }catch(Exception e){fail(e);}
 }
 Object predicted,requestPacket;boolean acknowledged;
 boolean authoritative()throws Exception {return acknowledged;}
 public Object onClientHook(String hook,Object...args){try{return hook(hook,args);}catch(Exception e){fail(e);return null;}}
 Object hook(String hook,Object...args)throws Exception {
  if(hook.equals("weapon.workbench-request.v1")&&active&&busy()){requestPacket=args[0];acknowledged=false;return null;}
  if(hook.equals("weapon.workbench-ack.v1")&&requestPacket!=null){
   if(Objects.equals(call(args[0],"boolean","int"),call(requestPacket,"boolean","int"))&&Objects.equals(call(args[0],"volatile","int"),call(requestPacket,"volatile","int")))acknowledged=true;return null;
  }
  if(hook.equals("weapon.workbench-select.v1")&&active){select(args[0],args[1]);return Boolean.TRUE;}
  if(!active)return null;
  if(hook.equals("weapon.workbench-camera-pose.v1")&&get(args[1],"assert")==player){
   if(args[0]==null)return null;
   workbench.Math3.Q q=LiftMotion.slerp(quaternion(args[0]),workbench.Math3.Q.ID,poseWeight());
   return type("600627034").getConstructor(float.class,float.class,float.class,float.class).newInstance((float)q.x(),(float)q.y(),(float)q.z(),(float)q.w());
  }
  if(hook.equals("weapon.workbench-fov.v1")){double original=((Number)args[0]).doubleValue();return (float)(original+(70-original)*poseWeight());}
  if(hook.equals("weapon.workbench-context.v1")&&args[0]==player)return pose.prepare(args[1],this);
  if(hook.equals("input.ui-capture.v1")||hook.equals("input.action-block.v1")||hook.equals("view.block-body-look.v1"))return Boolean.TRUE;
  if(hook.equals("input.key.v1"))return Boolean.FALSE;
  return null;
 }
 void qa()throws Exception {
  if(System.getProperty("sz.render.qa.directory")==null)return;
  long now=System.nanoTime();if(now<nextPoll)return;nextPoll=now+100_000_000;
  Path command=context.configDirectory().resolve("qa-command.json");
  if(Files.exists(command)){JsonObject q=JsonParser.parseString(Files.readString(command)).getAsJsonObject();Files.delete(command);String action=q.get("action").getAsString();
   if(action.equals("preview")){reset();nativePreview=q.get("clip").getAsString();open();}
   if(action.equals("normal")){reset();nativePreview=null;}
   if(action.equals("toggle")){if(active)close();else open();}
   if(action.equals("select")){Object slot=Enum.valueOf((Class)type("1536296909"),q.get("slot").getAsString());int number=q.get("item").getAsInt();Object item=null;for(Inventory.Entry e:inventory.candidates(slot))if(Inventory.number(e.item())==number)item=e.item();if(number==0||item!=null)buttons.buttons.get(slot).getClass().getMethod("assert",type("211097523")).invoke(buttons.buttons.get(slot),item);}
   if(action.equals("dropdown")){Object slot=Enum.valueOf((Class)type("1536296909"),q.get("slot").getAsString());call(buttons.buttons.get(slot),"volatile","void");}
  }
  JsonObject report=new JsonObject();report.addProperty("active",active);report.addProperty("closing",closing);report.addProperty("status",status);report.addProperty("changes",changes);report.addProperty("waiting",waiting);report.addProperty("time",actionTime);report.addProperty("fpPrepared",pose.adapter!=null);report.addProperty("weaponUuid",String.valueOf(weaponId));report.addProperty("acknowledged",acknowledged);
  report.addProperty("detachedModuleResourceRequested",false);if(pose.baseHands!=null)report.addProperty("originalGrip",pose.camera.inverse().compose(pose.baseHands.sample("RightHand")).p().toString());
  report.addProperty("poseWeight",poseWeight());report.addProperty("nativeHandoff",closing&&poseWeight()==0);report.addProperty("gripFrames",pose.gripFrames);report.addProperty("maxGripErrorMeters",pose.maxGripError);report.addProperty("maxGripAngleRadians",pose.maxGripAngle);
  report.addProperty("handgrip",pose.handgrip);report.addProperty("leftContact",pose.leftContact);report.addProperty("leftGripErrorMeters",pose.leftGripError);
  JsonArray mounts=new JsonArray();if(active)for(var e:buttons.buttons.entrySet()){JsonObject m=new JsonObject();m.addProperty("slot",((Enum<?>)e.getKey()).name());m.addProperty("installed",Inventory.number(inventory.mounted(e.getKey())));JsonArray a=new JsonArray();for(Inventory.Entry candidate:inventory.candidates(e.getKey())){JsonObject c=new JsonObject();c.addProperty("id",Inventory.number(candidate.item()));c.addProperty("name",Inventory.name(candidate.item()));a.add(c);}m.add("candidates",a);mounts.add(m);}report.add("mounts",mounts);
  Files.writeString(context.configDirectory().resolve("qa-state.json"),report.toString());
 }
 public void onUnload(){reset();}
}
