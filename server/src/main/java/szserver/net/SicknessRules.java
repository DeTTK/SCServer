package szserver.net;

import java.util.*;

/** Immutable native sickness catalog and fixed 20 Hz server state. No native work or IO on ticks. */
public final class SicknessRules {
    public record Profile(String kind,String id,int ordinal,String accumulation,String protection,String damageProtection,
                          List<Float> bounds,List<Float> damage,float decrease,float decreaseFactor,float maximum,
                          int damageCooldown,int resetTicks,float incoming,List<Map<String,Float>> properties){
        public Profile{
            bounds=List.copyOf(bounds);damage=List.copyOf(damage);properties=properties.stream().map(Map::copyOf).toList();
            if(kind.isEmpty()||id.isEmpty()||ordinal<0||ordinal>31||bounds.isEmpty()||bounds.size()>32||damage.size()!=bounds.size()+1||properties.size()!=damage.size()||damageCooldown<1||damageCooldown>12000||resetTicks<0||resetTicks>12000)throw new IllegalArgumentException("sickness_profile_shape");
            float last=-1;for(float n:bounds){if(!Float.isFinite(n)||n<0||n<=last)throw new IllegalArgumentException("sickness_bounds");last=n;}
            for(float n:damage)if(!Float.isFinite(n)||n<0)throw new IllegalArgumentException("sickness_damage");
            for(float n:new float[]{decrease,decreaseFactor,maximum,incoming})if(!Float.isFinite(n)||n<0)throw new IllegalArgumentException("sickness_numbers");
            if(maximum<=0||maximum>1_000_000||decreaseFactor>1)throw new IllegalArgumentException("sickness_budget");
            for(var bank:properties){if(bank.size()>128)throw new IllegalArgumentException("sickness_stat_budget");for(var e:bank.entrySet())if(e.getKey().isEmpty()||!Float.isFinite(e.getValue()))throw new IllegalArgumentException("sickness_stat");}
        }
        /** Actual native double() selects the highest bound strictly below power. */
        public int level(float power){if(!Float.isFinite(power)||power<0)throw new IllegalArgumentException("sickness_power");for(int i=bounds.size()-1;i>=0;i--)if(power>bounds.get(i))return i+1;return 0;}
        public float damage(float power,float resistance){return damage.get(level(power))*(1-resistance/100f);}
        /** Native1201171520 clamps resistance after dividing by exposure penetration. */
        public float protectedDose(float dose,float resistance,int penetration){if(!Float.isFinite(dose)||!Float.isFinite(resistance)||penetration<1)throw new IllegalArgumentException("sickness_dose");return dose*Math.max(0,Math.min(1,1-resistance/penetration/100f));}
    }
    public record State(float power,int damageLeft,int resetLeft){
        public State{if(!Float.isFinite(power)||power<0||power>1_000_000||damageLeft<0||damageLeft>12000||resetLeft<0||resetLeft>12000)throw new IllegalArgumentException("sickness_state");}
    }
    public record Step(State after,float damage,boolean levelChanged){}
    private final Map<String,Profile> profiles;
    public SicknessRules(Collection<Profile> values){Map<String,Profile> p=new LinkedHashMap<>();Set<String> ids=new HashSet<>();Set<Integer> ordinals=new HashSet<>();for(var v:values)if(p.put(v.kind(),v)!=null||!ids.add(v.id())||!ordinals.add(v.ordinal()))throw new IllegalArgumentException("sickness_duplicate");if(p.isEmpty()||p.size()>32)throw new IllegalArgumentException("sickness_catalog");profiles=Map.copyOf(p);}
    public Collection<Profile> profiles(){return profiles.values();}
    public Profile get(String kind){Profile p=profiles.get(kind);if(p==null)throw new IllegalArgumentException("sickness_unknown:"+kind);return p;}
    public State empty(String kind){return new State(0,get(kind).damageCooldown(),0);}
    /** Native per-tick units: passive removal and area/medicine accumulation are not per-second rates. */
    public Step step(String kind,State before,Map<String,Float> stats,float zoneDose){
        Profile p=get(kind);if(before.power()>p.maximum()||!Float.isFinite(zoneDose))throw new IllegalArgumentException("sickness_step");
        float input=(zoneDose>0?p.protectedDose(zoneDose,stats.getOrDefault(p.protection(),0f),1):zoneDose)+stats.getOrDefault(p.accumulation(),0f);
        if(!Float.isFinite(input))throw new IllegalArgumentException("sickness_accumulation");
        int reset=input>0?p.resetTicks():Math.max(0,before.resetLeft()-1);
        float power=Math.max(0,Math.min(p.maximum(),before.power()+input-p.decrease()-before.power()*p.decreaseFactor()));
        if(p.resetTicks()>0&&input<=0&&reset==0)power=0;
        int left=before.damageLeft()-1;float damage=0;if(left<=0){left=p.damageCooldown();damage=Math.max(0,p.damage(power,stats.getOrDefault(p.damageProtection(),0f)));}
        return new Step(new State(power,left,reset),damage,p.level(before.power())!=p.level(power));
    }
    public Map<String,Float> stats(Map<String,State> states){Map<String,Float> out=new TreeMap<>();states.forEach((kind,state)->get(kind).properties().get(get(kind).level(state.power())).forEach((k,v)->out.merge(k,v,Float::sum)));return Map.copyOf(out);}
    /** Offline time removes status without dealing unseen damage or simulating old area contacts. */
    public State offline(String kind,State state,long ticks){Profile p=get(kind);if(ticks<0||state.power()>p.maximum())throw new IllegalArgumentException("sickness_offline");if(ticks==0)return state;int reset=(int)Math.max(0,state.resetLeft()-Math.min(12000,ticks));float power;
        if(p.resetTicks()>0&&reset==0)power=0;
        else if(p.decreaseFactor()==0)power=(float)Math.max(0,state.power()-ticks*(double)p.decrease());
        else{double factor=Math.pow(1-p.decreaseFactor(),ticks);power=(float)Math.max(0,state.power()*factor-p.decrease()*(1-factor)/p.decreaseFactor());}
        int damageLeft=(int)(Math.floorMod((long)state.damageLeft()-1-ticks,p.damageCooldown())+1);
        return new State(Math.min(p.maximum(),power),damageLeft,reset);
    }
}
