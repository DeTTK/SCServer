package szserver.net;
import szserver.client.GameItems.*;
import java.util.*;
public final class CharacterEffectsTests {
    private static Medicine medicine(int id,String group,double duration,double cooldown,boolean active){
        return medicine(id,group,duration,cooldown,active,0);
    }
    private static Medicine medicine(int id,String group,double duration,double cooldown,boolean active,int priority){
        return new Medicine(id,group,priority,false,0,duration,cooldown,0,Map.of("heal",9.5f),new MedicineRules(0,0,false,0,0,0,0,0,false,0,MedicineStages.NONE,active));
    }
    public static void main(String[] args)throws Exception{
        int checks=0;Medicine ungrouped=medicine(100,null,60,60,false),other=medicine(101,null,30,60,false),twin=medicine(105,null,60,60,false);
        Map<Integer,Medicine> catalog=Map.of(100,ungrouped,101,other,105,twin);
        CharacterEffects effects=new CharacterEffects();
        effects=effects.use(ungrouped,catalog,1_000).after();
        if(effects.active(2_000).size()!=1)throw new AssertionError("first effect");checks++;
        effects=effects.use(ungrouped,catalog,61_000).after();
        if(effects.active(62_000).size()!=1||effects.active(62_000).iterator().next().until()!=121_000)throw new AssertionError("ungrouped consumable stacked");checks++;
        effects=effects.use(other,catalog,63_000).after();
        if(effects.active(64_000).size()!=2)throw new AssertionError("different consumables merged");checks++;
        effects=effects.use(twin,catalog,65_000).after();
        if(effects.active(66_000).size()!=2||effects.active(66_000).stream().noneMatch(e->e.item()==105))throw new AssertionError("identical ungrouped effect stacked");checks++;

        Medicine groupedA=medicine(102,"heal",60,0,false),groupedB=medicine(103,"heal",60,0,false);
        Map<Integer,Medicine> groupedCatalog=Map.of(102,groupedA,103,groupedB);
        CharacterEffects groups=new CharacterEffects();groups=groups.use(groupedA,groupedCatalog,1_000).after();
        groups=groups.use(groupedB,groupedCatalog,2_000).after();
        if(groups.active(3_000).size()!=1||groups.active(3_000).iterator().next().item()!=103)throw new AssertionError("group refresh");checks++;

        Medicine medkitA=medicine(26000,"item.effects.effect_type.healing",12,0,false),medkitB=medicine(26002,"item.effects.effect_type.healing",20,0,false);
        Map<Integer,Medicine> realCatalog=Map.of(26000,medkitA,26002,medkitB);
        CharacterEffects realGroups=new CharacterEffects();realGroups=realGroups.use(medkitA,realCatalog,1_000).after();
        realGroups=realGroups.use(medkitB,realCatalog,2_000).after();
        if(realGroups.active(3_000).size()!=1||realGroups.active(3_000).iterator().next().item()!=26002)throw new AssertionError("different medkits must refresh native healing group");checks++;

        Medicine boosterA=medicine(20994,"item.effects.effect_type.booster",1.5,0,false),boosterB=new Medicine(20995,"item.effects.effect_type.booster",0,false,0,30,0,0,Map.of("speed",5f),new MedicineRules(0,0,false,0,0,0,0,0,false,0,MedicineStages.NONE,false));
        Map<Integer,Medicine> boosterCatalog=Map.of(20994,boosterA,20995,boosterB);
        CharacterEffects boosters=new CharacterEffects();boosters=boosters.use(boosterA,boosterCatalog,1_000).after();
        boosters=boosters.use(boosterB,boosterCatalog,2_000).after();
        if(boosters.active(3_000).size()!=1||boosters.active(3_000).iterator().next().item()!=20995)throw new AssertionError("different boosters must refresh native booster group");checks++;

        Medicine morphine=new Medicine(26017,"item.effects.effect_type.short_time_medicine",7,false,0,270,5,0,Map.of("bullet_dmg_factor",21f),new MedicineRules(0,0,true,0,0,0,0,40,false,0,MedicineStages.NONE,false));Map<Integer,Medicine> morphineCatalog=Map.of(26017,morphine);
        CharacterEffects narcotic=new CharacterEffects();narcotic=narcotic.use(morphine,morphineCatalog,1_000).after();
        narcotic=narcotic.use(morphine,morphineCatalog,200_000).after();
        if(narcotic.active(201_000).size()!=1||narcotic.active(201_000).iterator().next().item()!=26017)throw new AssertionError("persistent morphine must refresh instead of stacking");checks++;

        Medicine staged=new Medicine(104,"staged",0,false,0,60,0,0,Map.of("heal",9.5f),new MedicineRules(0,0,false,0,0,0,0,0,false,0,new MedicineStages(0,1_000,0,Map.of("heal",9.5f),null,false),false));Map<Integer,Medicine> stagedCatalog=Map.of(104,staged);
        CharacterEffects stages=new CharacterEffects();stages=stages.use(staged,stagedCatalog,1_000).after();
        stages=stages.use(staged.activeUse(),stagedCatalog,2_000).after();
        if(stages.active(2_500).size()!=1)throw new AssertionError("active stage and base stacked within one category");checks++;
        var transition=stages.transition(stages.active(2_500).stream().filter(e->e.definition(stagedCatalog).rules().active()).findFirst().orElseThrow().id(),staged,stagedCatalog,2_600);stages=transition.after();
        if(stages.active(3_000).size()!=1||stages.active(3_000).iterator().next().definition(stagedCatalog).rules().active())throw new AssertionError("active stage transition");checks++;
        Medicine strong=medicine(110,"heal",10,0,false,3), weak=medicine(111,"heal",60,0,false,-1);
        Medicine independent=medicine(112,"booster",60,0,false,0);
        var priorityCatalog=Map.of(110,strong,111,weak,112,independent);
        var priority=new CharacterEffects().use(strong,priorityCatalog,1_000).after();
        var queued=priority.use(weak,priorityCatalog,2_000);
        priority=queued.after().use(independent,priorityCatalog,3_000).after();
        if(priority.active(4_000).size()!=3||!priority.suppressed(priorityCatalog,4_000).equals(Set.of(queued.added().id())))
            throw new AssertionError("weak medicine must stay visible and suppressed");checks++;
        if(priority.stats(priorityCatalog,4_000).get("heal")!=19f)throw new AssertionError("suppressed stats leaked");checks++;
        priority=CharacterEffects.read(priority.json(),priorityCatalog,4_000);
        if(!priority.suppressed(priorityCatalog,4_000).contains(queued.added().id()))throw new AssertionError("suppression lost on reload");checks++;
        var expired=priority.expire(11_000).after();
        if(!expired.suppressed(priorityCatalog,11_000).isEmpty()
                ||expired.effective(priorityCatalog,11_000).stream().noneMatch(e->e.id()==queued.added().id()&&e.until()==62_000))
            throw new AssertionError("waiting medicine must resume on its original deadline");checks++;
        var reverse=new CharacterEffects().use(weak,priorityCatalog,1_000).after().use(strong,priorityCatalog,2_000).after();
        if(reverse.effective(priorityCatalog,13_000).stream().noneMatch(e->e.item()==111))
            throw new AssertionError("stronger medicine permanently deleted earlier weaker medicine");checks++;
        var shortWeak=medicine(111,"heal",1,0,false,-1);
        var alreadyExpired=new CharacterEffects().use(strong,priorityCatalog,1_000).after().use(shortWeak,priorityCatalog,2_000).after();
        if(!alreadyExpired.effective(priorityCatalog,11_000).isEmpty())throw new AssertionError("expired queued medicine reappeared");checks++;
        var oldSave=new CharacterEffects().use(ungrouped,catalog,1_000).after().json();
        var parsed=CharacterEffects.read(oldSave,catalog,2_000).use(twin,catalog,2_000).after();
        if(parsed.active(3_000).size()!=1)throw new AssertionError("same effect stacked after save");checks++;
        var oldDefinition=oldSave.getAsJsonArray("effects").get(0).getAsJsonObject().getAsJsonObject("definition");
        oldDefinition.remove("duration");oldDefinition.remove("stageStats");
        parsed=CharacterEffects.read(oldSave,catalog,2_000).use(twin,catalog,2_000).after();
        if(parsed.active(3_000).size()!=1)throw new AssertionError("same effect stacked after legacy save");checks++;
        System.out.println("PASS "+checks+" ungrouped consumable refresh and grouped effect replacement checks");
    }
}
