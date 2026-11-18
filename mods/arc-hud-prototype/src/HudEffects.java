import java.time.*;
import java.util.*;
import java.nio.file.Path;
import com.google.gson.*;

/** Presentation of the same effect instances and status providers used by the native HUD.
 * Donor type only: the native build merges this into the existing HUD host class. */
final class HudEffects {
 // source, icon, item, group, text, end, duration, positive, opacity, seen, infection, title, overridden, rendered text, remaining ms
 final Map<String,Object[]> entries=new LinkedHashMap<>();
 final Map<String,Object> sprites=new HashMap<>();
 final Map<String,HudWeaponPreview> symbols=new HashMap<>();
 int screenWidth,screenHeight;
 Object graphics,badgeRenderer,font; double scale; long nextSample; Object owner;
 int activeBoosts,activeStates; String error="";
 double previousStatusRight,previousStatusBottom;boolean statusAnchorKnown;
 static Object c(Object o,String n,String t,Object...a)throws Exception{return HudNative.call(o,n,t,a);}
 static Object g(Object o,String n)throws Exception{return HudNative.get(o,n);}
 static Object base(Object o,String n)throws Exception{return Class.forName("1448893284").getField(n).get(o);}
 static Object infection(Object o,String n)throws Exception{return Class.forName("1706339042").getField(n).get(o);}
 static double number(Object o){return ((Number)o).doubleValue();}
 static double clamp(double n){return Math.max(0,Math.min(1,n));}
 static String phase(Object o){return o==null?"HIDDEN":((Enum<?>)o).name();}
 static String timer(long millis){long seconds=Math.max(1,(millis+999)/1000);return seconds>=3600?seconds/3600+":"+String.format(Locale.ROOT,"%02d",seconds/60%60):seconds>=60?seconds/60+":"+String.format(Locale.ROOT,"%02d",seconds%60):Long.toString(seconds);}
 Object[] put(String key,Object source,Object icon,Object item,String group,String text,Instant end,long duration,boolean positive,boolean infection,String title){
  Object[] e=entries.get(key);
  if(e==null){e=new Object[22];e[8]=0d;e[16]=0d;e[17]=0d;entries.put(key,e);}
  e[0]=source;e[1]=icon;e[2]=item;e[3]=group;e[4]=text;e[5]=end;e[6]=duration;e[7]=positive;e[9]=true;e[10]=infection;e[11]=title;e[12]=false;e[13]="";e[14]=-1L;e[15]=System.nanoTime();e[18]=0;e[19]=-1d;e[20]=-1d;e[21]=0xffa9ada3;
  return e;
 }
 void sample(Object player)throws Exception{
  activeBoosts=activeStates=0;for(Object[] e:entries.values())e[9]=false;
  Object manager=g(Class.forName("1238132202"),"if");Instant clock=(Instant)c(Class.forName("1553688601"),"const","java.time.Instant");
  for(Object effect:(List<?>)g(manager,"void")){
   String state=phase(g(effect,"new"));if(state.equals("HIDDEN")||state.equals("COMPRESSION")||state.equals("DISAPPEAR"))continue;
   Object item=g(effect,"this"),nativeEffect=g(effect,"char");
   String group=((Enum<?>)c(nativeEffect,"boolean","1585835196")).name(),title=group;
   Object key=g(Class.forName("626983914"),"goto");
   Object visual=c(nativeEffect,"instanceof","java.lang.Object",key);
   Object icon=visual==null?null:c(visual,"extends","779455578");
   boolean medicineCategory=false;
   if(item!=null){
    Object profile=c(item,"interface","1366800141");
    if(Class.forName("1305711328").isInstance(profile)){
     Object category=g(profile,"short");if(category!=null){group=category.toString();medicineCategory=true;}
     Object label=g(profile,"interface");if(label!=null)title=(String)c(label,"throws","java.lang.String");
    }
   }
   boolean untimed=(Boolean)c(effect,"abstract","boolean");
   Instant end=untimed?null:(Instant)g(effect,"short");Duration duration=(Duration)g(effect,"native");
   if(end!=null&&!clock.isBefore(end))continue;
   // Never substitute the currently selected medicine or an inventory stack for this source item.
   String text=(String)g(effect,"int");
   Object[] entry=put("boost:"+(medicineCategory?group:g(effect,"synchronized")),effect,icon,item,group,text,end,duration==null?0:duration.toMillis(),!(Boolean)c(effect,"native","boolean"),false,title);
   entry[12]=g(effect,"super");
   activeBoosts++;
  }
  Object hud=g(Class.forName("180003465"),"break");
  int index=0;
  for(Object state:(List<?>)g(hud,"a")){
   String id="state:"+index++;String animation=phase(base(state,"else"));
   if(animation.equals("HIDDEN")||animation.equals("COMPRESSION")||animation.equals("DISAPPEAR"))continue;
   if(Class.forName("1706339042").isInstance(state)){
    Object value=infection(state,"float");if(value==null)continue;
    Object type=infection(state,"boolean");int level=((Number)c(value,"boolean","int")).intValue();
    if(level==0)level=((Number)infection(state,"protected")).intValue();
    double intensity=number(c(value,"default","float"));
    String text=Long.toString(Math.round(intensity));
    Object[] entry=put(id,state,infection(state,"static"),null,((Enum<?>)type).name(),text,null,0,false,true,infection(state,"return").toString());
    entry[18]=level;entry[19]=intensity;entry[21]=0xff000000|((Number)c(type,"class","int",level)).intValue();
    Object config=c(value,"default","1077465001");float[] thresholds=(float[])c(config,"abstract","[F");double maximum=0;for(float threshold:thresholds)maximum=Math.max(maximum,threshold);if(maximum>0)entry[20]=clamp(intensity/maximum);
   }else{
    Object descriptor=base(state,"enum");if(descriptor==null)continue;
    Object text=c(state,"strictfp","java.lang.String");
    String kind=state.getClass().getName();if(kind.equals("973373427"))kind=((Enum<?>)g(state,"import")).name();
    String value=text==null?"":text.toString();Instant end=null;long duration=0;
    if(kind.equals("1005375721")){
     kind+=":"+((Enum<?>)g(state,"extends")).name();Instant started=(Instant)g(state,"const");duration=((Number)g(state,"catch")).longValue();
     if(started!=null&&duration>0){end=started.plusMillis(duration);long remaining=Math.max(0,Duration.between(clock,end).toMillis());value=remaining>0?(remaining<60000?String.format(Locale.ROOT,"%.1fс",remaining/1000d):timer(remaining)):"Готов";}else value="Готов";
    }
    Object[] entry=put(id,state,g(descriptor,"extends"),null,kind,value,end,duration,state.getClass().getName().equals("334302088"),false,g(descriptor,"int").toString());
    entry[21]=0xff000000|((Number)g(descriptor,"void")).intValue();if(end!=null&&duration>0)entry[20]=clamp(Duration.between(clock,end).toMillis()/(double)duration);
   }
   activeStates++;
  }
 }
 static String symbolName(String key){return switch(key){
  case "MEDICINE","item.effects.effect_type.healing"->"heart-pulse";
  case "item.effects.effect_type.long_time_medicine"->"biceps-flexed";
  case "item.effects.effect_type.mobility"->"footprints";
  case "item.effects.effect_type.short_time_medicine"->"timer";
  case "item.effects.effect_type.protection"->"droplet-off";
  case "item.effects.effect_type.accumulation"->"flask-conical";
  case "item.effects.effect_type.rad_protection"->"radiation";
  case "item.effects.effect_type.freezing_protection","item.effects.effect_type.low_antifreezing"->"snowflake";
  case "item.effects.effect_type.booster"->"package-plus";
  case "item.effects.effect_type.clan_booster"->"users-round";
  case "item.effects.effect_type.inside"->"shield-plus";
  case "item.effects.effect_type.inside_epi"->"syringe";
  case "item.effects.effect_type.inside_morph"->"pill";
  case "item.effects.effect_type.inside_nootrop"->"brain";
  case "item.effects.effect_type.inside_reset"->"rotate-ccw";
  case "item.effects.effect_type.inside_rest"->"moon";
  case "334302088"->"shield-check";
  case "1005375721","1005375721:DEFAULT","1005375721:SNIPER"->"rotate-ccw";
  case "1688377162"->"weight";
  case "1521246813"->"wrench";
  case "RADIATION"->"radiation";
  case "BIOLOGICAL"->"biohazard";
  case "THERMAL"->"thermometer";
  case "COMBUSTION"->"flame";
  case "PSYCHO"->"brain";
  case "FROST"->"snowflake";
  case "BLEEDING"->"droplet";
  case "GONER"->"skull";
  case "ABNORMAL"->"anomaly";
  default->"sparkles";
 };}
 static String statusLabel(Object[] e){String kind=e[3].toString();String title=switch(kind){
  case "RADIATION"->"Радиация";case "BIOLOGICAL"->"Биозаражение";case "THERMAL"->"Температура";case "PSYCHO"->"Пси-воздействие";case "FROST"->"Обморожение";case "GONER"->"Инфекция";case "ABNORMAL"->"Аномальное заражение";
  case "BLEEDING"->"Кровотечение";case "COMBUSTION"->"Горение";case "334302088"->"Безопасная зона";case "1005375721:DEFAULT"->"Многогранник";case "1005375721:SNIPER"->"Многогранник · С";case "1688377162"->"Перегруз";default->e[11].toString();};
  int level=((Number)e[18]).intValue();if(level>0){String[] roman={"","I","II","III","IV","V"};title+=" · "+(level<roman.length?roman[level]:Integer.toString(level));}return title;
 }
 Object category(String key)throws Exception{
  String name=symbolName(key);HudWeaponPreview symbol=symbols.get(name);
  if(symbol==null){symbol=new HudWeaponPreview();symbol.loadVector(Path.of(System.getProperty("stalcraft.arcHud.root","config/arc-hud-native"),"design/effect-icons",name+".svg"));symbols.put(name,symbol);}
  return symbol;
 }
 static int alpha(int color,double opacity){return ((int)Math.round((color>>>24)*clamp(opacity))<<24)|(color&0xffffff);}
 void rect(double x,double y,double w,double h,int color)throws Exception{c(graphics,"extends","void",x,y,w,h,color);}
 void icon(Object icon,double x,double y,double size,double opacity)throws Exception{
  if(icon==null)return;
  if(icon instanceof HudWeaponPreview vector){vector.draw(x,y,size,size,screenWidth,screenHeight,opacity);return;}
  c(Class.forName("1973624787"),"interface","void",alpha(0xffe0e3de,opacity));
  if(Class.forName("485110855").isInstance(icon)){
   Object key=c(icon,"do","779455578"),pos=c(icon,"long","1500420601"),dimensions=c(icon,"strictfp","61438557");
   int sw=((Number)g(dimensions,"try")).intValue(),sh=((Number)g(dimensions,"enum")).intValue();double fit=size/Math.max(sw,sh);
   c(graphics,"super","void",key);float u=((Number)c(graphics,"transient","float")).floatValue(),v=((Number)c(graphics,"enum","float")).floatValue();
   int px=((Number)g(pos,"class")).intValue(),py=((Number)g(pos,"abstract")).intValue(),w=(int)Math.round(sw*fit),h=(int)Math.round(sh*fit);
   c(graphics,"float","void",(int)Math.round(x+(size-w)/2),(int)Math.round(y+(size-h)/2),px*u,py*v,(px+sw)*u,(py+sh)*v,w,h);
  }else if(!icon.toString().toLowerCase(Locale.ROOT).endsWith(".svg")){
   c(graphics,"super","void",icon);
   double tw=1/number(c(graphics,"transient","float")),th=1/number(c(graphics,"enum","float")),fit=size/Math.max(tw,th);
   float w=(float)(tw*fit),h=(float)(th*fit);
   c(graphics,"package","void",(float)(x+(size-w)/2),(float)(y+(size-h)/2),0f,0f,1f,1f,w,h);
  }else{
   String cacheKey="image:"+icon+":"+Math.round(size);Object scaled=sprites.get(cacheKey);
   if(scaled==null){scaled=Class.forName("1476497268").getConstructor(Class.forName("779455578"),float.class).newInstance(icon,(float)size);sprites.put(cacheKey,scaled);}
   c(graphics,"strictfp","void",scaled,(float)x,(float)y);
  }
  c(Class.forName("1973624787"),"interface","void",-1);
 }
 void draw(Object nativeGraphics,Object nativeFont,double x,double bottom,double s,double dt,Object player,int W,int H,double statusRight,double statusBottom)throws Exception{
  graphics=nativeGraphics;font=nativeFont;screenWidth=W;screenHeight=H;
  if(owner!=player){entries.clear();nextSample=0;owner=player;statusAnchorKnown=false;}
  if(Math.abs(scale-s)>.001||badgeRenderer==null){scale=s;badgeRenderer=c(graphics,"while","466989852",(float)(10*s/32));statusAnchorKnown=false;for(var entry:entries.entrySet())if(entry.getKey().startsWith("state:")){entry.getValue()[16]=0d;entry.getValue()[17]=0d;}}
  long now=System.nanoTime();if(now>=nextSample){sample(player);nextSample=now+100_000_000L;}
  Instant clock=(Instant)c(Class.forName("1553688601"),"const","java.time.Instant");
  if(statusAnchorKnown){double dx=statusRight-previousStatusRight,dy=statusBottom-previousStatusBottom;for(var entry:entries.entrySet())if(entry.getKey().startsWith("state:")){Object[] e=entry.getValue();if(number(e[16])!=0||number(e[17])!=0){e[16]=number(e[16])+dx;e[17]=number(e[17])+dy;}}}previousStatusRight=statusRight;previousStatusBottom=statusBottom;statusAnchorKnown=true;
  int statusRows=Math.max(1,(int)Math.floor((statusBottom-38*s)/(25*s))+1);
  double w=Math.max(1,Math.rint(40*s)),h=Math.max(1,Math.rint(44*s)),gap=Math.max(1,Math.rint(4*s)),line=Math.max(1,Math.round(s));int boostIndex=0,stateIndex=0;
  Iterator<Object[]> it=entries.values().iterator();
  while(it.hasNext()){
   Object[] e=it.next();boolean seen=(Boolean)e[9];double opacity=number(e[8]);opacity=HudWeaponSlots.approach(opacity,seen?1:0,dt/(seen?.12:.18));e[8]=opacity;
   boolean boost=e[0].getClass().getName().equals("1105585305"),positive=(Boolean)e[7];
   if(!seen&&opacity==0){if(boost&&now-((Number)e[15]).longValue()<700_000_000L)boostIndex++;else it.remove();continue;}
   int i=boost?boostIndex++:stateIndex++;
   double left=Math.rint(x+(i%7)*(w+gap)),top=Math.rint(bottom-h-(i/7)*(h+gap));
   if(!boost){double targetX=Math.rint(statusRight-(176+(i/statusRows)*182)*s),targetY=Math.rint(statusBottom-22*s-(i%statusRows)*25*s);boolean first=number(e[16])==0&&number(e[17])==0;double step=Math.max(1,dt*240*s);left=first?targetX:Math.rint(HudWeaponSlots.approach(number(e[16]),targetX,step));top=first?targetY:Math.rint(HudWeaponSlots.approach(number(e[17]),targetY,step));}
   e[16]=left;e[17]=top;
   String value=e[4]==null?"":e[4].toString();double progress=-1;
   if(e[5] instanceof Instant expiry){long remaining=Math.max(0,Duration.between(clock,expiry).toMillis());e[14]=remaining;if(boost&&remaining==0)e[9]=false;if(!boost)value=remaining>0?(remaining<60000?String.format(Locale.ROOT,"%.1fс",remaining/1000d):timer(remaining)):"Готов";else if(value.isEmpty())value=remaining==0?"0":timer(remaining);if(number(e[6])>0)progress=clamp(remaining/number(e[6]));}
   e[13]=value;boolean overridden=(Boolean)e[12];double cardOpacity=opacity;if(overridden)opacity*=.45;
   int tone=overridden?0xff747a72:0xffb1aa82;
   if(!boost){
    opacity*=.88;double rowW=Math.rint(176*s),rowH=Math.rint(22*s);int stateColor=((Number)e[21]).intValue();boolean hazard=(Boolean)e[10]||e[3].equals("BLEEDING")||e[3].equals("COMBUSTION");if((Boolean)e[7])stateColor=0xffa6b7a7;
    rect(left,top,rowW,rowH,alpha(0x48191c17,opacity));rect(left,top,Math.max(1,s),rowH,alpha(stateColor,hazard?opacity*.8:opacity*.35));
    Object stateGlyph=symbolName(e[3].toString()).equals("sparkles")?e[1]:category(e[3].toString());icon(stateGlyph,left+5*s,top+4*s,14*s,opacity*.82);
    String label=statusLabel(e);label=(String)c(font,"goto","java.lang.String",label,(int)Math.round((value.isEmpty()?143:97)*s));double labelWidth=number(c(font,"try","double",label));
    c(font,"while","double",label,Math.rint(left+26*s+labelWidth/2),Math.rint(top+10.5*s),alpha(0xffc9cdc3,opacity),true);
    if(!value.isEmpty()){value=(String)c(font,"goto","java.lang.String",value,(int)Math.round(45*s));double valueWidth=number(c(font,"try","double",value));c(font,"while","double",value,Math.rint(left+rowW-7*s-valueWidth/2),Math.rint(top+10.5*s),alpha(hazard?stateColor:0xffc9cdc3,opacity),true);}
    double statusProgress=progress>=0?progress:number(e[20]);if(statusProgress>=0){rect(left+26*s,top+rowH-2*s,rowW-33*s,line,alpha(0x21c9cdc3,opacity));rect(left+26*s,top+rowH-2*s,(rowW-33*s)*statusProgress,line,alpha(stateColor,opacity*.65));}
    continue;
   }
   // Native flat rectangles: the effect texture itself keeps a transparent mask.
   rect(left,top,w,h,alpha(0xa0191c17,cardOpacity));
   int border=alpha(0x68999f92,cardOpacity);
   rect(left,top,w,line,border);rect(left,top+h-line,w,line,border);
   rect(left,top+line,line,h-2*line,border);rect(left+w-line,top+line,line,h-2*line,border);
   Object glyph=e[3].toString().startsWith("item.effects.")?category(e[3].toString()):e[1];if(glyph==null)glyph=category(e[3].toString());
   icon(glyph,left+(w-20*s)/2,top+4*s,20*s,opacity);
   if(e[2]!=null){
    double bx=left+w-12*s,by=top+16*s;
    try(AutoCloseable badgeState=(AutoCloseable)c(Class.forName("1973624787"),"do","1973624787")){c(badgeRenderer,"catch","void",e[2],(float)bx,(float)by,c(e[2],"finally","1596173232"),(float)opacity,false);}
   }
   if(!value.isEmpty()){
    value=(String)c(font,"goto","java.lang.String",value,(int)Math.round(w-6*s));
    c(font,"while","double",value,Math.rint(left+w/2),Math.rint(top+32*s),alpha(0xffdaddd6,opacity),true);
   }
   if(progress>=0){rect(left+4*s,top+h-3*s,w-8*s,line,alpha(0x35daddd6,opacity));rect(left+4*s,top+h-3*s,(w-8*s)*progress,line,alpha(tone,opacity));}
   else rect(left+4*s,top+h-3*s,8*s,line,alpha(tone,opacity));
  }
 }
 void close(){for(HudWeaponPreview symbol:symbols.values())symbol.close();symbols.clear();}
 JsonObject report()throws Exception{
  JsonObject result=new JsonObject();result.addProperty("boosts",activeBoosts);result.addProperty("states",activeStates);JsonArray rows=new JsonArray();
  result.addProperty("statusAnchorRight",previousStatusRight);result.addProperty("statusAnchorBottom",previousStatusBottom);result.addProperty("statusAnchorMode","drawn-weapon-bounds");
  JsonArray statusDetails=new JsonArray();for(var entry:entries.entrySet())if(entry.getKey().startsWith("state:")){Object[] e=entry.getValue();JsonObject row=new JsonObject();row.addProperty("key",entry.getKey());row.addProperty("kind",e[3].toString());row.addProperty("label",statusLabel(e));row.addProperty("level",((Number)e[18]).intValue());row.addProperty("intensity",number(e[19]));row.addProperty("progress",number(e[20]));row.addProperty("color",((Number)e[21]).intValue());row.addProperty("active",(Boolean)e[9]);statusDetails.add(row);}result.add("statusDetails",statusDetails);result.addProperty("statusRowWidth",176*scale);result.addProperty("statusRowHeight",22*scale);result.addProperty("statusLayout","compact-labelled-rows");
  for(var entry:entries.entrySet()){Object[] e=entry.getValue();JsonObject row=new JsonObject();row.addProperty("key",entry.getKey());row.addProperty("category",e[3].toString());row.addProperty("title",e[11].toString());row.addProperty("value",e[4]==null?"":e[4].toString());row.addProperty("sourceItemId",e[2]==null?-1:((Number)c(e[2],"continue","int")).intValue());row.addProperty("hasSourceBadge",e[2]!=null);row.addProperty("infection",(Boolean)e[10]);row.addProperty("opacity",number(e[8]));row.addProperty("visible",number(e[8])>0);row.addProperty("active",(Boolean)e[9]);row.addProperty("x",number(e[16]));row.addProperty("y",number(e[17]));row.addProperty("symbol",symbolName(e[3].toString()));row.addProperty("overridden",(Boolean)e[12]);row.addProperty("renderedValue",e[13].toString());row.addProperty("remainingMs",((Number)e[14]).longValue());rows.add(row);}
  result.add("entries",rows);result.addProperty("separateStatusRail",true);result.addProperty("boostBackgroundAlpha",160d/255);result.addProperty("boostCardsSquare",false);result.addProperty("boostCardWidth",Math.max(1,Math.rint(40*scale)));result.addProperty("boostCardHeight",Math.max(1,Math.rint(44*scale)));result.addProperty("boostCardBorder",true);result.addProperty("boostCardGlyphSize",20*scale);result.addProperty("boostCardBadgeSize",10*scale);JsonArray vectors=new JsonArray();for(var symbol:symbols.entrySet()){JsonObject v=new JsonObject();v.addProperty("name",symbol.getKey());v.addProperty("size",symbol.getValue().w);v.addProperty("transparent",symbol.getValue().transparentVector);v.addProperty("coveragePixels",symbol.getValue().vectorCoverage);vectors.add(v);}result.add("vectors",vectors);return result;
 }
}
