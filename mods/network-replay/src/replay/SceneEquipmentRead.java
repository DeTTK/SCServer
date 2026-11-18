package replay;

import java.io.*;
import java.util.*;

/** Canonical display snapshots contain a complete attachment map, not ordered edits. */
public final class SceneEquipmentRead {
    interface SlotReader {Object read(DataInput input)throws Exception;}
    record Gun(String skin,Map<Object,Integer> attachments,int ammunition,int counter) {
        Gun {attachments=Collections.unmodifiableMap(new LinkedHashMap<>(attachments));}
    }
    private static final ThreadLocal<Integer> depth=new ThreadLocal<>();
    public static long installed,audited,attachmentValues;
    public static final class Scope implements AutoCloseable {
        private final int previous;private boolean closed;
        Scope(){Integer value=depth.get();previous=value==null?0:value;depth.set(previous+1);}
        public void close(){if(closed)return;closed=true;if(previous==0)depth.remove();else depth.set(previous);}
    }
    public static Scope canonical(){return new Scope();}
    static boolean active(){return depth.get()!=null;}
    static Gun read(DataInput input,SlotReader slots)throws Exception {
        int length=input.readByte();if(length < -1)throw new IOException("Invalid canonical gun skin length");
        String skin=null;if(length>=0){byte[] bytes=new byte[length];input.readFully(bytes);skin=new String(bytes,java.nio.charset.Charset.defaultCharset());}
        int count=input.readByte();if(count<0)throw new IOException("Invalid canonical attachment count");
        Map<Object,Integer> attachments=new LinkedHashMap<>();
        for(int n=0;n<count;n++){
            Object slot=Objects.requireNonNull(slots.read(input),"Missing canonical attachment slot");int item=input.readShort();
            if(item<0)throw new IOException("Invalid canonical attachment item ID");
            if(attachments.put(slot,item)!=null)throw new IOException("Duplicate canonical attachment slot");
        }
        return new Gun(skin,attachments,input.readShort(),input.readShort());
    }
    static void install(Object item,DataInput input)throws Exception {
        Gun snapshot=read(input,in->Native.call(Native.type("1046176253"),"catch","1173269411",in));
        Object[] definitions=(Object[])Native.field(Native.type("1366800141"),"import");Map<Object,Object> attachments=new LinkedHashMap<>();
        for(var entry:snapshot.attachments.entrySet()){
            int id=entry.getValue();if(id>=definitions.length||definitions[id]==null)throw new IOException("Unknown canonical attachment item "+id);
            attachments.put(entry.getKey(),Native.type("211097523").getConstructor(Native.type("1366800141")).newInstance(definitions[id]));
        }
        if(snapshot.skin!=null)Native.call(Native.call(item,"static","java.lang.Object",Native.type("1535556086")),"double","void",snapshot.skin);
        Object container=Native.call(item,"static","java.lang.Object",Native.type("822754569"));
        // Validate the bounded bytes first, then commit all parents and children together.
        Native.call(container,"throw","void",attachments);
        if(!attachments.equals(Native.call(container,"for","java.util.Map")))throw new IOException("Canonical attachment snapshot changed during installation");
        Native.call(item,"enum","void",Native.field(Native.type("241793746"),"enum"),snapshot.ammunition);
        Native.call(item,"enum","void",Native.field(Native.type("241793746"),"strictfp"),snapshot.counter);
        installed++;attachmentValues+=attachments.size();
        if(Boolean.getBoolean("sz.replay.qa.scene-equipment-audit")){
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            Native.call(Native.call(item,"interface","1366800141"),"package","void",item,out);
            var in=new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
            Gun exported=read(in,value->Native.call(Native.type("1046176253"),"catch","1173269411",value));
            if(in.available()!=0||!snapshot.equals(exported))throw new IOException("Canonical gun snapshot did not survive native export");
            audited++;
        }
    }
}
