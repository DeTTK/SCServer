import java.util.*;import java.lang.reflect.*;import com.google.gson.*;
/** Cached native glyph metrics: fast magazine bearings and exact sparse action-counter values. */
final class HudNumberInk {
 final Object gui;final Map<String,Object> widgets=new HashMap<>(),positions=new HashMap<>();final Map<String,Map<String,int[]>> offsets=new HashMap<>();final Map<String,Boolean> right=new HashMap<>();
 static Object c(Object o,String n,String t,Object...a)throws Exception{return HudNative.call(o,n,t,a);}
 static Object g(Object o,String n)throws Exception{return HudNative.get(o,n);}
 HudNumberInk(Object gui,Set<String> ids)throws Exception{
  this.gui=gui;Map<?,?> all=(Map<?,?>)g(gui,"continue");
  for(String id:ids){Object widget=all.get(id);widgets.put(id,widget);positions.put(id,g(widget,"final"));Object style=c(c(widget,"else","1287567536"),"new","695281613");String align=((Enum<?>)c(style,"native","1351327104")).name();if(!align.equals("Left")&&!align.equals("Right"))throw new IllegalStateException("Numeric cell needs a fixed edge: "+id);right.put(id,align.equals("Right"));offsets.put(id,new LinkedHashMap<>());}
 }
 static void value(Object widget,String text)throws Exception{widget.getClass().getField("return").set(widget,text);widget.getClass().getField("if").set(widget,null);}
 void warm()throws Exception{
  Field position=Class.forName("36549396").getField("final");
  for(String id:widgets.keySet()){
   Object widget=widgets.get(id);String saved=(String)g(widget,"return");
   for(int pass=0;pass<2;pass++)for(char digit:"0123456789".toCharArray()){
    String sample=right.get(id)?String.valueOf(digit):String.valueOf(digit).repeat(3);value(widget,sample);position.set(widget,positions.get(id));
    JsonObject measurement=NativeHudInkAlignmentV2.prepare(gui,Set.of(id)).get(0).getAsJsonObject();offsets.get(id).put(String.valueOf(digit),new int[]{measurement.get("offsetX").getAsInt(),measurement.get("offsetY").getAsInt()});
   }
   value(widget,saved);apply(id,saved);
  }
 }
 void apply(String id,String text)throws Exception{
  if(!widgets.containsKey(id)||text.isEmpty())return;String key=right.get(id)?text:text.substring(0,1);Map<String,int[]> cache=offsets.get(id);int[] delta=cache.get(key);
  // Right-aligned proportional text also depends on the complete rounded advance.
  // Medicine/grenade quantities change on actions; cache each value, never measure per frame.
  if(delta==null&&right.get(id)){Class.forName("36549396").getField("final").set(widgets.get(id),positions.get(id));JsonObject measurement=NativeHudInkAlignmentV2.prepare(gui,Set.of(id)).get(0).getAsJsonObject();delta=new int[]{measurement.get("offsetX").getAsInt(),measurement.get("offsetY").getAsInt()};if(cache.size()>=128)cache.remove(cache.keySet().iterator().next());cache.put(key,delta);}
  if(delta==null)return;

  Object base=positions.get(id);int x=((Number)g(base,"class")).intValue(),y=((Number)g(base,"abstract")).intValue();Class.forName("36549396").getField("final").set(widgets.get(id),NativeHudInkAlignmentV2.pos(x+delta[0],y+delta[1]));
 }
}
