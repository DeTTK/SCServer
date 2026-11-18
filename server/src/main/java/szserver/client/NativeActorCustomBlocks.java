package szserver.client;

import com.google.gson.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Reviewed native custom collision prototypes, with immutable captured material flags.
 * Render/model construction is excluded; the actual collision/face implementations remain native.
 * The original mine callback may query a tile only after its native admission checks. */
final class NativeActorCustomBlocks {
    static void prepare(Path runtime,Path file,ClassLoader loader,Object unsafe,Method allocate,Object[] blocks)throws Exception{
        if(Files.size(file)>2*1024*1024)throw new IllegalArgumentException("actor_custom_profiles_size");var root=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        for(var entry:root.getAsJsonObject("nativeClasses").entrySet()){
            if(!entry.getKey().matches("[0-9]+"))throw new IllegalArgumentException("actor_custom_profile_class");
            String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(runtime.resolve("classes").resolve(entry.getKey()+".class"))));
            if(!actual.equals(entry.getValue().getAsString()))throw new IllegalStateException("actor_custom_release:"+entry.getKey());
        }
        Class<?> base=Class.forName("1959919792",true,loader);Field material=base.getField("enum"),identity=base.getField("return");material.setAccessible(true);identity.setAccessible(true);
        Method bounds=base.getMethod("void",float.class,float.class,float.class,float.class,float.class,float.class);Set<Integer> seen=new HashSet<>();
        var capture=root.getAsJsonObject("capture");if(!capture.get("captureOnly").getAsBoolean()||capture.get("clientFieldsWritten").getAsBoolean()||capture.getAsJsonArray("blocks").size()!=41)throw new IllegalArgumentException("actor_custom_capture_scope");
        for(var value:capture.getAsJsonArray("blocks")){
            var row=value.getAsJsonObject();int id=row.get("id").getAsInt();String name=row.get("class").getAsString();
            if(id<=0||id>=blocks.length||!seen.add(id)||!root.getAsJsonObject("nativeClasses").has(name))throw new IllegalArgumentException("actor_custom_profile_identity");
            Class<?> type=Class.forName(name,true,loader);if(!base.isAssignableFrom(type))throw new IllegalArgumentException("actor_custom_profile_type");
            if(blocks[id]!=null&&(!type.isInstance(blocks[id])||id!=4018&&id!=4025))throw new IllegalStateException("actor_custom_profile_conflict:"+id);
            Object block=blocks[id];
            if(block==null&&NativeActorFluids.water(id,name)){
                // Fluid constructors initialize level scale and density direction. Unsafe
                // defaults happen to pass the body scan but corrupt the head/surface query.
                Class<?> fluid=Class.forName("685910552",true,loader),materialType=Class.forName("393979275",true,loader);
                Object definition=fluid.getConstructor(String.class).newInstance("actor-water-"+id);fluid.getMethod("const",int.class).invoke(definition,id);
                block=name.equals("1621282299")?type.getConstructor(int.class,fluid,Class.forName("1088612043",true,loader)).newInstance(id,definition,null):type.getConstructor(Class.forName("683052146",true,loader),int.class,fluid,materialType).newInstance(null,id,definition,materialType.getField("class").get(null));
                base.getMethod("int",int.class).invoke(block,3);
            }
            if(block==null)block=allocate.invoke(unsafe,type);
            String materialClass=row.get("materialClass").getAsString();if(!root.getAsJsonObject("nativeClasses").has(materialClass))throw new IllegalArgumentException("actor_custom_material_release");
            // Native fluid contact compares the material singleton by identity. A fresh
            // material with identical flags silently turns resource waters into dry air.
            boolean water=NativeActorFluids.water(id,name);
            Object nativeMaterial=water?Class.forName("393979275",true,loader).getField("class").get(null):Class.forName(materialClass,true,loader).getConstructor().newInstance();
            if(!nativeMaterial.getClass().getName().equals(materialClass))throw new IllegalArgumentException("actor_custom_material_class");
            for(var flag:row.getAsJsonArray("materialFlags")){var f=flag.getAsJsonObject();Class<?> owner=Class.forName(f.get("class").getAsString(),true,loader);if(!owner.isInstance(nativeMaterial))throw new IllegalArgumentException("actor_custom_material_owner");Field field=owner.getDeclaredField(f.get("field").getAsString());field.setAccessible(true);if(field.getType()!=boolean.class||Modifier.isStatic(field.getModifiers()))throw new IllegalArgumentException("actor_custom_material_flag");boolean expected=f.get("value").getAsBoolean();if(water){if(field.getBoolean(nativeMaterial)!=expected)throw new IllegalStateException("actor_water_material_flags");}else field.setBoolean(nativeMaterial,expected);}
            material.set(block,nativeMaterial);identity.setInt(block,id);bounds.invoke(block,0f,0f,0f,1f,1f,1f);((boolean[])base.getField("j").get(null))[id]=false;
            blocks[id]=block;
        }
    }
    private NativeActorCustomBlocks(){}
}
