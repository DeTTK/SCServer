import gamecompat.UdpPolicyTransformer;
import org.objectweb.asm.*;
import java.net.*;import java.nio.file.*;import java.lang.reflect.*;import java.util.*;

/** Real native selector bytecode, isolated from renderer/device initialisation. */
public class UdpPolicyTest {
 static int checks;static void check(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}
 public static void main(String[] args)throws Exception {
  Path r=Path.of(args[0]);List<URL> urls=new ArrayList<>();for(String p:List.of("classes","bootstrap","libs"))urls.add(r.resolve(p).toUri().toURL());try(var list=Files.list(r.resolve("dependencies"))){for(var p:list.filter(p->p.toString().endsWith(".jar")).toList())urls.add(p.toUri().toURL());}
  try(var loader=new URLClassLoader(urls.toArray(URL[]::new),ClassLoader.getPlatformClassLoader()) {
   @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
    synchronized(getClassLoadingLock(name)){Class<?> loaded=findLoadedClass(name);if(loaded!=null)return loaded;
     if(name.equals("1546247182")){ClassWriter w=new ClassWriter(0);w.visit(61,Opcodes.ACC_PUBLIC,name,null,"java/lang/Object",null);w.visitField(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"ar","L812797379;",null,null).visitEnd();w.visitEnd();byte[] b=w.toByteArray();return defineClass(name,b,0,b.length);}
     if(name.equals("812797379"))try{byte[] b=new UdpPolicyTransformer().transform(this,name,null,null,Files.readAllBytes(r.resolve("classes/812797379.class")));return defineClass(name,b,0,b.length);}catch(Exception e){throw new ClassNotFoundException(name,e);}
     return super.loadClass(name,resolve);
    }
   }
  }) {
   Class<?> type=Class.forName("812797379",true,loader),settings=Class.forName("1546247182",true,loader),label=Class.forName("595713561",true,loader);
   Field singleton=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");singleton.setAccessible(true);sun.misc.Unsafe unsafe=(sun.misc.Unsafe)singleton.get(null);
   Object protocol=unsafe.allocateInstance(type),ordinary=unsafe.allocateInstance(type);for(Object item:List.of(protocol,ordinary)){Field labels=type.getField("throws");labels.setAccessible(true);labels.set(item,java.lang.reflect.Array.newInstance(label,3));type.getField("default").setInt(item,2);}
   Method get=type.getMethod("goto"),set=type.getMethod("float",int.class);check((Integer)get.invoke(ordinary)==2);settings.getField("ar").set(null,protocol);
   check((Integer)get.invoke(protocol)==1);check(type.getField("default").getInt(protocol)==1);
   for(int input:new int[]{0,1,2,-1,99}){set.invoke(protocol,input);check((Integer)get.invoke(protocol)==1);}
   set.invoke(ordinary,2);check((Integer)get.invoke(ordinary)==2);set.invoke(ordinary,-1);check((Integer)get.invoke(ordinary)==0);set.invoke(ordinary,99);check((Integer)get.invoke(ordinary)==2);
   System.out.println("PASS forced UDP native selector checks="+checks);
  }
 }
}
