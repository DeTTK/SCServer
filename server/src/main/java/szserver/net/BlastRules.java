package szserver.net;

/** Complete native damage-channel contract, prepared before any gameplay owner starts. */
public record BlastRules(String kind,String primary,String secondary,float secondaryAmount,float playerReduction,boolean lowestPoint){
    private static final java.util.Set<String> TYPES=java.util.Set.of("DEFAULT","ELECTROSHOCK","BURN","CHEMICAL_BURN","TEAR","EXPLOSION","BULLET","CONTAMINATION","STRIKE","FREEZE","EJECTION");
    public BlastRules{if(!java.util.Set.of("FRAG","FLASH").contains(kind)||!TYPES.contains(primary)||!TYPES.contains(secondary)||!Float.isFinite(secondaryAmount)||secondaryAmount<0||secondaryAmount>100000||!Float.isFinite(playerReduction)||playerReduction<0||playerReduction>100000)throw new IllegalArgumentException("blast_damage_contract");}
    /** Native ordinary damage source factor; subtype bonuses do not apply to a hand grenade. */
    public static float factor(String type,java.util.Map<String,Float> stats){String key=switch(type){case "ELECTROSHOCK"->"electra_dmg_factor";case "BURN"->"burn_dmg_factor";case "CHEMICAL_BURN"->"chemical_burn_dmg_factor";case "TEAR"->"tear_dmg_factor";case "EXPLOSION"->"explosion_dmg_factor";case "BULLET"->"bullet_dmg_factor";case "FREEZE"->"freeze_dmg_factor";default->null;};return key==null?1:100f/(100+Math.max(-99,stats.getOrDefault(key,0f)));}
}
