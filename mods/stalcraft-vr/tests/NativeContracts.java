package vr;
import java.util.*;
/** Resolve active runtime contracts without starting the client. */
public final class NativeContracts {
    private static void contract(String cls,String name,String result,String...args)throws Exception{
        ClassLoader loader=NativeContracts.class.getClassLoader();Class<?> type=Class.forName(cls,false,loader);
        Class<?>[] parameters=new Class<?>[args.length];for(int i=0;i<args.length;i++)parameters[i]=switch(args[i]){case "double"->double.class;case "float"->float.class;case "int"->int.class;case "boolean"->boolean.class;default->Class.forName(args[i],false,loader);};
        if(Arrays.stream(type.getMethods()).noneMatch(m->m.getName().equals(name)&&m.getReturnType().getName().equals(result)&&Arrays.equals(m.getParameterTypes(),parameters)))throw new AssertionError(cls+"."+name);
    }
    public static void main(String[] args)throws Exception{
        contract("276406255","protected","void","int");
        contract("1559281940","boolean","849613593","920235447");
        contract("849613593","else","211097523");
        contract("1478690128","do","254941120","200241649");
        contract("1579323378","catch","1612003613");
        contract("656386660","long","void","303756944","boolean","boolean","1118769848");
        contract("1535940098","szmods$vr$world","void","float");
        contract("1389510037","szmods$flight$enum","void","1102042093");
        contract("353488815","goto","void","1500514786");
        contract("447252483","transient","447252483","[F");
        contract("1102042093","interface","1102042093","[D");
        contract("820497227","class","void","float","float");
        contract("1822220185","szmods$flight$float","void","238490198","int","int","boolean");
        contract("1822220185","szmods$vr$float","void","238490198","int","int","boolean");
        contract("1378012157","byte","void","1745367406","84240326","1810277368");
        contract("1378012157","szmods$vr$mouse","void","160172807");
        contract("1810277368","public","void","int");
        contract("622415611","extends","boolean","int");
        contract("622415611","assert","622415611");
        contract("1402131154","else","void","622415611");
        contract("1376962110","package","void","353488815","353488815");
        contract("1367604516","for","[L1684091846;");
        contract("1973624787","throws","1973624787");
        contract("vr.NativeFpAdapter","szFp_this","168071319");contract("vr.NativeFpAdapter","szFp_this","1770159677");
        contract("211097523","continue","168071319");
        contract("vr.NativeSceneCopy","create","java.util.function.BiConsumer","java.lang.Class");
        contract("495112732","abstract","void");contract("1881987517","while","void","int");
        contract("1183745312","enum","int");
        contract("sz.animation.fp.N174868968","szmods$ensureBuffers","void");contract("sz.animation.fp.N1825402593","szmods$ensureBuffers","void");
        contract("289750496","const","boolean","211097523","62393481","1536296909","1118769848","java.lang.String");
        contract("289750496","const","void","211097523","1536296909","1118769848","243964136","243964136","boolean","boolean","1304833431");
        contract("1745367406","for","211097523","211097523","445983397");
        contract("1745367406","implements","211097523","211097523","java.lang.Object");
        contract("1745367406","public","void","211097523","211097523","java.lang.Object");
        contract("1745367406","break","float","211097523");contract("1745367406","default","float","211097523");
        contract("211097523","enum","211097523");
        contract("1745367406","abstract","1710540327","211097523");contract("1710540327","return","float","211097523");
        contract("447252483","void","[F","[F");contract("1492523895","throw","void");
        contract("495112732","instanceof","void");contract("szmods.runtime.VrRenderHooks","viewReady","void","java.lang.Object");
        contract("sz.animation.fp.N1320579730","szFp_this","void","378545641");
        contract("1536296909","values","[L1536296909;");
        contract("1473643318","while","153304200");
        contract("1810277368","throw","boolean");contract("1810277368","void","void");
        contract("366373100","interface","void","200241649");
        contract("szmods.bridge.VrBolt","switch","void","java.io.DataOutput");
        contract("szmods.bridge.VrWeapon","switch","void","java.io.DataOutput");
        contract("szmods.bridge.VrWeaponReply","extends","void","java.io.DataInput");
        if(szmods.runtime.VrFrameHooks.interval(.033)!=.033)throw new AssertionError("Native frame pacing fallback");
        Object original=new Object();if(szmods.runtime.VrRenderHooks.world(original,0))throw new AssertionError("Disabled world hook");
        szmods.runtime.VrRenderHooks.viewReady(original);
        if(!szmods.runtime.VrRenderHooks.offsetTracer(7,7)||szmods.runtime.VrRenderHooks.offsetTracer(8,7))throw new AssertionError("Disabled VR must preserve the native self-FP tracer offset condition");
        szmods.runtime.VrRenderHooks.fpEffect(original,"barrel",original);
        if(szmods.runtime.VrInputHooks.focus(false)||!szmods.runtime.VrInputHooks.focus(true))throw new AssertionError("Disabled VR input focus fallback");
        if(szmods.runtime.VrInputHooks.blockAutoReload())throw new AssertionError("Inactive VR must preserve native automatic reload");
        System.setProperty("sz.vr.active","true");try{if(!szmods.runtime.VrInputHooks.blockAutoReload())throw new AssertionError("VR physical magazine owns reload initiation");if(szmods.runtime.VrFrameHooks.interval(.033)!=0)throw new AssertionError("XR owns frame pacing");}finally{System.clearProperty("sz.vr.active");}
        java.util.ArrayDeque<String> queue=new java.util.ArrayDeque<>();queue.add("one");queue.add("two");
        if(!szmods.runtime.VrRenderHooks.poll(queue).equals("one")||queue.size()!=1)throw new AssertionError("Disabled render task queue");
        System.out.println("PASS stereo native boundary, eye camera/frustum rebuild, matrix imports and disabled fallback");
    }
}
