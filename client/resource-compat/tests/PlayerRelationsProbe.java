import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/** Verify the packaged party predicate and that the agent preserves the native alliance table. */
public final class PlayerRelationsProbe {
    private static final List<String> FACTIONS=List.of("stalkers","bandits","duty","freedom","merc","covenant");

    public static void main(String[] args)throws Exception{
        Method sameParty=Class.forName("1900336751").getMethod("new",Long.class,Long.class);
        Class<?> faction=Class.forName("510786740");
        Class<?> text=Class.forName("595713561");
        Method lookup=faction.getMethod("return",String.class);
        Class<?> relation=Class.forName("495513349");
        Object friendly=relation.getField("FRIENDLY").get(null);
        Object hostile=relation.getField("HOSTILE").get(null);
        for(String first:FACTIONS){
            Object definition=faction.getConstructor(int.class,String.class,text,text).newInstance(0,first,null,null);
            @SuppressWarnings("unchecked")
            Map<String,Object> overrides=(Map<String,Object>)faction.getField("for").get(definition);
            for(String second:FACTIONS)
                if(!first.equals(second)){
                    check(lookup.invoke(definition,second)==hostile,"default hostility",first,second);
                    overrides.put(second,friendly);
                    check(lookup.invoke(definition,second)==friendly,"alliance from native table",first,second);
                }
            check(lookup.invoke(definition,first)==friendly,"same faction",first,first);
        }
        check((boolean)sameParty.invoke(null,8L,8L),"shared squad","8","8");
        check(!(boolean)sameParty.invoke(null,-1L,-1L),"no squad","-1","-1");
        check(!(boolean)sameParty.invoke(null,8L,9L),"different squads","8","9");
        System.out.println("PASS packaged native faction relations and squad predicate");
    }

    private static void check(boolean result,String test,String first,String second){
        if(!result)throw new AssertionError(test+" ("+first+", "+second+")");
    }
}
