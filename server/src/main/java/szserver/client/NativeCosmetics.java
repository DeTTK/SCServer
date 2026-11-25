package szserver.client;

import com.google.gson.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Original cosmetic compatibility and slot operations on the shared item-factory host. */
final class NativeCosmetics extends NativeItemFactories {
    private final Class<?> target,kind;
    NativeCosmetics(ClassLoader loader,Map<Integer,String> definitions,Map<Integer,String> classes,
                    Map<Integer,String> resourceDeclarations,NativeFactoryRegistrations registrations)throws Exception{
        super(loader,definitions,classes,resourceDeclarations,registrations);target=type("714221946");kind=type("1847365303");
        for(int id:new TreeSet<>(classes.keySet())){Class<?> c=type(classes.get(id));if(skin.isAssignableFrom(c)||style.isAssignableFrom(c))factory(id);}
    }
    record Skin(Object state,int previous,boolean unchanged){}
    Skin skin(int item,Object state,int selected)throws Exception{
        Class<?> visual=type("865153858");if(!visual.isInstance(state))throw new IOException("item_has_no_visual_name");if((Boolean)visual.getMethod("abstract").invoke(state))throw new IOException("visual_name_locked");
        Object definition=registry[item],replacement=selected==0?null:factory(selected);if(definition==null)throw new IOException("skin_target_factory_missing");if(replacement!=null&&(!skin.isInstance(replacement)||!(Boolean)skin.getMethod("long",base).invoke(replacement,definition)))throw new IOException("skin_incompatible");
        String previous=(String)visual.getMethod("transient").invoke(state),name=replacement==null?null:(String)skin.getField("new").get(replacement);boolean unchanged=Objects.equals(previous,name);
        Object old=previous==null?null:skin.getMethod("transient",String.class,base).invoke(null,previous,definition);int oldId=old==null?0:(Integer)base.getMethod("static").invoke(old);
        visual.getMethod("double",String.class).invoke(state,name);return new Skin(state,oldId,unchanged);
    }
    record Paint(Object state,byte[] previous,boolean builtin){}
    Paint paint(int item,Object state,String stateClass,int slot,byte[] raw,byte[] attached,boolean virtual)throws Exception{
        if(slot<0||slot>1)throw new IOException("paint_slot");Object definition=factory(item);if(!target.isInstance(definition))throw new IOException("paint_target_factory");Object selected=attached==null?null:factory(new DataInputStream(new ByteArrayInputStream(attached)).readUnsignedShort());Object key=kind.getField(slot==0?"VISUAL_STYLE":"MOTIF").get(null);
        if(selected!=null&&(!style.isInstance(selected)||!Arrays.stream(style.getMethods()).filter(m->m.getName().equals("implements")&&m.getParameterCount()==0&&m.getReturnType()==kind).findFirst().orElseThrow().invoke(selected).equals(key)||!(Boolean)style.getMethod("break",target).invoke(selected,definition)))throw new IOException("paint_target_incompatible");
        Class<?> flags=type("1156376641");if(!flags.isInstance(state))throw new IOException("paint_flags_missing");boolean wasVirtual=(Boolean)flags.getMethod(slot==0?"throw":"finally").invoke(state);
        return bound(()->{Object itemStack=stack.getConstructor(DataInput.class).newInstance(new BoundedNativeInput(raw)),replacement=attached==null?null:stack.getConstructor(DataInput.class).newInstance(new BoundedNativeInput(attached));Object previous=target.getMethod("implements",stack,kind).invoke(definition,itemStack,key);
            boolean builtin=wasVirtual||previous!=null&&(Boolean)style.getMethod("if",stack).invoke(null,previous);byte[] old=null;if(previous!=null){var out=new ByteArrayOutputStream();stack.getMethod("switch",DataOutput.class).invoke(previous,new DataOutputStream(out));old=out.toByteArray();}
            target.getMethod("throws",stack,kind,stack).invoke(definition,itemStack,key,replacement);Object changed=stack.getMethod("for",Class.class).invoke(itemStack,type(stateClass));flags.getMethod(slot==0?"assert":"private",boolean.class).invoke(changed,virtual&&replacement!=null);return new Paint(changed,old,builtin);
        });
    }
}
