package szserver.inventory;

import java.util.Set;

/** Finite transport representation of the native admin armors' unlimited protection. */
public final class StatValues {
    private StatValues(){}
    private static final Set<String> PROTECTION=Set.of("bullet_dmg_factor","burn_dmg_factor","chemical_burn_dmg_factor","electra_dmg_factor","explosion_dmg_factor","tear_dmg_factor","freeze_dmg_factor");
    public static float fromNative(String key,float value){
        // JSON, persisted actor banks and owner validation require finite numbers.
        // Keep immunity at float precision without changing the authored resources.
        if(value==Float.POSITIVE_INFINITY&&PROTECTION.contains(key))return Float.MAX_VALUE;
        if(!Float.isFinite(value))throw new IllegalArgumentException("native_stat_nonfinite:"+key);
        return value;
    }
    public static boolean unlimitedProtection(String key,float value){return value==Float.MAX_VALUE&&PROTECTION.contains(key);}
    public static float add(String key,float first,float second){
        double result=(double)first+second;
        if(PROTECTION.contains(key)&&result>=Float.MAX_VALUE)return Float.MAX_VALUE;
        float value=(float)result;
        if(!Float.isFinite(value))throw new IllegalArgumentException("stat_sum_nonfinite:"+key);
        return value;
    }
}
