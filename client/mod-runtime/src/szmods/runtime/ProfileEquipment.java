package szmods.runtime;

import java.io.DataInput;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Equipment comes from the game server and is rendered inside the stock profile. */
public record ProfileEquipment(String player,List<String> titles) {
    private record View(Object connection,ProfileEquipment equipment) {}
    private static volatile View view;

    public static ProfileEquipment read(DataInput input)throws IOException {
        String player=input.readUTF();
        int count=input.readUnsignedByte();
        if (player.length()>96 || count>6) throw new IOException("Invalid profile equipment");
        var titles=new ArrayList<String>(count);
        for(int slot=0;slot<count;slot++){
            String key=input.readUTF();
            if(key.isEmpty() || key.length()>256) throw new IOException("Invalid equipment name");
            titles.add(key);
        }
        return new ProfileEquipment(player,List.copyOf(titles));
    }

    public void play(Object connection) {view=new View(connection,this);}

    static void connected(Object connection) {
        var current=view;
        if(current!=null && current.connection()!=connection) view=null;
    }

    public static String describe(String status,Object profile) {
        var current=view;
        if(current==null || current.equipment().titles().isEmpty()) return status;
        try{
            String player=(String)Native.NAME.invoke(profile);
            if(!current.equipment().player().equals(player))return status;
            var names=new ArrayList<String>(current.equipment().titles().size());
            for(String key:current.equipment().titles()) names.add((String)Native.LOCALIZE.invoke(null,key));
            return status+"\n\nЭкипировка\n"+String.join(", ",names);
        }catch(ReflectiveOperationException | LinkageError failure){
            return status;
        }
    }

    private static final class Native {
        static final Method NAME,LOCALIZE;
        static{
            try{
                NAME=FunnelImpact.nativeMethod(Class.forName("789817320"),"break",String.class);
                LOCALIZE=FunnelImpact.nativeMethod(Class.forName("255753289"),"else",String.class,String.class);
            }catch(ReflectiveOperationException failure){throw new ExceptionInInitializerError(failure);}
        }
    }
}
