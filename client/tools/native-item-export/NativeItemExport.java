package nativeexport;

import szmods.api.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Optional developer export. Do not install in ordinary client bundles. Runs once after game startup. */
public final class NativeItemExport implements ClientMod {
    private Path output;private boolean done;
    public void onLoad(ModContext context){output=context.configDirectory().resolve("native-items.json");}
    private static Method method(Class<?> c,String name,Class<?> result,boolean isStatic,Class<?>...args)throws Exception{return Arrays.stream(c.getMethods()).filter(m->m.getName().equals(name)&&m.getReturnType()==result&&Modifier.isStatic(m.getModifiers())==isStatic&&Arrays.equals(m.getParameterTypes(),args)).findFirst().orElseThrow(()->new NoSuchMethodException(c.getName()+"."+name));}
    public void onFrame(ClientFrame frame)throws Exception{
        if(done)return;ClassLoader loader=frame.game().getClass().getClassLoader();Class<?> base=Class.forName("1366800141",true,loader),stack=Class.forName("211097523",true,loader),geometry=Class.forName("1824362651",true,loader),sizeType=Class.forName("61438557",true,loader);Object[] table=(Object[])base.getField("import").get(null);if(Arrays.stream(table).filter(Objects::nonNull).count()<100)return;done=true;
        Method limit=method(stack,"finally",int.class,false),size=method(geometry,"while",sizeType,true,stack);StringJoiner rows=new StringJoiner(","),errors=new StringJoiner(",");for(int id=1;id<table.length;id++)if(table[id]!=null)try{Object item=stack.getConstructor(int.class,int.class,int.class).newInstance(id,1,0),shape=size.invoke(null,item);rows.add("\""+id+"\":{\"limit\":"+limit.invoke(item)+",\"width\":"+sizeType.getField("try").getInt(shape)+",\"height\":"+sizeType.getField("enum").getInt(shape)+"}");}catch(Exception e){errors.add("\""+id+"\":\"native_export_failed\"");}
        Files.createDirectories(output.getParent());Files.writeString(output,"{\"rows\":{"+rows+"},\"errors\":{"+errors+"}}");
    }
}
