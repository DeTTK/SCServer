package szserver.inventory;

import com.google.gson.Gson;
import szserver.world.ActorEquipment;
import java.util.Map;

public final class StatValuesTests {
    static int checks;
    static void check(boolean value){if(!value)throw new AssertionError();checks++;}
    static void rejects(Runnable action){try{action.run();throw new AssertionError("invalid stat accepted");}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[] args){
        for(String key:new String[]{"bullet_dmg_factor","burn_dmg_factor","chemical_burn_dmg_factor","electra_dmg_factor","explosion_dmg_factor","tear_dmg_factor","freeze_dmg_factor"}){
            float value=StatValues.fromNative(key,Float.POSITIVE_INFINITY);check(value==Float.MAX_VALUE);
            check(StatValues.add(key,value,value)==value);check(StatValues.add(key,value,500)==value);
            var bank=Map.of(key,value);check(ActorEquipment.stats(bank,false,null).stats().equals(bank));
            check(!new Gson().toJson(bank).contains("Infinity"));
            float damage=(float)(50*100d/(100d+value));check(100f-damage==100f);
            rejects(()->StatValues.fromNative(key,Float.NEGATIVE_INFINITY));rejects(()->StatValues.fromNative(key,Float.NaN));
        }
        check(StatValues.fromNative("speed_modifier",40)==40);
        rejects(()->StatValues.fromNative("speed_modifier",Float.POSITIVE_INFINITY));
        rejects(()->ActorEquipment.stats(Map.of("speed_modifier",Float.MAX_VALUE),false,null));
        rejects(()->StatValues.add("speed_modifier",Float.MAX_VALUE,Float.MAX_VALUE));
        check(ActorEquipment.stats(Map.of("speed_modifier",40f),false,null).movement()!=null);
        System.out.println("Native protection stats: "+checks+" PASS");
    }
}
