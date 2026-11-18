package replay;
import java.util.*;import java.util.function.LongSupplier;

/** Animation contexts own skeletons; rendering also requires their independent
 * body, armor, dimension materials and held-item GPU model references. */
final class SceneActorAssets implements AutoCloseable {
    final SceneMapAssets assets=new SceneMapAssets();
    final Set<Object> inspected=Collections.newSetFromMap(new IdentityHashMap<>());
    void actor(NativeSceneSelection.Actor actor)throws Exception {
        if(inspected.contains(actor.entity))return;
        Object entity=actor.entity;boolean player=Native.type("200241649").isInstance(entity);
        Object owner=player?Native.call(Native.type("1478690128"),"new","1579323378",entity):Native.type("1848416318").isInstance(entity)?Native.field(entity,"native"):null;
        if(owner!=null&&(player||Native.type("859153379").isInstance(owner))){
            Object equipment=Native.call(owner,"interface","1619781176");Object wear=Native.call(owner,player?"assert":"if","611440048");
            for(Object mode:(Object[])Native.call(Native.type("1969001007"),"values","[L1969001007;")){
                // Armor and the skinned body use separate render references.
                // case(mode) contains body renderers with dynamic materials;
                // player void(mode) also supplies the native fallback model.
                assets.model(Native.call(player?owner:equipment,player?"void":"case","1304833431",mode));
                assets.model(Native.call(equipment,"static","1304833431",mode));
                assets.model(Native.call(wear,"new","1304833431",mode));
            }
            if(!player)assets.model(Native.call(owner,"assert","1304833431"));
            if(player){
                // Player backpack/item rendering uses a separate equipment-slot
                // profile (1579323378.throw), not the secondary wear graph.
                Object inventory=Native.call(Native.type("276406255"),"implements","276406255",entity);
                Object worn=Native.call(Native.field(inventory,"class"),"enum","211097523");
                worn(worn);
            }
        }
        if(actor.inputs!=null){item(actor.inputs.controllerItem());item(actor.inputs.handsItem());}
        if(SceneRemains.is(entity)){
            Object renderer=SceneRemains.renderer(entity);
            if(renderer!=null){Object equipment=Native.field(renderer,"else"),wear=Native.field(renderer,"super");
                for(Object mode:(Object[])Native.call(Native.type("1969001007"),"values","[L1969001007;")){
                    if(equipment!=null){assets.model(Native.call(equipment,"case","1304833431",mode));assets.model(Native.call(equipment,"static","1304833431",mode));}
                    if(wear!=null)assets.model(Native.call(wear,"new","1304833431",mode));
                }
            }
        }
        inspected.add(entity);
    }
    void item(Object item)throws Exception {
        if(item==null)return;Object definition=Native.call(item,"continue","168071319");
        if(definition!=null)assets.model(Native.call(definition,"byte","1304833431",item));
        if(definition!=null&&Native.type("289750496").isInstance(definition)){
            // Native gun readiness/rendering enumerates attachment and sight
            // models independently of the base weapon. Retain those exact refs.
            for(Object model:(List<?>)Native.call(definition,"enum","java.util.List",item))assets.model(model);
            assets.model(Native.call(definition,"class","1304833431",item,true));
            Object lod=Native.field(definition,"assert");
            if(lod!=null)assets.model(Native.call(lod,"const","1304833431",item));
        }
    }
    void worn(Object item)throws Exception {
        if(item==null)return;Object definition=Native.call(item,"interface","1366800141");
        if(!Native.type("1441854005").isInstance(definition))return;
        Object profile=Native.call(definition,"final","1982565452",item);if(profile==null)return;
        assets.model(Native.call(profile,"transient","1304833431",item));
        assets.model(Native.field(profile,"new"));assets.model(Native.field(profile,"final"));
    }
    void fpSleeves(Object entity)throws Exception {
        Object style=Native.call(Native.type("916325480"),"double","916325480",entity);if(style==null)return;
        Object factory=Native.field(style,"break");if(factory==null)return;
        Object inventory=Native.call(Native.type("353451775"),"super","1559281940",entity);
        Object armor=Native.call(Native.call(inventory,"goto","1721909562"),"else","211097523");
        Object model=armor==null?Native.call(factory,"native","1304833431"):Native.call(factory,"catch","1304833431",Native.call(Native.call(armor,"interface","1366800141"),"throws","java.lang.String",armor));
        assets.model(model);
    }
    boolean ready(long deadline,LongSupplier clock)throws Exception{return assets.ready(deadline,clock);}
    Map<String,Object> diagnostic(){return assets.diagnostic();}
    public void close()throws Exception {try{assets.close();}finally{inspected.clear();}}
}
