package fpv;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import static fpv.NativeFlight.*;

/** Uses the real X item chooser, hand animation, raycast and placement preview. */
final class NativeItems {
    static final int DRONE=12447, CONTROLLER=10501;
    private Object profile,renderer;
    int id(Object stack)throws Exception{return (Integer)call(stack,"continue","int");}
    boolean drone(Object definition)throws Exception{return (Integer)call(definition,"static","int")==DRONE;}
    Object placement()throws Exception{return Class.forName("1118020966").getField("transient").get(null);}
    Object address(Object player,Object stack)throws Exception{
        Object inventory=call(Class.forName("276406255"),"implements","276406255",player);
        Object hand=call(Class.forName("1936107049"),"default","1936107049",player);
        for(Object slot:(Set<?>)call(hand,"boolean","java.util.LinkedHashSet")){
            if(call(slot,"boolean","211097523")==stack)return call(slot,"return","949164803",get(inventory,"short"));
        }
        throw new IllegalStateException("Item is no longer in the local inventory");
    }
    byte[] addressBytes(Object address)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        call(address,"static","void",new DataOutputStream(bytes));
        if(bytes.size()!=4)throw new IllegalStateException("Native inventory address layout changed");
        return bytes.toByteArray();
    }
    void begin(Object player,Object stack)throws Exception{
        Class<?> vector=Class.forName("1987590071");
        Object offset=vector.getConstructor(float.class,float.class,float.class).newInstance(0f,.24f,0f);
        Object rotation=vector.getConstructor(float.class,float.class,float.class).newInstance(0f,180f,0f);
        call(placement(),"case","void",address(player,stack),stack,offset,rotation);
    }
    Object profile(Object definition)throws Exception{
        if(!drone(definition))return null;
        if(profile!=null)return profile;
        // A private copy: no modification to the stock stash or its zone policy.
        Class<?> type=Class.forName("1814125595");
        Object template=call(type,"throws","1814125595","extractor");
        if(template==null)throw new IllegalStateException("Native installation placement profile unavailable");
        Object bounds=Class.forName("1994198637").getConstructor(double.class,double.class,double.class,double.class,double.class,double.class)
            .newInstance(-.42,0.,-.48,.42,.46,.48);
        Object boxes=Class.forName("com.google.common.collect.ArrayListMultimap").getMethod("create").invoke(null);
        Class.forName("com.google.common.collect.Multimap").getMethod("put",Object.class,Object.class).invoke(boxes,"body",bounds);
        Object collision=Class.forName("1669874697").getConstructor(Class.forName("com.google.common.collect.Multimap")).newInstance(boxes);
        Object copy=type.getConstructor(String.class,Class.forName("1669874697"),boolean.class)
            .newInstance("sz_fpv_placement",collision,false);
        for(Field field:type.getFields())if(!Modifier.isStatic(field.getModifiers())&&!Modifier.isFinal(field.getModifiers()))field.set(copy,field.get(template));
        type.getField("goto").set(copy,collision);type.getField("throws").set(copy,collision);
        Object pair=call(get(renderer(),"package"),"native","1304833431");
        Object mesh=Class.forName("513648291").getConstructor(Class.forName("1304833431")).newInstance(pair);
        type.getField("a").set(copy,new ArrayList<>(List.of(mesh)));
        type.getField("static").set(copy,Class.forName("1792292307").getConstructor(type).newInstance(copy));
        type.getField("final").set(copy,call(Class.forName("1074092844"),"public","1254743678","FPV-дрон"));
        profile=copy;return copy;
    }
    Object renderer(Object stack)throws Exception{
        if(id(stack)!=DRONE)return null;
        return renderer();
    }
    private Object renderer()throws Exception{
        if(renderer!=null)return renderer;
        Object[] registry=(Object[])Class.forName("1366800141").getField("import").get(null);
        Object donor=get(registry[16450],"boolean");
        if(donor==null)throw new IllegalStateException("Native installation hands unavailable");
        // The promoted FP renderer also needs the donor's native cast animation.
        // Keep its hand rig, placement clips and FP cast resource together.
        renderer=Class.forName("225628581").getConstructor(String.class,String.class,String.class,String.class,boolean.class,Class.forName("1616698092"),Class.forName("1124991383"))
            .newInstance("/assets/","stalkermobs/models/fpv/fpv.mcsb","stalkermobs/models/fpv/fpv.mcmtl",
                get(donor,"private").toString(),true,get(donor,"default"),call(donor,"goto","1124991383"));
        return renderer;
    }
    float[] candidate(Object address,Object stack)throws Exception{
        Object placement=placement();
        if(!Boolean.TRUE.equals(call(placement,"char","boolean"))||!address.equals(get(placement,"try")))return null;
        Object candidate=call(placement,"byte","1934559518");
        if(candidate==null||get(candidate,"transient")!=stack)return null;
        return new float[]{((Number)get(candidate,"abstract")).floatValue(),((Number)get(candidate,"long")).floatValue()+.24f,((Number)get(candidate,"continue")).floatValue()};
    }
    void cancel()throws Exception{call(placement(),"strictfp","boolean");}
}
