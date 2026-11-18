package completion;
import java.lang.reflect.*;
final class Native {
 static Object field(Object o,String name)throws Exception{
  Class<?> type=o instanceof Class<?> c?c:o.getClass();
  for(Class<?> c=type;c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(o instanceof Class<?>?null:o);}catch(NoSuchFieldException ignored){}
  throw new NoSuchFieldException(name);
 }
 static Object call(Object o,String name,String returns,Object...args)throws Exception{
  Class<?> c=o instanceof Class<?> t?t:o.getClass();
  outer:for(Method m:c.getMethods()){
   if(!m.getName().equals(name)||!m.getReturnType().getName().equals(returns)||m.getParameterCount()!=args.length)continue;
   for(int i=0;i<args.length;i++)if(args[i]!=null&&!m.getParameterTypes()[i].isPrimitive()&&!m.getParameterTypes()[i].isInstance(args[i]))continue outer;
   return m.invoke(o instanceof Class<?>?null:o,args);
  }throw new NoSuchMethodException(c.getName()+"."+name+":"+returns);
 }
}
