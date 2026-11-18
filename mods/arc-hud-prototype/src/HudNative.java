import java.io.*;import java.nio.file.*;import java.lang.invoke.*;import java.lang.reflect.*;import java.util.*;
public final class HudNative {
 static final Map<String,Method> METHODS=new HashMap<>();static final Map<String,Field> FIELDS=new HashMap<>();
 static Object call(Object o,String n,String r,Object...a)throws Exception{Class<?> t=o instanceof Class<?> c?c:o.getClass();String k=t.getName()+"."+n+":"+r+Arrays.toString(Arrays.stream(a).map(x->x==null?null:x.getClass()).toArray());Method m=METHODS.get(k);if(m==null){for(Method candidate:t.getMethods()){if(!candidate.getName().equals(n)||!candidate.getReturnType().getName().equals(r)||candidate.getParameterCount()!=a.length)continue;boolean good=true;for(int i=0;i<a.length;i++)if(a[i]!=null&&!box(candidate.getParameterTypes()[i]).isInstance(a[i]))good=false;if(good){m=candidate;break;}}if(m==null)throw new NoSuchMethodException(k);METHODS.put(k,m);}try{return m.invoke(o instanceof Class?null:o,a);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception x)throw x;throw e;}}
 static Class<?> box(Class<?> c){return c==int.class?Integer.class:c==float.class?Float.class:c==double.class?Double.class:c==boolean.class?Boolean.class:c;}
 static Object get(Object o,String n)throws Exception{Class<?> c=o instanceof Class<?> t?t:o.getClass();String k=c.getName()+"."+n;Field f=FIELDS.get(k);if(f==null){f=c.getField(n);FIELDS.put(k,f);}return f.get(o instanceof Class?null:o);}
 static Object gui(Path root,String name,Object game,int width,int height)throws Throwable{
  Object doc=Class.forName("361648866").getConstructor().newInstance();call(doc,"int","void",new DataInputStream(Files.newInputStream(root.resolve("resources/"+name+"/figma.bin"))));Object document=get(doc,"synchronized");Object canvas=((java.util.List<?>)get(document,"synchronized")).get(0);
  Object key=Class.forName("779455578").getConstructor(String.class,String.class).newInstance("stalker","figma_gui/arc_hud_preview_"+name+"/figma.bin");Class<?> node=Class.forName("1287567536");Object data=Class.forName("1256067609").getConstructor(String.class,node,Map.class).newInstance("figma_gui/arc_hud_preview_"+name,canvas,new LinkedHashMap<>());
  ((Map<Object,Object>)Class.forName("1636983629").getField("extends").get(null)).put(key,data);
  Object scale=Class.forName("108161210").getConstructor().newInstance();Object gui=Class.forName("arhud.PreviewGuiAdaptive").getConstructor(key.getClass(),Class.forName("410896297"),boolean.class).newInstance(key,scale,false);
  Class<?> base=Class.forName("1222473535");Method init=base.getDeclaredMethod("do",Class.forName("1402131154"),int.class,int.class);MethodHandles.privateLookupIn(base,MethodHandles.lookup()).unreflectSpecial(init,base).bindTo(gui).invokeWithArguments(game,width,height);
  for(Object widget:((Map<?,?>)get(gui,"continue")).values())if(widget.getClass().getName().equals("1056724396"))call(gui,"continue","void",widget);return gui;
 }
}
