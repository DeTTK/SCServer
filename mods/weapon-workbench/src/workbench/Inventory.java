package workbench;
import java.util.*;
import static workbench.NativeAccess.*;

/** The same factory predicates and packet as the stock modification window. */
final class Inventory {
 record Entry(Object item,Object address){}
 Object player,weapon,factory,address,stockMenu;List<Entry> items=List.of();
 static Object id(Object item)throws Exception{return item==null?null:call(item,"goto","java.util.UUID");}
 static int number(Object item)throws Exception{return item==null?0:(int)call(item,"continue","int");}
 static String name(Object item)throws Exception {
  if(item==null)return "Снять";Object f=call(item,"interface","1366800141");
  return (String)call(call(f,"for","595713561",item),"throws","java.lang.String");
 }
 static Object[] slots()throws Exception{return (Object[])get(type("1536296909"),"slots");}
 void refresh(Object p)throws Exception {
  player=p;weapon=call(p,"case","211097523");factory=weapon==null?null:call(weapon,"interface","1366800141");address=null;
  Object inv=call(type("276406255"),"implements","276406255",p),manager=get(inv,"short");
  List<Entry> found=new ArrayList<>();
  for(Object container:((Map<?,?>)call(manager,"throws","java.util.Map")).values()){
   for(Object location:(Iterable<?>)call(container,"transient","java.util.List")){
    Object item=call(location,"boolean","211097523");if(item==null)continue;
    Object a=call(location,"return","949164803",manager);if(a==null)continue;
    Entry e=new Entry(item,a);found.add(e);if(Objects.equals(id(item),id(weapon)))address=a;
   }
  }
  items=List.copyOf(found);
 }
 boolean firearm()throws Exception{return weapon!=null&&type("1745367406").isInstance(factory)&&address!=null;}
 Object mounted(Object slot)throws Exception{return call(factory,"continue","211097523",weapon,slot);}
 boolean handgrip()throws Exception {
  for(String name:new String[]{"MOUNT1","MOUNT5"}){
   Object slot=Enum.valueOf((Class)type("1536296909"),name),item=mounted(slot);if(item==null)continue;
   Object attachment=call(item,"interface","1366800141");
   if(type("62393481").isInstance(attachment)&&((Enum<?>)get(attachment,"continue")).name().equals("HANDGRIP"))return true;
  }return false;
 }
 boolean unlocked(Object slot)throws Exception {
  Object item=mounted(slot);return item==null||(boolean)call(call(type("62393481"),"extends","381378872",item),"super","boolean");
 }
 boolean compatible(Object slot,Entry e)throws Exception {
  Object f=call(e.item,"interface","1366800141");
  return type("62393481").isInstance(f)&&(boolean)call(stockMenu,"goto","boolean",slot,f);
 }
 List<Entry> candidates(Object slot)throws Exception {
  List<Entry> result=new ArrayList<>();
  for(Object item:(List<?>)call(stockMenu,"finally","java.util.List")){
   Entry match=null;for(Entry e:items)if(Objects.equals(id(e.item),id(item))){match=e;break;}
   // Built-ins are also provided by the stock window; no inventory source needed.
   if(match==null)match=new Entry(item,null);
   if(compatible(slot,match))result.add(match);
  }return result;
 }
 void open()throws Exception {
  stockMenu=type("471825707").getConstructor(type("622415611"),type("949164803"),type("211097523")).newInstance(null,address,weapon);
  Object game=call(type("1402131154"),"try","1402131154");
  call(stockMenu,"do","void",game,(int)call(game,"while","int"),(int)call(game,"throws","int"));
  set(stockMenu,"long",false);
 }
 void send(Object slot,Entry selected)throws Exception {
  if(!firearm()||!unlocked(slot)||selected!=null&&!compatible(slot,selected))throw new IllegalStateException("Крепление или предмет изменились");
  if(selected!=null&&selected.address()!=null){
   Object wanted=id(selected.item());Entry live=null;for(Entry e:items)if(Objects.equals(wanted,id(e.item()))){live=e;break;}
   if(live==null)throw new IllegalStateException("Выбранного модуля уже нет в рюкзаке");selected=live;
  }
  // Exact click handler from the normal modification window: built-ins, address,
  // packet and native inventory prediction are handled by the game itself.
  call(stockMenu,"switch","void",slot,selected==null?null:selected.item);
 }
}
