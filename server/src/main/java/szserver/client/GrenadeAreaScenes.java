package szserver.client;

import szserver.inventory.*;
import szserver.world.*;
import szserver.net.Wire;
import szserver.util.Workers;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Prepared native area objects and worker formatting. Temporary scenes never enter map/editor stores. */
public final class GrenadeAreaScenes implements AutoCloseable {
    private record Template(GrenadeAreaProfile profile,SceneObject object,float radius,int ownerAt,int durabilityAt,SceneCollision.Box bounds){}
    public record Scene(UUID id,int handle,World.Vec position,long until,SceneObject object,byte[] spawn,byte[] remove,int ownerAt,boolean targeted,SceneObject ownedObject){
        public Scene{spawn=spawn.clone();remove=remove.clone();}
        @Override public byte[] spawn(){return spawn.clone();}@Override public byte[] remove(){return remove.clone();}
    }
    private final Map<Integer,List<Template>> templates=new HashMap<>();private final Map<Integer,String> unavailable=new TreeMap<>();
    private final ThreadPoolExecutor worker=Workers.pool("grenade-area-scenes",1,32);private volatile Throwable failure;private volatile boolean closed;private volatile long built,rejected;
    public static boolean smoke(List<GrenadeAreaProfile> rows){if(rows.size()!=1)return false;var p=rows.get(0);return p.target().equals("ALL")&&p.duration()>0&&!p.oneShot()&&Set.of("smoke_aoe","prestige_smoke_aoe").contains(p.object()==null?"":p.object())&&p.stats().isEmpty()&&p.shield()==0&&p.heal()==0&&!p.revive()&&p.damageMultiplier()==0&&p.group()==null&&p.visuals()==null&&p.targetFx()==null;}
    public static boolean gas(List<GrenadeAreaProfile> rows){if(rows.size()!=1)return false;var p=rows.get(0);return p.target().equals("ALL")&&p.duration()>0&&!p.oneShot()&&"gas_aoe".equals(p.object())&&p.stats().equals(Map.of("toxic_accumulation",1.5f))&&p.shield()==0&&p.heal()==0&&!p.revive()&&p.damageMultiplier()==0&&p.group()==null&&p.visuals()==null&&p.targetFx()==null;}
    public static boolean status(List<GrenadeAreaProfile> rows){if(rows.size()!=1)return false;var p=rows.get(0);return p.duration()>0&&!p.oneShot()&&Set.of("gas_aoe","thermal_aoe").contains(p.object()==null?"":p.object())&&p.shield()==0&&p.heal()==0&&!p.revive()&&p.damageMultiplier()==0&&p.visuals()==null&&p.targetFx()==null&&p.stats().keySet().stream().allMatch(k->Set.of("combustion_accumulation","frost_accumulation","toxic_accumulation","biological_accumulation","stopping_accumulation","stopping_protection","artefakt_damage").contains(k));}
    public static boolean continuous(List<GrenadeAreaProfile> rows){if(rows.size()!=1)return false;var p=rows.get(0);return p.duration()>0&&!p.oneShot()&&p.object()!=null&&!p.object().isBlank()&&p.shield()==0&&p.heal()==0&&!p.revive()&&p.damageMultiplier()==0&&p.visuals()==null&&p.targetFx()==null;}
    public static boolean healing(List<GrenadeAreaProfile> rows){if(rows.size()!=1)return false;var p=rows.get(0);return p.duration()>0&&!p.oneShot()&&p.object()!=null&&!p.object().isBlank()&&p.heal()>0&&p.shield()==0&&!p.revive()&&p.damageMultiplier()==0&&p.stats().isEmpty()&&p.visuals()==null&&p.targetFx()==null;}
    public static boolean shield(List<GrenadeAreaProfile> rows){if(rows.size()!=1)return false;var p=rows.get(0);return p.target().equals("ALL")&&p.duration()>0&&!p.oneShot()&&"artgrenade_shield_aoe".equals(p.object())&&p.shield()>0&&p.heal()==0&&!p.revive()&&p.damageMultiplier()==0&&p.stats().isEmpty()&&p.visuals()==null&&p.targetFx()==null;}
    public static boolean pulse(List<GrenadeAreaProfile> rows){if(rows.size()!=1)return false;var p=rows.get(0);return p.duration()>0&&p.oneShot()&&p.object()!=null&&!p.object().isBlank()&&p.shield()==0&&p.heal()==0&&!p.revive()&&p.damageMultiplier()==0&&!p.stats().isEmpty()&&p.visuals()==null&&p.targetFx()==null;}
    public static boolean supportUltimate(List<GrenadeAreaProfile> rows){return rows.size()==2&&GrenadeAreaComponents.revive(List.of(rows.get(0)))&&continuous(List.of(rows.get(1)))&&rows.stream().allMatch(p->p.target().equals("ALLIES")&&"artgrenade_r3_aoe".equals(p.object()));}
    public static boolean ready(List<GrenadeAreaProfile> rows){return smoke(rows)||continuous(rows)||healing(rows)||shield(rows)||pulse(rows)||supportUltimate(rows);}
    public static int selector(GrenadeAreaProfile p){return switch(p.target()){case "SELF"->0;case "ALLIES"->1;case "ENEMIES"->2;case "ALL"->3;default->throw new IllegalArgumentException("area_selector");};}
    public GrenadeAreaScenes(Path root,Path runtime,Path itemRegistry,Path boxesRoot,Map<String,SceneRegistry.Definition> definitions,Map<Integer,List<GrenadeAreaProfile>> profiles,Map<Integer,ExplosiveProfile> explosives)throws Exception{
        this(root,runtime,itemRegistry,boxesRoot,definitions,profiles,explosives,null);
    }
    public GrenadeAreaScenes(Path root,Path runtime,Path itemRegistry,Path boxesRoot,Map<String,SceneRegistry.Definition> definitions,Map<Integer,List<GrenadeAreaProfile>> profiles,Map<Integer,ExplosiveProfile> explosives,Path resources)throws Exception{
        this(root,runtime,itemRegistry,boxesRoot,definitions,profiles,explosives,resources,Set.of());
    }
    public GrenadeAreaScenes(Path root,Path runtime,Path itemRegistry,Path boxesRoot,Map<String,SceneRegistry.Definition> definitions,Map<Integer,List<GrenadeAreaProfile>> profiles,Map<Integer,ExplosiveProfile> explosives,Path resources,Set<Integer> effectOnly)throws Exception{
        SceneCollision.worker();LauncherPackets.verifyManifest(root,runtime,"native-grenade-healing.json");LauncherPackets.verifyManifest(root,runtime,"native-grenade-shield.json");LauncherPackets.verifyManifest(root,runtime,"native-grenade-pulse.json");LauncherPackets.verifyManifest(root,runtime,"native-grenade-revive.json");Path registry=itemRegistry.toAbsolutePath().getParent().resolve("active-stock-gameobjects.json");if(definitions.isEmpty()&&Files.isRegularFile(registry))definitions=SceneRegistry.load(registry).names();
        if(definitions.isEmpty()){profiles.forEach((id,p)->{if(ready(p))unavailable.put(id,"grenade_area_native_resources_missing");});return;}
        try(ClientSceneCodec codec=new ClientSceneCodec(runtime,resources==null?NativeSceneDefaults.resourceRoot(registry):resources)){
            codec.prepareDefaults(root.resolve("fixtures/native-scene-behaviour-bindings.json"));CollisionBoxes boxes=new CollisionBoxes(boxesRoot);
            for(var e:profiles.entrySet())if(ready(e.getValue()))try{
                List<Template> parts=new ArrayList<>();for(var p:e.getValue()){var definition=definitions.get(p.object());var explosive=explosives.get(e.getKey());if(explosive==null)throw new IllegalArgumentException("grenade_area_explosive_missing");if(explosive.radius()<=0)throw new IllegalArgumentException("grenade_area_authored_zero_radius");
                if(effectOnly.contains(e.getKey())){
                    if(e.getValue().size()!=1||!continuous(e.getValue())&&!pulse(e.getValue()))throw new IllegalArgumentException("grenade_effect_only_contract");
                    float radius=explosive.radius()*p.radiusScale();if(!Float.isFinite(radius)||radius<=0||radius>128)throw new IllegalArgumentException("grenade_area_radius_budget");
                    // The resource declares an effect bank without an authored scene.
                    // Preserve its explosion/target/duration contract without inventing a prefab.
                    parts.add(new Template(p,null,radius,-1,-1,null));continue;
                }
                if(definition==null)throw new IllegalArgumentException("grenade_area_scene_registry");
                // AOE collision is deliberately none; its declared native AABB owns visual size.
                var geometry=definition.definition().deepCopy();geometry.remove("collision");var shape=boxes.get(new SceneRegistry.Definition(definition.id(),definition.name(),definition.behaviour(),definition.profile(),geometry));
                if(shape.size()!=1)throw new IllegalArgumentException("grenade_area_smoke_shape");var b=shape.get(0);if(Math.abs(b.x()+.5)>.001||Math.abs(b.y()+.5)>.001||Math.abs(b.z()+.5)>.001||Math.abs(b.xx()-.5)>.001||Math.abs(b.yy()-.5)>.001||Math.abs(b.zz()-.5)>.001)throw new IllegalArgumentException("grenade_area_smoke_aabb_changed");
                float radius=explosive.radius()*p.radiusScale();if(!Float.isFinite(radius)||radius<=0||radius>128)throw new IllegalArgumentException("grenade_area_radius_budget");
                var seed=NativeSceneDefaults.envelope(definition);byte[] scaled=seed.record();ByteBuffer out=ByteBuffer.wrap(scaled);for(int axis:new int[]{0,4,8})out.putFloat(SceneObject.prefix(scaled).transformAt()+axis*4,radius*2);
                var object=codec.grenadeAreaObject(definition,new SceneObject(definition.name(),scaled),0,selector(p),p.targetFx());codec.grenadeAreaSpawn(definition,object,1);int ownerAt=ownerOffset(object);codec.grenadeAreaSpawn(definition,withoutOwner(object,ownerAt),1);parts.add(new Template(p,object,radius,ownerAt,p.shield()>0?durabilityOffset(object):-1,b));}templates.put(e.getKey(),List.copyOf(parts));
            }catch(Exception error){unavailable.put(e.getKey(),error.getClass().getSimpleName()+":"+String.valueOf(error.getMessage()));}
        }catch(Exception|Error error){worker.shutdownNow();throw error;}
    }
    public Set<Integer> items(){return Set.copyOf(templates.keySet());}
    public boolean visible(int item){var parts=templates.get(item);return parts!=null&&parts.stream().anyMatch(t->t.object()!=null);}
    /** Locate the actual flat NBT int once on the preparation worker, never a guessed packet offset. */
    private static int ownerOffset(SceneObject object){
        byte[] record=object.record(),tag=Wire.bytes(out->{out.writeByte(3);out.writeUTF("owner_entity_id");});int start=SceneObject.prefix(record).behaviourAt();var settings=SceneSettings.compound(record,start);if(settings==null||settings.get("owner_entity_id").getAsInt()!=0)throw new IllegalArgumentException("area_owner_settings");int found=-1;
        for(int i=start;i+tag.length+4<=record.length;i++){boolean matches=true;for(int k=0;k<tag.length;k++)if(record[i+k]!=tag[k]){matches=false;break;}if(matches){if(found!=-1)throw new IllegalArgumentException("area_owner_duplicate");found=i+tag.length;}}
        if(found<0||ByteBuffer.wrap(record).getInt(found)!=0)throw new IllegalArgumentException("area_owner_tag");return found;
    }
    private static SceneObject withoutOwner(SceneObject object,int ownerAt){byte[] original=object.record();int tagSize=Wire.bytes(out->{out.writeByte(3);out.writeUTF("owner_entity_id");}).length,begin=ownerAt-tagSize,end=ownerAt+4;byte[] record=new byte[original.length-(end-begin)];System.arraycopy(original,0,record,0,begin);System.arraycopy(original,end,record,begin,original.length-end);return new SceneObject(object.asset(),record);}
    /** Local owner is entity zero, observers use the owner's remote entity. Cold owners remain -1. */
    public static byte[] spawnFor(Scene scene,int owner){if(!scene.targeted()||owner==-1)return scene.spawn();if(owner< -1)throw new IllegalArgumentException("area_owner_id");byte[] record=scene.ownedObject().record();ByteBuffer.wrap(record).putInt(scene.ownerAt(),owner);return Wire.dynamic(GrenadeAreaPackets.SPAWN,GrenadeAreaPackets.spawnWireBody(1,List.of(new SceneObject(scene.object().asset(),record))));}
    public List<szserver.net.AreaExposure.Contact> contacts(Collection<GrenadeAreaStore.Activation> areas,double[] feet,double height,long now){
        return contacts(areas,feet,height,now,false);
    }
    public List<szserver.net.AreaExposure.Contact> pulseContacts(Collection<GrenadeAreaStore.Activation> areas,double[] feet,double height,long now){return contacts(areas,feet,height,now,true);}
    private List<szserver.net.AreaExposure.Contact> contacts(Collection<GrenadeAreaStore.Activation> areas,double[] feet,double height,long now,boolean pulses){
        List<szserver.net.AreaExposure.Contact> out=new ArrayList<>();for(var a:areas)if(now<a.until()){
            var parts=templates.get(a.item());if(parts==null)continue;for(int index=0;index<parts.size();index++){Template t=parts.get(index);if(now>=a.started()+t.profile().durationMillis()||t.profile().oneShot()!=pulses||t.profile().stats().isEmpty()&&t.profile().heal()==0&&!t.profile().revive())continue;double r=t.radius();var p=a.position();
            if(feet[0]+.3<p.x()-r||feet[0]-.3>p.x()+r||feet[1]+height<p.y()-r||feet[1]>p.y()+r||feet[2]+.3<p.z()-r||feet[2]-.3>p.z()+r)continue;
            out.add(new szserver.net.AreaExposure.Contact(a,t.profile(),index));}
        }return List.copyOf(out);
    }
    /** Native AABB feedback contact for an actor's body; no extra collision wall or scene persistence. */
    public Map<String,Float> exposure(Collection<GrenadeAreaStore.Activation> areas,double[] feet,double height,long now){
        Map<String,Float> out=new TreeMap<>();for(var a:areas)if(now<a.until()){
            var parts=templates.get(a.item());if(parts==null)continue;for(Template t:parts){if(now>=a.started()+t.profile().durationMillis()||t.profile().oneShot()||t.profile().stats().isEmpty())continue;if(!t.profile().target().equals("ALL")||t.profile().group()!=null)throw new IllegalArgumentException("area_requires_target_policy");double r=t.radius();var p=a.position();
            if(feet[0]+.3<p.x()-r||feet[0]-.3>p.x()+r||feet[1]+height<p.y()-r||feet[1]>p.y()+r||feet[2]+.3<p.z()-r||feet[2]-.3>p.z()+r)continue;
            t.profile().stats().forEach((key,value)->out.merge(key,value,Float::sum));}
        }return Map.copyOf(out);
    }
    public void validate(GrenadeAreaStore.Activation activation){var t=templates.get(activation.item());if(t==null||!activation.profiles().equals(t.stream().map(Template::profile).toList()))throw new IllegalArgumentException("grenade_area_scene_restore_profile:"+activation.item());}
    public CompletableFuture<Scene> prepare(GrenadeAreaStore.Activation activation,int handle){return prepare(activation,handle,1);}
    public CompletableFuture<Scene> prepare(GrenadeAreaStore.Activation activation,int handle,float fraction){return prepare(activation,0,handle,fraction);}
    public CompletableFuture<Scene> prepare(GrenadeAreaStore.Activation activation,int index,int handle,float fraction){
        if(!visible(activation.item()))return CompletableFuture.failedFuture(new IllegalArgumentException("grenade_effect_has_no_scene"));
        if(!Float.isFinite(fraction)||fraction<=0||fraction>1)throw new IllegalArgumentException("grenade_shield_fraction");validate(activation);var component=new GrenadeAreaComponents.Component(activation,index);if(handle>=-1)throw new IllegalArgumentException("grenade_area_handle");if(closed||failure!=null)return CompletableFuture.failedFuture(new IllegalStateException("grenade_area_scene_unavailable",failure));
        CompletableFuture<Scene> result=new CompletableFuture<>();try{worker.execute(()->{try{
            SceneCollision.worker();var t=templates.get(activation.item()).get(index);byte[] record=t.object().record();if(t.durabilityAt()>=0)ByteBuffer.wrap(record).putFloat(t.durabilityAt(),fraction);else if(fraction!=1)throw new IllegalArgumentException("grenade_area_not_shield");int at=SceneObject.prefix(record).transformAt();ByteBuffer out=ByteBuffer.wrap(record);out.putInt(at-20,handle).putLong(at-16,component.identity().getMostSignificantBits()).putLong(at-8,component.identity().getLeastSignificantBits());out.putFloat(at+36,(float)activation.position().x()).putFloat(at+40,(float)activation.position().y()).putFloat(at+44,(float)activation.position().z());var object=new SceneObject(t.object().asset(),record);
            SceneObject plain=withoutOwner(object,t.ownerAt());Scene scene=new Scene(object.identity(),handle,activation.position(),component.until(),plain,Wire.dynamic(GrenadeAreaPackets.SPAWN,GrenadeAreaPackets.spawnWireBody(1,List.of(plain))),GrenadeAreaPackets.remove(handle),t.ownerAt(),!t.profile().target().equals("ALL"),object);built++;result.complete(scene);
        }catch(Throwable error){failure=error;result.completeExceptionally(error);}});}catch(RejectedExecutionException error){rejected++;result.completeExceptionally(error);}return result;
    }
    private static int durabilityOffset(SceneObject object){
        byte[] record=object.record(),tag=Wire.bytes(out->{out.writeByte(5);out.writeUTF("durability");});int begin=SceneObject.prefix(record).behaviourAt();var saved=SceneSettings.compound(record,begin);if(saved==null||!saved.has("durability")||saved.get("durability").getAsFloat()!=1)throw new IllegalArgumentException("grenade_shield_native_durability");int found=-1;
        for(int at=begin;at+tag.length+4<=record.length;at++){boolean same=true;for(int i=0;i<tag.length;i++)if(record[at+i]!=tag[i]){same=false;break;}if(same){if(found!=-1)throw new IllegalArgumentException("grenade_shield_duplicate_durability");found=at+tag.length;}}
        if(found<0||ByteBuffer.wrap(record).getFloat(found)!=1)throw new IllegalArgumentException("grenade_shield_native_durability");return found;
    }
    public record ShieldContact(GrenadeAreaStore.Activation activation,double distance){}
    /** One deterministic radial contact per explosion/shield, independent of its victims. */
    public double[] closestShieldPoint(ShieldContact contact,double[] origin){
        var a=contact.activation();var t=templates.get(a.item()).get(0);double scale=t.radius()*2;var b=t.bounds();var p=a.position();
        return new double[]{Math.max(p.x()+b.x()*scale,Math.min(p.x()+b.xx()*scale,origin[0])),Math.max(p.y()+b.y()*scale,Math.min(p.y()+b.yy()*scale,origin[1])),Math.max(p.z()+b.z()*scale,Math.min(p.z()+b.zz()*scale,origin[2]))};
    }
    /** At most128 prepared single native boxes; no IO/native construction on the gameplay owner. */
    public ShieldContact shieldContact(Collection<GrenadeAreaStore.Activation> areas,double[] origin,double[] end,long now){
        double x=end[0]-origin[0],y=end[1]-origin[1],z=end[2]-origin[2],maximum=Math.sqrt(x*x+y*y+z*z);if(maximum<1e-9)return null;World.Vec from=new World.Vec(origin[0],origin[1],origin[2]),direction=new World.Vec(x/maximum,y/maximum,z/maximum);ShieldContact first=null;
        for(var a:areas){var parts=templates.get(a.item());Template t=parts==null||parts.size()!=1?null:parts.get(0);if(t==null||t.profile().shield()<=0||now>=a.until())continue;double scale=t.radius()*2;var b=t.bounds();var p=a.position();var box=new SceneCollision.Box(p.x()+b.x()*scale,p.y()+b.y()*scale,p.z()+b.z()*scale,p.x()+b.xx()*scale,p.y()+b.yy()*scale,p.z()+b.zz()*scale);Double distance=SceneCollision.ray(from,direction,box,maximum);if(distance!=null&&(first==null||distance<first.distance()||distance==first.distance()&&a.projectile().compareTo(first.activation().projectile())<0))first=new ShieldContact(a,distance);}
        return first;
    }
    public boolean healthy(){return !closed&&failure==null;}
    public Map<String,Object> metrics(){return Map.of("healthy",healthy(),"prepared",templates.size(),"preparedComponents",templates.values().stream().mapToInt(List::size).sum(),"effectOnly",templates.keySet().stream().filter(id->!visible(id)).count(),"unavailable",Map.copyOf(unavailable),"built",built,"rejected",rejected,"queued",worker.getQueue().size());}
    @Override public void close()throws InterruptedException{Workers.stop(worker);closed=true;}
}
