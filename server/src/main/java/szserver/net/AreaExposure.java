package szserver.net;

import szserver.inventory.*;import java.util.*;import java.util.function.Function;

/** Native effect-bank selection after geometry contact. No IO or client objects on the owner. */
public final class AreaExposure {
    public record Contact(GrenadeAreaStore.Activation activation,GrenadeAreaProfile profile,int index){
        public Contact(GrenadeAreaStore.Activation activation,GrenadeAreaProfile profile){this(activation,profile,activation.profiles().indexOf(profile));}
        public Contact{if(index<0||index>=activation.profiles().size()||!profile.equals(activation.profiles().get(index)))throw new IllegalArgumentException("grenade_contact_profile");}
        public long until(){return activation.started()+profile.durationMillis();}
    }
    public static Map<String,Float> resolve(Collection<Contact> contacts,AreaRelations relations,AreaRelations.Actor target,Function<UUID,AreaRelations.Actor> liveOwner,AreaRelations.Mode mode){
        return bank(select(contacts,relations,target,liveOwner,mode));
    }
    /** Actual GameplayEffects sums stats, but gravity takes the strongest positive and negative. */
    public static Map<String,Float> bank(Collection<Contact> selected){
        return banks(selected.stream().map(c->c.profile().stats()).toList());
    }
    public static Map<String,Float> banks(Collection<Map<String,Float>> sources){
        Map<String,Float> bank=new TreeMap<>();float positive=0,negative=0;for(var source:sources){for(var e:source.entrySet())if(!e.getKey().equals("gravity_modifier"))bank.merge(e.getKey(),e.getValue(),Float::sum);float gravity=source.getOrDefault("gravity_modifier",0f);if(gravity>.01f)positive=Math.max(positive,gravity);else if(gravity<-.01f)negative=Math.min(negative,gravity);}if(Math.abs(positive+negative)>.001f)bank.put("gravity_modifier",positive+negative);
        if(bank.values().stream().anyMatch(v->!Float.isFinite(v)))throw new IllegalStateException("grenade_area_exposure_overflow");return Map.copyOf(bank);
    }
    public static List<Contact> select(Collection<Contact> contacts,AreaRelations relations,AreaRelations.Actor target,Function<UUID,AreaRelations.Actor> liveOwner,AreaRelations.Mode mode){
        List<Contact> selected=new ArrayList<>();Map<String,Contact> groups=new TreeMap<>();
        for(var contact:contacts){var area=contact.activation();var profile=contact.profile();var owner=liveOwner.apply(area.character());if(owner==null)owner=area.ownerContext();if(!relations.selected(profile.target(),owner,target,mode))continue;
            if(profile.group()==null)selected.add(contact);else groups.merge(profile.group(),contact,(a,b)->compare(a,b)>=0?a:b);
        }
        selected.addAll(groups.values());selected.sort(Comparator.comparing((Contact c)->c.activation().projectile()).thenComparingInt(Contact::index));return List.copyOf(selected);
    }
    /** Higher native priority wins; newest equal-priority contact, then stable UUID breaks ties. */
    private static int compare(Contact a,Contact b){int order=Byte.compare(a.profile().priority(),b.profile().priority());if(order==0)order=Long.compare(a.activation().started(),b.activation().started());if(order==0)order=a.activation().projectile().compareTo(b.activation().projectile());if(order==0)order=Integer.compare(a.index(),b.index());return order;}
    private AreaExposure(){}
}
