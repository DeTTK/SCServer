package szserver.client;

import com.google.gson.*;
import java.lang.reflect.*;
import java.util.*;

/** Cosmetics share the native gun attachment map but are not functional modules. */
public final class ModuleCosmeticCompatibility {
    public static Object childFactory(Object adapter,int id)throws Exception{
        Field definitions=adapter.getClass().getDeclaredField("moduleDefinitions");definitions.setAccessible(true);
        String raw=((Map<Integer,String>)definitions.get(adapter)).get(id);
        if(raw!=null){JsonObject definition=JsonParser.parseString(raw).getAsJsonObject();String type=definition.has("type")?definition.get("type").getAsString():"";
            if(type.equals("weapon_style")||type.equals("weapon_motif"))return null;
        }
        Method method=adapter.getClass().getDeclaredMethod("moduleFactory",int.class);method.setAccessible(true);
        try{return method.invoke(adapter,id);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception cause)throw cause;throw e;}
    }
    private ModuleCosmeticCompatibility(){}
}
