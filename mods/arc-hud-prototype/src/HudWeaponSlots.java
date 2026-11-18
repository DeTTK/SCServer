import java.nio.file.Path;
import java.util.*;
import static org.lwjgl.opengl.GL33.*;

/** Two permanent native Figma cards. Selection changes their heights, never their order. */
final class HudWeaponSlots implements AutoCloseable {
 static Object c(Object o,String n,String t,Object...a)throws Exception{return HudNative.call(o,n,t,a);}
 static Object g(Object o,String n)throws Exception{return HudNative.get(o,n);}
 static int number(Object x){return ((Number)x).intValue();}
 static Object pos(int x,int y)throws Exception{return Class.forName("1500420601").getConstructor(int.class,int.class).newInstance(x,y);}
 static void show(Object widget,boolean yes)throws Exception{c(widget,"else","void",yes);}
 static void alpha(Object widget,double value)throws Exception{Object node=c(widget,"else","1287567536");node.getClass().getField("if").set(node,value);}
 static final Set<String> DETAIL=Set.of("svg-159","svg-160","svg-161","svg-empty-firemode");
 static class Card {
  Object gui,group;Map<String,Object> widgets;int x,y,w,fullH,compactH,slot;double expanded;
  final Map<String,Object> logicalPositions=new HashMap<>();HudCardGrid grid;HudNumberInk numbers;
  String title="",caliber="",ammoType="";int ammo=-1,reserve,pips;double reload=-1;boolean present;
  final HudWeaponPreview preview=new HudWeaponPreview();final HudFireModeIcons modeIcons=new HudFireModeIcons();
  Card(Path root,String state,Object game,int width,int height,int slot)throws Throwable {
   this.slot=slot;gui=HudNative.gui(root,state,game,width,height);widgets=(Map<String,Object>)g(gui,"continue");
   NativeHudTypographyV2.prepare(gui);
   for(var e:widgets.entrySet())if(e.getKey().startsWith("svg-group-")&&!e.getKey().equals("svg-group-ActiveWeapon"))show(e.getValue(),false);
   for(String id:List.of("svg-147","svg-148","svg-150","svg-154","svg-155","svg-156","svg-157","svg-158","svg-162"))show(widgets.get(id),false);
   group=widgets.get("svg-group-ActiveWeapon");Object p=c(group,"extends","1500420601"),size=c(group,"switch","61438557");x=number(g(p,"class"));y=number(g(p,"abstract"));w=number(g(size,"try"));grid=new HudCardGrid(w);fullH=grid.fullHeight;compactH=grid.compactHeight;
   font("svg-152",8,"Center");font("svg-153",9,"Left");font("svg-159",22,"Left");font("svg-160",7.5,"Left");font("svg-161",9,"Left");font("svg-empty-firemode",10,"Left");show(widgets.get("svg-empty-firemode"),true);
   box("svg-151",grid.key);box("svg-152",grid.key);box("svg-153",grid.title);box("svg-159",grid.ammo);box("svg-160",grid.reload);box("svg-161",grid.reserve);box("svg-empty-firemode",grid.ammoType);
   text("svg-152",String.valueOf(slot));for(String id:List.of("svg-153","svg-159","svg-160","svg-161","svg-empty-firemode"))logicalPositions.put(id,g(widgets.get(id),"final"));
   numbers=new HudNumberInk(gui,Set.of("svg-159","svg-161"));NativeHudInkAlignmentV2.prepare(gui,Set.of("svg-152","svg-153","svg-160"));

   expanded=slot==1?1:0;
  }
  void box(String id,HudCardGrid.Box cell)throws Exception{Object widget=widgets.get(id);c(widget,"int","void",Class.forName("61438557").getConstructor(int.class,int.class).newInstance(cell.w(),cell.h()));Class.forName("36549396").getField("final").set(widget,pos(cell.x(),cell.y()));}
  void font(String id,double units,String horizontal)throws Exception{Object widget=widgets.get(id);c(widget,"native","void",units*grid.scale);Object style=c(c(widget,"else","1287567536"),"new","695281613");c(style,"int","void",Class.forName("1351327104").getField(horizontal).get(null));c(style,"switch","void",Class.forName("780508252").getField("Center").get(null));}
  void text(String id,String text)throws Exception {
   Object widget=widgets.get(id);if(Objects.equals(g(widget,"return"),text))return;
   Object font=c(g(widget,"class"),"native","186969209");int max=number(g(c(widget,"switch","61438557"),"try"));
   while(text.length()>1&&number(c(font,"boolean","int",text))>max-1)text=text.substring(0,text.length()-1);
   widget.getClass().getField("return").set(widget,text);widget.getClass().getField("if").set(widget,null);if(numbers!=null)numbers.apply(id,text);if((id.equals("svg-160")||id.equals("svg-empty-firemode"))&&!text.isEmpty()&&logicalPositions.containsKey(id)){Class.forName("36549396").getField("final").set(widget,logicalPositions.get(id));NativeHudInkAlignmentV2.prepare(gui,Set.of(id));}
  }
  void update(Object item,boolean selected,int rounds,int stock,int mode,double loading,String type)throws Exception {
   present=item!=null;if(!present)return;
   String next=(String)c(c(c(item,"interface","1366800141"),"for","595713561",item),"throws","java.lang.String");boolean changed=!Objects.equals(title,next);title=next;text("svg-153",title);
   if(selected){caliber=HudAmmoLabel.loaded(item);ammoType=type;ammo=rounds;reserve=stock;pips=mode;reload=loading;}
   if(selected||expanded>0)preview.update(item);
   text("svg-159",ammo<0?"":String.format(Locale.ROOT,"%03d",Math.min(999,ammo)));text("svg-160",caliber);text("svg-empty-firemode",ammoType);text("svg-161",reserve<0?"":String.format(Locale.ROOT,"%,d",reserve).replace(',',' '));
   if(changed){Class.forName("36549396").getField("final").set(widgets.get("svg-153"),logicalPositions.get("svg-153"));NativeHudInkAlignmentV2.prepare(gui,Set.of("svg-153"));}

  }
  double eased(){return expanded*expanded*(3-2*expanded);}
  int height(){return compactH+(int)Math.round((fullH-compactH)*eased());}
  void rect(Object graphics,double x,double y,double width,double height,int color)throws Exception{c(graphics,"extends","void",x,y,width,height,color);}
  void draw(int top,int cardH,Object graphics,int width,int height,double flash)throws Exception {
   if(!present)return;double detail=Math.max(0,(eased()-.55)/.45);
   Class.forName("36549396").getField("final").set(group,pos(x,top));for(String id:DETAIL)alpha(widgets.get(id),detail);
   boolean clipped=glIsEnabled(GL_SCISSOR_TEST);int[] oldClip=new int[4];glGetIntegerv(GL_SCISSOR_BOX,oldClip);glEnable(GL_SCISSOR_TEST);glScissor(x,height-top-cardH,w,cardH);
   try {
    int line=grid.line,pad=grid.padding;rect(graphics,x,top,w,cardH,0x88101412);rect(graphics,x+line,top+line,w-2*line,Math.min(cardH,grid.header)-line,0x18383e3a);
    int border=0x55daddd6;rect(graphics,x,top,w,line,border);rect(graphics,x,top+cardH-line,w,line,border);rect(graphics,x,top,line,cardH,border);rect(graphics,x+w-line,top,line,cardH,border);
    if(detail>0)rect(graphics,x+pad,top+grid.header,w-2*pad,line,((int)(68*detail)<<24)|0xdaddd6);
    c(gui,"package","void",-2000,-2000,0f);
    glEnable(GL_SCISSOR_TEST);glScissor(x,height-top-cardH,w,cardH);
    if(flash>0)rect(graphics,x+pad,top+grid.header,w-2*pad,line,((int)(190*flash)<<24)|0xeee9d0);
    if(detail>0){modeIcons.draw(graphics,grid,x,top,pips,width,height,detail);
     if(reload>=0)rect(graphics,x+pad,top+cardH-2*line,(w-2*pad)*reload,line,((int)(255*detail)<<24)|0xd6c49b);
     HudCardGrid.Box image=grid.image;preview.draw(x+image.x(),top+image.y(),image.w(),image.h(),width,height,detail);
    }
   } finally {glScissor(oldClip[0],oldClip[1],oldClip[2],oldClip[3]);HudSurface.toggle(GL_SCISSOR_TEST,clipped);}
  }

