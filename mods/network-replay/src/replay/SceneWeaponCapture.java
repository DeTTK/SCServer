package replay;

import java.lang.invoke.*;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/** Native weapon attachment transforms in an isolated CPU matrix scope. */
final class SceneWeaponCapture {
    static final String WEAPON="(L211097523;L351062380;)V",FX="(L211097523;L351062380;L461253740;)V";
    static final ClassValue<MethodHandle> prefixes=new ClassValue<>(){
        protected MethodHandle computeValue(Class<?> type){try{
            Class<?> owner=type;while(owner!=null){try{owner.getDeclaredMethod("import",Class.forName("211097523",false,type.getClassLoader()),Class.forName("351062380",false,type.getClassLoader()));break;}catch(NoSuchMethodException absent){owner=owner.getSuperclass();}}
            if(owner==null||!Set.of("685555948","963495584","289750496").contains(owner.getName()))throw new IllegalStateException("Unsupported native weapon transform: "+type.getName());
            String name="ReplayWeaponPrefix"+owner.getName(),descriptor="(L"+owner.getName()+";"+WEAPON.substring(1);
            var writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS){protected String getCommonSuperClass(String a,String b){try{Class<?> x=Class.forName(a.replace('/','.'),false,type.getClassLoader()),y=Class.forName(b.replace('/','.'),false,type.getClassLoader());if(x.isAssignableFrom(y))return a;if(y.isAssignableFrom(x))return b;if(x.isInterface()||y.isInterface())return "java/lang/Object";do{x=x.getSuperclass();}while(!x.isAssignableFrom(y));return x.getName().replace('.','/');}catch(Exception e){throw new IllegalStateException(e);}}};
            writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC|Opcodes.ACC_FINAL,name,null,"java/lang/Object",null);boolean[] found={false},emitter={false};
            new ClassReader(Files.readAllBytes(Path.of(System.getProperty("sz.runtime.classes", "runtime/classes"),owner.getName()+".class"))).accept(new ClassVisitor(Opcodes.ASM9){
                public MethodVisitor visitMethod(int access,String method,String desc,String sig,String[] exceptions){
                    if(!method.equals("import")||!desc.equals(WEAPON))return null;found[0]=true;
                    return new MethodVisitor(Opcodes.ASM9,writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"capture",descriptor,null,exceptions)){
                        public void visitMethodInsn(int op,String target,String called,String calledDesc,boolean iface){
                            super.visitMethodInsn(op,target,called,calledDesc,iface);
                            if(target.equals("495112732")&&called.equals("char")&&calledDesc.equals("(DDD)D")){super.visitInsn(Opcodes.POP2);super.visitInsn(Opcodes.DCONST_0);}
                            if(target.equals("1043044427")&&called.equals("throws")&&calledDesc.equals(FX)){emitter[0]=true;super.visitInsn(Opcodes.RETURN);}
                        }
                    };
                }
            },ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
            if(!found[0]||!emitter[0])throw new IllegalStateException("Native weapon prefix has no emitter");writer.visitEnd();
            // The game and mods have distinct class loaders/unnamed modules.
            // A lookup produced in the canonical owner retains the privileges
            // needed to define its hidden nestmate across that boundary.
            var lookup=(MethodHandles.Lookup)owner.getDeclaredMethod("szreplay$weaponLookup").invoke(null);
            var compiled=lookup.defineHiddenClass(writer.toByteArray(),true,MethodHandles.Lookup.ClassOption.NESTMATE);
            return compiled.findStatic(compiled.lookupClass(),"capture",MethodType.methodType(void.class,owner,Class.forName("211097523",false,type.getClassLoader()),Class.forName("351062380",false,type.getClassLoader()))).asType(MethodType.methodType(void.class,Object.class,Object.class,Object.class));
        }catch(Throwable failure){throw new IllegalStateException("Cannot compile native weapon attachment prefix",failure);}}
    };
    static class MatrixScope implements AutoCloseable {
        final Class<?> matrices;final List<?> stack;final int depth;
        MatrixScope()throws Exception {matrices=Native.type("889335306");stack=(List<?>)Native.field(matrices,"for");depth=stack.size();Native.call(matrices,"if","void");}
        public void close()throws Exception {if(stack.size()<depth+1)throw new IllegalStateException("Native weapon transform escaped its matrix scope");while(stack.size()>depth)Native.call(matrices,"default","void");}
    }
    /** Frame callbacks can inherit a previous renderer's model matrix. */
    static final class ActorScope extends MatrixScope {
        ActorScope()throws Exception {super();Native.call(matrices,"native","void");}
    }
    static boolean capture(Object entity,Object weapon)throws Exception {
        Object configuration=Native.call(weapon,"continue","168071319");if(configuration==null)return false;
        Object manager=Native.field(Native.type("1597325473"),"super"),renderer=Native.call(manager,"native","153743900",entity);
        boolean player=Native.type("200241649").isInstance(entity),npc=Native.type("1422788721").isInstance(entity);
        if(renderer==null||!player&&!npc)return false;
        if(player&&!Native.type("175605189").isInstance(renderer)||npc&&!Native.type("368424395").isInstance(renderer))return false;
        var rig=NativeSceneCapture.characterPose(entity);if(rig==null)return false;
        String boneName=player?"r_arm_wpn":((String[])Native.field(Native.type("1281655698"),"if"))[13130];
        Object bone=Native.call(rig.skeleton(),"class","470347082",boneName);if(bone==null)return false;
        Class<?> matrices=Native.type("889335306");float alpha=FreeCamera.renderAlpha();
        try(var scope=new ActorScope()){
            Native.call(matrices,"instanceof","void",Native.field(Native.field(Native.type("1311607083"),"short"),"transient"));
            Native.call(manager,"transient","void",entity,alpha);
            if(player){
                if((boolean)Native.call(renderer,"abstract","boolean")){float yaw=((Number)Native.call(Native.type("189575079"),"package","float",Native.call(entity,"throws","float"),Native.call(entity,"catch","float"),alpha)).floatValue();Native.call(matrices,"instanceof","void",-yaw,0f,1f,0f);}
            }else{
                if((boolean)Native.call(entity,"goto","boolean"))Native.call(matrices,"continue","void",0f,-.125f,0f);
                float scale=.2f*((Number)Native.field(Native.field(entity,"double"),"continue")).intValue();Native.call(matrices,"else","void",scale,scale,scale);
            }
            Native.call(Native.type("1863799435"),"strictfp","void",rig.pose(),Native.field(bone,"const"));
            if(player)Native.call(renderer,"throw","void",entity,alpha,weapon);else Native.call(renderer,"int","void",weapon);
            try{prefixes.get(configuration.getClass()).invokeExact(configuration,weapon,entity);}catch(Throwable failure){if(failure instanceof Exception e)throw e;throw new IllegalStateException(failure);}
            return true;
        }
    }
}
