package replay;

import java.util.*;

/** Explicit native attachment roles. No recursive object inspection or animation ticks. */
final class SceneSecondaryRigs {
    static final List<String> ROLES=List.of("equipment","hands","wear");
    record Rig(Object graph,SceneTracks.Bytes skeleton){}
    record Inputs(SceneTracks.Value held,SceneTracks.Value hands,Object controllerItem,Object handsItem){
        static Inputs decode(SceneTracks.ObjectState state)throws Exception {
            SceneTracks.Value held=state.channels().get("controller-held-item"),hands=state.channels().get("hands-held-item");
            return new Inputs(held,hands,held instanceof SceneTracks.Bytes b?SceneControllerItem.decode(b):null,hands instanceof SceneTracks.Bytes b?SceneControllerItem.decode(b):null);
        }
        boolean matches(SceneTracks.ObjectState state){return Objects.equals(held,state.channels().get("controller-held-item"))&&Objects.equals(hands,state.channels().get("hands-held-item"));}
    }
    static Object hands(Object entity)throws Exception {
        boolean player=Native.type("200241649").isInstance(entity);
        Object owner=player?Native.call(Native.type("1478690128"),"new","1579323378",entity):Native.type("1848416318").isInstance(entity)?Native.field(entity,"native"):null;
        return owner==null?null:Native.call(owner,player?"implements":"int","1430189226");
    }
    static Object equipment(Object entity)throws Exception {
        boolean player=Native.type("200241649").isInstance(entity);
        Object owner=player?Native.call(Native.type("1478690128"),"new","1579323378",entity):Native.type("1848416318").isInstance(entity)?Native.field(entity,"native"):null;
        return owner==null?null:Native.call(owner,"interface","1619781176");
    }
    static void prepare(Object entity,Inputs inputs)throws Exception {
        SceneTracks.Value held=inputs.held();Object recorded=inputs.controllerItem();
        if(Native.type("200241649").isInstance(entity)){
            Object owner=Native.call(Native.type("1478690128"),"new","1579323378",entity);if(owner!=null){
                Object controller=Native.call(owner,"static","1805188833");
                Native.call(controller,"throws","void",held!=null?recorded:Native.call(Native.type("764970879"),"implements","211097523",entity));
                bindContexts(owner,entity,inputs,true);Native.call(owner,"szFp_assert","boolean",true,null);bindContexts(owner,entity,inputs,true);
            }
        }else if(Native.type("1848416318").isInstance(entity)){
            Object owner=Native.field(entity,"native");if(owner!=null&&Native.type("859153379").isInstance(owner)){
                Object controller=Native.call(owner,"short","1805188833");
                Native.call(controller,"throws","void",held!=null?recorded:Native.call(entity,"while","211097523"));
                bindContexts(owner,entity,inputs,false);Native.call(owner,"package","boolean",true,null);bindContexts(owner,entity,inputs,false);
            }
        }
        if(inputs.hands()!=null){
            Object holder=hands(entity),primary=NativeScenePresenter.graphIdentity(entity);
            if(holder!=null&&primary!=null)Native.call(holder,"final","void",primary,inputs.handsItem());
        }
    }
    static void bindContexts(Object owner,Object entity,Inputs inputs,boolean player)throws Exception {
        Object primary=NativeScenePresenter.graphIdentity(entity);
        if(primary==null)return;
        Native.call(Native.call(owner,"interface","1619781176"),"super","void",primary);
        Native.call(Native.call(owner,player?"assert":"if","611440048"),"final","void",primary);
        Object item=inputs.hands()!=null?inputs.handsItem():Native.call(SceneControllerItem.controller(entity),"throws","211097523");
        Native.call(Native.call(owner,player?"implements":"int","1430189226"),"final","void",primary,item);
    }
    static boolean ready(SceneTracks.ObjectState state,Map<String,Rig> bound){
        return waiting(state,bound).isEmpty();
    }
    static boolean ready(SceneTracks.ObjectState state,Map<String,Rig> bound,SceneSkeletonIdentity verified)throws Exception {
        for(String role:ROLES)if(state.channels().get("attachment/"+role+"/rig-layout") instanceof SceneTracks.Bytes layout&&layout.size()>0){
            Rig rig=bound.get(role);if(rig==null)return false;String key=new String(layout.data(),java.nio.charset.StandardCharsets.UTF_8);int separator=key.indexOf(':');if(separator<0)return false;String identity=key.substring(0,separator);
            if(!verified.matches(rig.skeleton(),state.channels().get("attachment/"+role+"/skeleton/"+identity),identity))return false;
        }
        return true;
    }
    static String waiting(SceneTracks.ObjectState state,Map<String,Rig> bound){
        for(String role:ROLES)if(state.channels().get("attachment/"+role+"/rig-layout") instanceof SceneTracks.Bytes layout&&layout.size()>0){
            Rig rig=bound.get(role);if(rig==null)return "missing "+role;
            String key=new String(layout.data(),java.nio.charset.StandardCharsets.UTF_8);
            int separator=key.indexOf(':');if(separator<0)return "invalid "+role;
            if(!rig.skeleton().equals(state.channels().get("attachment/"+role+"/skeleton/"+key.substring(0,separator)))){
                try{return "skeleton "+role+" expected="+key+" actual="+NativeScenePresenter.sha(rig.skeleton());}catch(Exception failure){return "invalid skeleton "+role;}
            }
        }
        return "";
    }
    static Map<String,Object> graphs(Object entity,Object primary)throws Exception {
        Object owner;boolean player=Native.type("200241649").isInstance(entity);
        if(player)owner=Native.call(Native.type("1478690128"),"new","1579323378",entity);
        else if(Native.type("1848416318").isInstance(entity))owner=Native.field(entity,"native");
        else return Map.of();
        if(owner==null||!player&&!Native.type("859153379").isInstance(owner))return Map.of();
        Map<String,Object> result=new LinkedHashMap<>();
        Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());if(primary!=null)seen.add(primary);
        Object equipment=Native.call(owner,"interface","1619781176");
        Object model=equipment==null?null:Native.field(equipment,"interface");
        add(result,seen,"equipment",model==null?null:Native.call(model,"transient","387875964"));
        Object hands=Native.call(owner,player?"implements":"int","1430189226");
        Object handsModel=hands==null?null:Native.call(hands,"float","461253740");
        add(result,seen,"hands",handsModel==null?null:Native.call(handsModel,"float","java.lang.Object"));
        Object wear=Native.call(owner,player?"assert":"if","611440048");
        // implements() also evaluates the graph. Ownership discovery must be
        // read-only: evaluating here can re-enter selected pose resolution.
        Object wearController=wear==null?null:Native.field(wear,"assert");
        add(result,seen,"wear",wearController==null||!(boolean)Native.call(wearController,"else","boolean")?null:Native.field(wearController,"case"));
        return Map.copyOf(result);
    }
    static void add(Map<String,Object> result,Set<Object> seen,String role,Object graph)throws Exception {
        if(graph!=null&&Native.type("387875964").isInstance(graph)&&seen.add(graph))result.put(role,graph);
    }
    static Map<String,Rig> bind(Map<String,Object> graphs)throws Exception {
        Map<String,Rig> result=new LinkedHashMap<>();
        for(var e:graphs.entrySet()){
            Object skeleton=Native.call(e.getValue(),"strictfp","1612003613");
            if(skeleton!=null)result.put(e.getKey(),new Rig(e.getValue(),NativeSceneCapture.skeletonDefinition(skeleton)));
        }
        return Map.copyOf(result);
    }
    static boolean matches(Map<String,Rig> bound,Map<String,Object> graphs){
        if(bound.size()!=graphs.size())return false;
        for(var e:bound.entrySet())if(e.getValue().graph()!=graphs.get(e.getKey()))return false;
        return true;
    }
}