  void close(){preview.close();modeIcons.close();}
 }
 Card first,second;int activeSlot=1;double firstHeight,secondHeight;int occupiedLeft,occupiedTop,occupiedRight,occupiedBottom,visibleCards;
 void includeDrawn(Card card,int top,int height){if(!card.present||height<=0)return;occupiedLeft=Math.min(occupiedLeft,card.x);occupiedTop=Math.min(occupiedTop,top);occupiedRight=Math.max(occupiedRight,card.x+card.w);occupiedBottom=Math.max(occupiedBottom,top+height);visibleCards++;}
 com.google.gson.JsonObject report(){var r=new com.google.gson.JsonObject();r.addProperty("cards",visibleCards);r.addProperty("left",occupiedLeft);r.addProperty("top",occupiedTop);r.addProperty("right",occupiedRight);r.addProperty("bottom",occupiedBottom);return r;}
 void init(Path root,String state,Object game,int w,int h)throws Throwable{close();first=new Card(root,state,game,w,h,1);second=new Card(root,state,game,w,h,2);}
 void update(Object primary,Object secondary,boolean usingSecondary,int ammo,int reserve,int pips,double reload,String type)throws Exception{activeSlot=usingSecondary?2:1;first.update(primary,!usingSecondary,ammo,reserve,pips,reload,type);second.update(secondary,usingSecondary,ammo,reserve,pips,reload,type);}
 void draw(double dt,boolean animate,Object graphics,int w,int h,double flash)throws Exception {
  double step=animate?dt/.20:1;first.expanded=approach(first.expanded,activeSlot==1?1:0,step);second.expanded=approach(second.expanded,activeSlot==2?1:0,step);
  int gap=Math.max(1,first.grid.p(2)),firstH=first.height(),secondH=first.fullH+first.compactH-firstH,count=(first.present?1:0)+(second.present?1:0);int total=(first.present?firstH:0)+(second.present?secondH:0)+Math.max(0,count-1)*gap,bottom=first.y+first.fullH+gap+first.compactH,top=bottom-total;
  occupiedLeft=w;occupiedTop=h;occupiedRight=occupiedBottom=visibleCards=0;
  first.draw(top,firstH,graphics,w,h,activeSlot==1?flash:0);includeDrawn(first,top,firstH);
  int secondTop=top+(first.present?firstH+gap:0);second.draw(secondTop,secondH,graphics,w,h,activeSlot==2?flash:0);includeDrawn(second,secondTop,secondH);firstHeight=firstH;secondHeight=secondH;
  if(visibleCards==0){occupiedLeft=first.x;occupiedRight=first.x+first.w;occupiedTop=occupiedBottom=bottom;}
 }
 static double approach(double value,double target,double step){return value<target?Math.min(target,value+step):Math.max(target,value-step);}
 public void close(){if(first!=null)first.close();if(second!=null)second.close();first=second=null;}
}
