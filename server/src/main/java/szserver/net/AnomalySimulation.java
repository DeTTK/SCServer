package szserver.net;

import szserver.world.*;
import java.util.*;

/** Authoritative 20 Hz anomaly lifecycle. Emits intents; transport and durable health remain in GameServer. */
public final class AnomalySimulation {
    public record Target(String key,int entity,World.Vec position,double width,double height,float health,Map<String,Float> stats,boolean nativeCarousel){}
    public record Event(String type,AnomalyZone zone,Target target,float amount,double[] velocity,boolean set){public Event{velocity=velocity.clone();}}
    private static final class Contact{long entered,next;boolean accepted;Contact(long t,boolean a){entered=t;next=t;accepted=a;}}
    private static final class State{AnomalyZone zone;long next,active;String capture;final Map<String,Contact> contacts=new HashMap<>();State(AnomalyZone z){zone=z;}}
    private record Mirror(AnomalyZone zone,long until,long next){}
    private final Map<String,Mirror> mirrors=new HashMap<>();
    private final Map<UUID,State> states=new LinkedHashMap<>();private final Map<String,World.Vec> previous=new HashMap<>();private final Map<String,Target> lastTargets=new HashMap<>();
    private final Random random;private long steps,activations,hits;private final Map<String,Float> damage;
    public AnomalySimulation(){this(new Random(),Map.of());}
    public AnomalySimulation(Random random,Map<String,Float> damage){this.random=random;this.damage=Map.copyOf(damage);}
    private float damage(AnomalyZone zone,float fallback){return zone.damage()>=0?zone.damage():damage.getOrDefault(zone.kind(),fallback);}
    public List<Event> step(long tick,List<AnomalyZone> zones,List<Target> targets){
        if(zones.size()>8192||targets.size()>256)throw new IllegalArgumentException("anomaly_step_capacity");steps++;var events=new ArrayList<Event>();var live=new HashMap<String,Target>();for(var t:targets)live.put(t.key(),t);var cleanup=new HashMap<>(lastTargets);cleanup.putAll(live);
        var ids=new HashSet<UUID>();for(var z:zones)ids.add(z.id());
        for(var it=states.entrySet().iterator();it.hasNext();){var e=it.next();if(!ids.contains(e.getKey())){release(e.getValue(),cleanup,events);it.remove();}}
        for(var z:zones){var s=states.get(z.id());if(s==null||s.zone.revision()!=z.revision()){if(s!=null)release(s,cleanup,events);s=new State(z);states.put(z.id(),s);}var present=new HashSet<String>();int selected=0;
            for(var t:targets){if(t.health()<=0||t.nativeCarousel()&&z.kind().equals("carousel"))continue;World.Vec from=previous.getOrDefault(t.key(),t.position());if(distance(from,t.position())>16)from=t.position(); // teleports are not swept attacks
                boolean heldCarousel=z.kind().equals("carousel")&&t.key().equals(s.capture);if(heldCarousel&&!z.retains(t.position()))continue;
                if(!heldCarousel&&!z.nearby(from,t.position(),t.width(),t.height()))continue;boolean inside=heldCarousel||z.touches(t.position(),t.position(),t.width(),t.height()),crossed=z.touches(from,t.position(),t.width(),t.height());if(!inside&&!crossed)continue;if(inside)present.add(t.key());var c=s.contacts.get(t.key());if(c==null){c=new Contact(tick,random.nextFloat()<z.settings().chance());s.contacts.put(t.key(),c);}if(!c.accepted)continue;
                switch(z.kind()){
                    case "electra"->{if(tick>=s.next){activate(s,t,events,tick);s.active=tick;s.next=tick+z.cooldown();}if(s.active==tick)hit(z,t,damage(z,60),events);}
                    case "tramp"->{if(tick>=(z.settings().individual()?c.next:s.next)){activate(s,t,events,tick);events.add(new Event("velocity",z,t,0,z.impulse(),z.settings().setVelocity()));hit(z,t,damage(z,0),events);c.next=tick+z.cooldown();if(!z.settings().individual())s.next=c.next;}}
                    case "funnel","carousel"->{if(s.capture==null){s.capture=t.key();c.entered=tick;activate(s,t,events,tick);events.add(event("enter",z,t,0));}if(t.key().equals(s.capture)){long phase=tick-c.entered;if(t.nativeCarousel()&&z.kind().equals("funnel")){double dx=z.center().x()-t.position().x(),dy=z.center().y()+.38-t.position().y(),dz=z.center().z()-t.position().z(),len=Math.sqrt(dx*dx+dy*dy+dz*dz),force=.02+phase/2000.;if(len>1e-5)events.add(new Event("velocity",z,t,0,new double[]{dx/len*force*20,dy/len*force*20,dz/len*force*20},false));}if(z.kind().equals("funnel")){if(phase==z.duration()&&!z.disabledDamage())events.add(event("damage",z,t,t.health()+1));if(phase>=z.duration()+80){events.add(event("leave",z,t,0));s.capture=null;c.accepted=false;}}else{if(phase>0&&phase%20==0)hit(z,t,(phase/20)*damage(z,5),events);if(phase>=z.duration()){events.add(event("leave",z,t,0));s.capture=null;c.accepted=false;}}}}
                    case "stasis"->{if(tick==c.entered)events.add(event("stasis_enter",z,t,0));events.add(event("slow",z,t,.1f));if(tick-c.entered>=z.duration()&&!z.disabledDamage())events.add(event("damage",z,t,t.health()+1));}
                    case "mirror"->{if(tick==c.entered&&!mirrors.containsKey(t.key())){activate(s,t,events,tick);hit(z,t,Math.min(Math.max(0,t.health()-1),t.health()*.6f),events);mirrors.put(t.key(),new Mirror(z,tick+z.duration(),tick+z.period()));events.add(event("mirror",z,t,z.duration()));}}
                    case "tesla"->{if(tick>=s.next){s.active=tick+between(z.duration(),z.maximumDuration());s.next=s.active+between(z.cooldown(),z.maximumCooldown());activate(s,t,events,tick);}if(tick<s.active&&selected++<z.targets())hit(z,t,damage(z,z.damage()),events);}
                    case "lighter"->{if(tick>=s.active){activate(s,t,events,tick);s.active=tick+z.duration();}if(tick>=c.next){hit(z,t,damage(z,5),events);c.next=tick+z.period();}}
                    case "circus"->{if(tick>=c.next){double dx=t.position().x()-z.center().x(),dz=t.position().z()-z.center().z(),scale=z.radius()/4;float amount=0;if(Math.hypot(dx,dz)<1*scale&&t.position().y()<z.bounds().yy()&&t.position().y()+t.height()>z.bounds().y())amount=damage(z,25);else for(int i=0;i<3;i++){double a=Math.toRadians(tick*2+i*120),x=z.center().x()+Math.cos(a)*3*scale,y=z.center().y()+scale,zz=z.center().z()+Math.sin(a)*3*scale;if(Math.hypot(t.position().x()-x,t.position().z()-zz)<.65*scale+t.width()&&t.position().y()<=y+.65*scale&&t.position().y()+t.height()>=y-.65*scale){amount=damage.getOrDefault("circus_radial",40f);break;}}if(amount>0){hit(z,t,amount,events);c.next=tick+z.period();}}}
                    case "steam","puh","simple_anomaly"->{if(tick>=c.next){if(tick==c.entered)activate(s,t,events,tick);float amount=switch(z.kind()){case "steam"->damage(z,10);case "circus"->damage(z,25);default->damage(z,z.damage());};hit(z,t,amount,events);c.next=tick+z.period();}}
                    default->throw new IllegalArgumentException("anomaly_kind");
                }
            }
            for(var it=s.contacts.entrySet().iterator();it.hasNext();){var e=it.next();if(!present.contains(e.getKey())){if(z.kind().equals("stasis")){var t=cleanup.get(e.getKey());if(t!=null)events.add(event("stasis_leave",z,t,0));}if(e.getKey().equals(s.capture)){var t=cleanup.get(e.getKey());if(t!=null)events.add(event("leave",z,t,0));s.capture=null;}it.remove();}}
        }
        for(var it=mirrors.entrySet().iterator();it.hasNext();){var e=it.next();var t=live.get(e.getKey());var m=e.getValue();if(t==null||t.health()<=0||tick>=m.until()){it.remove();continue;}if(tick>=m.next()){hit(m.zone(),t,Math.min(Math.max(0,t.health()-1),t.health()*.2f),events);e.setValue(new Mirror(m.zone(),m.until(),tick+m.zone().period()));}}
        lastTargets.clear();lastTargets.putAll(live);previous.keySet().retainAll(live.keySet());for(var t:targets)previous.put(t.key(),t.position());return List.copyOf(events);
    }
    /** Release the old life before its transport identity advances (death, teleport or disconnect). */
    public List<Event> removeTarget(String key){
        var events=new ArrayList<Event>();Target target=lastTargets.remove(key);previous.remove(key);mirrors.remove(key);
        for(State state:states.values()){
            Contact contact=state.contacts.remove(key);if(key.equals(state.capture)){if(target!=null)events.add(event("leave",state.zone,target,0));state.capture=null;}
            if(contact!=null&&target!=null&&state.zone.kind().equals("stasis"))events.add(event("stasis_leave",state.zone,target,0));
        }
        return List.copyOf(events);
    }
    private int between(int minimum,int maximum){return minimum+random.nextInt(Math.max(minimum,maximum)-minimum+1);}
    private static double distance(World.Vec a,World.Vec b){return Math.sqrt(Math.pow(a.x()-b.x(),2)+Math.pow(a.y()-b.y(),2)+Math.pow(a.z()-b.z(),2));}
    private void hit(AnomalyZone z,Target t,float value,List<Event> events){if(z.disabledDamage()||value<=0)return;float amount=(float)(value*BlastRules.factor(z.channel(),t.stats())*szserver.inventory.CombatModifiers.received(t.stats()));if(amount>0&&Float.isFinite(amount)){events.add(event("damage",z,t,amount));hits++;}}
    private void activate(State s,Target t,List<Event> events,long tick){if(!s.zone.disabledEffect())events.add(event("activate",s.zone,t,0));activations++;}
    private static Event event(String type,AnomalyZone z,Target t,float amount){return new Event(type,z,t,amount,new double[3],false);}
    private static void release(State s,Map<String,Target> live,List<Event> events){var t=live.get(s.capture);if(t!=null)events.add(event("leave",s.zone,t,0));if(s.zone.kind().equals("stasis"))for(String key:s.contacts.keySet()){var v=live.get(key);if(v!=null)events.add(event("stasis_leave",s.zone,v,0));}}
    public Map<String,Object> metrics(){return Map.of("steps",steps,"zones",states.size(),"contacts",states.values().stream().mapToInt(s->s.contacts.size()).sum(),"activations",activations,"hits",hits);}
}
