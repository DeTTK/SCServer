package workbench;
import java.util.*;
import java.util.function.Predicate;
import imgui.ImGui;
import static workbench.NativeAccess.*;

/** Actual stock attachment button instances, including their native dropdown children. */
final class NativeButtons {
 final Map<Object,Object> buttons=new LinkedHashMap<>();boolean mouseWasDown;Object game;boolean focus;
 void build(WorkbenchCore mod,int width,int height)throws Exception {
  clear();Object menu=mod.inventory.stockMenu;
  for(Object slot:Inventory.slots()){
   String name=((Enum<?>)slot).name();if(!name.startsWith("SIGHT")&&!name.startsWith("MOUNT"))continue;
   List<Inventory.Entry> list=mod.inventory.candidates(slot);
   if(!mod.inventory.unlocked(slot)||list.isEmpty()&&mod.inventory.mounted(slot)==null)continue;
   Predicate<Object> predicate=new Predicate<Object>(){public boolean test(Object item){try{return type("62393481").isInstance(call(item,"interface","1366800141"))&&(boolean)call(menu,"goto","boolean",slot,call(item,"interface","1366800141"));}catch(Exception e){throw new IllegalStateException(e);}}};
   Object button=Class.forName("workbench.NativeAttachmentButton",true,getClass().getClassLoader()).getConstructor(Object.class,Object.class,Object.class,Predicate.class)
     .newInstance(menu,slot,mod.inventory.mounted(slot),predicate);
   buttons.put(slot,button);
  }
 }
 void clear(){buttons.clear();mouseWasDown=false;}
 void acquire(Object g)throws Exception {if(game==null){game=g;focus=(boolean)call(g,"try","boolean");}call(g,"import","void");}
 void release(){try{if(game!=null&&focus&&call(game,"float","622415611")==null)call(game,"extends","void");}catch(Exception ignored){}game=null;}
 void draw(WorkbenchCore mod,int width,int height)throws Exception {
  acquire(mod.game);
  call(call(type("976516049"),"try","976516049"),"throw","void");
  Object mouse=type("1500420601").getConstructor(int.class,int.class).newInstance((int)ImGui.getMousePosX(),(int)ImGui.getMousePosY());
  int i=0;for(Map.Entry<Object,Object> entry:buttons.entrySet()){
   Object slot=entry.getKey(),b=entry.getValue();String name=((Enum<?>)slot).name();
   float[] anchor=mod.pose.screen(name,width,height);if(anchor==null)continue;
   int x=(int)Math.max(10,Math.min(width-82,anchor[0]+(i%2==0?-100:30)));
   int y=(int)Math.max(40,Math.min(height-100,anchor[1]-100-(i/2)*90));i++;
   call(b,"byte","void",type("1500420601").getConstructor(int.class,int.class).newInstance(x,y));
   call(b,"package","void",mouse,(float)mod.blend());
   call(b,"super","void");
   if(!mod.busy()&&ImGui.isMouseClicked(0))call(b,"boolean","void",mouse,0);
   if(!mod.busy()&&ImGui.isMouseReleased(0))call(b,"import","void",mouse,0);
  }
 }
}
