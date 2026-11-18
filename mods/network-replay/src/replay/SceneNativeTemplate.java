package replay;

import java.io.*;

/** Explicit native persistent template for locally created player remains. It
 * stores model/identity data, not a running world or a simulation checkpoint. */
final class SceneNativeTemplate {
    static boolean supports(String type){return type.equals("410233126");}
    static SceneTracks.Bytes capture(Object entity)throws Exception {
        if(!supports(entity.getClass().getName()))throw new IOException("Unsupported native scene template");
        Object tag=Native.type("98008865").getConstructor().newInstance();Native.call(entity,"static","void",tag);
        var bytes=new ByteArrayOutputStream();Native.call(Native.type("1464336358"),"char","void",tag,new DataOutputStream(bytes));
        return new SceneTracks.Bytes(bytes.toByteArray());
    }
    static Object create(Object world,SceneTracks.ObjectState selected,int id)throws Exception {
        if(!supports(selected.type())||!(selected.channels().get("native-template") instanceof SceneTracks.Bytes template))throw new IOException("Selected entity lacks its native model template");
        DataInputStream input=new DataInputStream(new ByteArrayInputStream(template.data()));Object tag=Native.call(Native.type("1464336358"),"int","98008865",input);
        if(input.available()!=0)throw new IOException("Trailing native template data");
        Object entity=Native.type(selected.type()).getConstructor(Native.type("1500514786")).newInstance(world);
        Native.call(entity,"final","void",tag);Native.call(entity,"static","void",id);
        double[] p=SceneEnvironmentCodec.numbers(selected.channels().get("position"),3);Native.call(entity,"throw","void",p[0],p[1],p[2]);
        return entity;
    }
}
