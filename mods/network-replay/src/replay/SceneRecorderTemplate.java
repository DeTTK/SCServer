package replay;

import java.io.*;
import java.util.*;

/** Portable creation data for an independently owned recorder character. */
final class SceneRecorderTemplate {
    // Canonical constructor order differs from the field declaration order.
    static final List<String> RESOURCES=List.of("final","interface","super","protected","default");
    record Identity(String name,Object bodyView){}
    static SceneTracks.Bytes capture(Object entity)throws Exception {
        String method=Native.type("961798934").isInstance(entity)?"case":"const";
        return encode((String)Native.call(entity,"return","java.lang.String"),Native.call(entity,method,"658914576"));
    }
    static SceneTracks.Bytes encode(String name,Object bodyView)throws Exception {
        if(name==null||name.length()>4096)throw new IOException("Invalid recorder name");
        var bytes=new ByteArrayOutputStream();var output=new DataOutputStream(bytes);output.writeInt(1);output.writeUTF(name);output.writeBoolean(bodyView!=null);
        if(bodyView!=null){
            if(!Native.type("658914576").isInstance(bodyView))throw new IOException("Invalid recorder body view");
            output.writeUTF((String)Native.field(bodyView,"float"));
            for(String field:RESOURCES){Object resource=Native.field(bodyView,field);output.writeBoolean(resource!=null);if(resource!=null)output.writeUTF(resource.toString());}
        }
        return new SceneTracks.Bytes(bytes.toByteArray());
    }
    static Identity decode(SceneTracks.Bytes data)throws Exception {
        try(var input=new DataInputStream(new ByteArrayInputStream(data.data()))){
            if(input.readInt()!=1)throw new IOException("Unsupported recorder template");String name=input.readUTF();if(name.length()>4096)throw new IOException("Invalid recorder name");Object view=null;
            if(input.readBoolean()){
                String label=input.readUTF();Class<?> resource=Native.type("779455578");Object[] args=new Object[6];args[0]=label;
                for(int n=1;n<args.length;n++)args[n]=input.readBoolean()?resource.getConstructor(String.class).newInstance(input.readUTF()):null;
                view=Native.type("658914576").getConstructor(String.class,resource,resource,resource,resource,resource).newInstance(args);
            }
            if(input.available()!=0)throw new IOException("Trailing recorder template bytes");return new Identity(name,view);
        }
    }
    static Object create(Object game,Object world,SceneTracks.ObjectState state,int id)throws Exception {
        if(!(state.channels().get("recorder-template") instanceof SceneTracks.Bytes bytes))throw new IOException("Missing recorder identity");
        Identity identity=decode(bytes);Object entity=Native.type("961798934").getConstructor(Native.type("1402131154"),Native.type("1500514786"),String.class).newInstance(game,world,identity.name());
        Native.call(entity,"static","void",id);double[] p=SceneEnvironmentCodec.numbers(state.channels().get("position"),3);Native.call(entity,"throw","void",p[0],p[1],p[2]);
        double[] look=SceneEnvironmentCodec.numbers(state.channels().get("look"),2);Native.call(entity,"class","void",(float)look[0],(float)look[1]);
        ReplayRender.sceneBodyViews.put(entity,identity.bodyView());return entity;
    }
}
