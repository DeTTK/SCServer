package szserver.net;

import szserver.client.GameItems.Medicine;import szserver.inventory.GrenadeAreaStore;import java.util.*;

/** Ephemeral client effect bank. IDs are disjoint from durable medicine IDs (>=1,000,000). */
public final class AreaEffects {
    public record Change(List<CharacterEffects.Effect> removed,List<CharacterEffects.Effect> added){public boolean changed(){return !removed.isEmpty()||!added.isEmpty();}}
    private record Active(GrenadeAreaStore.Activation activation,szserver.inventory.GrenadeAreaProfile profile,CharacterEffects.Effect effect){}
    private record Key(UUID source,int profile){}
    private final Map<Key,Active> active=new LinkedHashMap<>();private int next=500_000;
    public Change update(List<AreaExposure.Contact> contacts,long now){
        if(contacts.size()>128)throw new IllegalArgumentException("area_effect_limit");Map<Key,AreaExposure.Contact> wanted=new LinkedHashMap<>();for(var c:contacts)if(!c.profile().stats().isEmpty()&&now<c.until()){if(wanted.put(new Key(c.activation().projectile(),c.index()),c)!=null)throw new IllegalArgumentException("area_effect_duplicate");}
        List<CharacterEffects.Effect> removed=new ArrayList<>(),added=new ArrayList<>();for(var e:new ArrayList<>(active.entrySet())){var c=wanted.get(e.getKey());if(c==null||!c.activation().equals(e.getValue().activation())||!c.profile().equals(e.getValue().profile())){removed.add(e.getValue().effect());active.remove(e.getKey());}}
        for(var e:wanted.entrySet())if(!active.containsKey(e.getKey())){var c=e.getValue();int id=allocate();var p=c.profile();var definition=new Medicine(c.activation().item(),p.group(),p.priority(),false,0,p.duration(),0,0,p.stats());var effect=new CharacterEffects.Effect(id,c.activation().item(),c.until(),definition);active.put(e.getKey(),new Active(c.activation(),p,effect));added.add(effect);}
        return new Change(List.copyOf(removed),List.copyOf(added));
    }
    private int allocate(){for(int n=0;n<=128;n++){int id=next++;if(next==1_000_000)next=500_000;boolean used=false;for(var value:active.values())if(value.effect().id()==id){used=true;break;}if(!used)return id;}throw new IllegalStateException("area_effect_id_capacity");}
    public Change clear(){return update(List.of(),0);}
    public int size(){return active.size();}
}
