package workbench;
import java.util.*;
import static workbench.NativeAccess.*;

final class WorkbenchCore {
 final Inventory inventory=new Inventory();final WorkbenchPose pose=new WorkbenchPose();final NativeButtons buttons=new NativeButtons();
 Object game,player,weaponId,selectedSlot,oldModule;Inventory.Entry selected;long frame;double age,actionTime,waitAge;boolean active,closing,waiting,sent,closeAfter;
 boolean jDown,escDown,cancelled;String status="";long nextPoll,nextReport;int changes;
 void log(String text){System.out.println("[WeaponWorkbench] "+text);}
 boolean allowed(){return true;}
 void draw(int width,int height){if(active&&pose.weapon!=null&&!closing)try{buttons.draw(this,width,height);}catch(Exception e){fail(e);}}
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
 void frame(Object currentGame,long number,double delta,boolean j,boolean esc){
  try{
   game=currentGame;frame=number;Object p=get(game,"e");if(p!=player){reset();player=p;}
   if(!allowed()||player==null)return;
   if(j&&!jDown){if(active)close();else open();}if(esc&&!escDown&&active)close();jDown=j;escDown=esc;
   double dt=Math.max(0,Math.min(.1,delta));
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
 void dispose(){reset();}
}
