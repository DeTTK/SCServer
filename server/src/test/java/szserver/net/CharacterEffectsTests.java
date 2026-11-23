package szserver.net;
import szserver.client.GameItems.*;
import java.util.*;
public final class CharacterEffectsTests {
    private static Medicine medicine(int id,String group,double duration,double cooldown,boolean active){
        return new Medicine(id,group,0,false,0,duration,cooldown,0,Map.of("heal",9.5f),new MedicineRules(0,0,false,0,0,0,0,0,false,0,MedicineStages.NONE,active));
    }
    public static void main(String[] args)throws Exception{
        int checks=0;Medicine ungrouped=medicine(100,null,60,60,false),other=medicine(101,null,60,60,false);
        Map<Integer,Medicine> catalog=Map.of(100,ungrouped,101,other);
        CharacterEffects effects=new CharacterEffects();
        effects=effects.use(ungrouped,catalog,1_000).after();
        if(effects.active(2_000).size()!=1)throw new AssertionError("first effect");checks++;
        effects=effects.use(ungrouped,catalog,61_000).after();
        if(effects.active(62_000).size()!=1||effects.active(62_000).iterator().next().until()!=121_000)throw new AssertionError("ungrouped consumable stacked");checks++;
        effects=effects.use(other,catalog,63_000).after();
        if(effects.active(64_000).size()!=2)throw new AssertionError("different consumables merged");checks++;

        Medicine groupedA=medicine(102,"heal",60,0,false),groupedB=medicine(103,"heal",60,0,false);
        Map<Integer,Medicine> groupedCatalog=Map.of(102,groupedA,103,groupedB);
        CharacterEffects groups=new CharacterEffects();groups=groups.use(groupedA,groupedCatalog,1_000).after();
        groups=groups.use(groupedB,groupedCatalog,2_000).after();
        if(groups.active(3_000).size()!=1||groups.active(3_000).iterator().next().item()!=103)throw new AssertionError("group refresh");checks++;

        Medicine staged=new Medicine(104,"staged",0,false,0,60,0,0,Map.of("heal",9.5f),new MedicineRules(0,0,false,0,0,0,0,0,false,0,new MedicineStages(0,1_000,0,Map.of("heal",9.5f),null,false),false));Map<Integer,Medicine> stagedCatalog=Map.of(104,staged);
        CharacterEffects stages=new CharacterEffects();stages=stages.use(staged,stagedCatalog,1_000).after();
        stages=stages.use(staged.activeUse(),stagedCatalog,2_000).after();
        if(stages.active(2_500).size()!=2)throw new AssertionError("active stage must not merge with base");checks++;
        var transition=stages.transition(stages.active(2_500).stream().filter(e->e.definition(stagedCatalog).rules().active()).findFirst().orElseThrow().id(),staged,stagedCatalog,2_600);stages=transition.after();
        if(stages.active(3_000).size()!=1||stages.active(3_000).iterator().next().definition(stagedCatalog).rules().active())throw new AssertionError("active stage transition");checks++;
        System.out.println("PASS "+checks+" ungrouped consumable refresh and grouped effect replacement checks");
    }
}
