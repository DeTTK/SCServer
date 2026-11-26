package vr;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import static vr.Math3.*;
import static vr.NativeAccess.*;

/** The equipped stack and the native model provider resolve attachments and their mounting transforms. */
final class NativeAttachments {
    private Object source,provider,definition,rifle,magazineSlot,installedMagazine,renderCopy;
    private V support;
    private boolean resolved;

    void prepare(GameSession game)throws Exception {
        if(source!=game.item){
            source=game.item;
            provider=call(source,"continue","168071319");
            rifle=definition=installedMagazine=renderCopy=null;
            support=null;
            resolved=false;
        }
        if(type("289750496").isInstance(provider)&&!resolved){
            rifle=call(provider,"class","1304833431",source,true);
            if(rifle!=null&&(boolean)call(rifle,"catch","boolean")){
                definition=get(provider,"continue");
                magazineSlot=get(type("1536296909"),"MAGAZINE");
                installedMagazine=call(definition,"implements","211097523",source,magazineSlot);
                resolved=resolveSupport();
            }
        }
        if(support!=null)game.gun.mounts(game.profile.withSupport(support));
    }

    private boolean resolveSupport()throws Exception {
        Object handle=get(type("445983397"),"HANDLE");
        Object foregrip=call(definition,"for","211097523",source,handle);
        if(foregrip==null)return true;
        for(Object slot:(Object[])get(type("1536296909"),"slots")){
            if(call(definition,"continue","211097523",source,slot)!=foregrip)continue;
            Object attachment=call(foregrip,"interface","1366800141");
            Object prototype=call(attachment,"switch","1720634751",source);
            if(prototype==null)return true;
            Object weaponVariant=call(definition,"throws","java.lang.String",source);
            Object model=call(attachment,"extends","1304833431",prototype,weaponVariant,get(get(provider,"private"),"private"),false);
            if(model==null||!(boolean)call(model,"catch","boolean"))return false;
            Object weaponSkeleton=call(rifle,"double","1612003613");
            if(weaponSkeleton==null)return false;
            Object saved=get(provider,"throws");
            call(type("889335306"),"if","void");
            try{
                set(provider,"throws",rifle);
                Object matrix=get(type("889335306"),"implements");
                call(matrix,"native","447252483");
                if(!(boolean)call(provider,"const","boolean",source,attachment,slot,get(weaponSkeleton,"double"),""))return true;
                float[] values=new float[16];
                call(matrix,"void","[F",(Object)values);
                Pose mount=OpticMath.matrix(values);
                Object skeleton=call(model,"double","1612003613");
                if(skeleton!=null){
                    NativePose pose=new NativePose(skeleton,get(skeleton,"double"));
                    for(String name:new String[]{"grip","handle","grip_jnt","handle_jnt"}){
                        if(pose.index.containsKey(name)){
                            support=mount.transform(pose.sample(name).p());
                            return true;
                        }
                    }
                }
                support=mount.transform(new V(0,-.055,0));
                return true;
            }finally{
                set(provider,"throws",saved);
                call(type("889335306"),"default","void");
            }
        }
        return true;
    }

    Object weaponItem(GameSession game)throws Exception {
        if(installedMagazine==null||game.spec.singleRounds()||game.gun.magazine==Ak15.Magazine.INSERTED)return game.item;
        if(renderCopy==null){
            renderCopy=call(game.item,"enum","211097523");
            call(definition,"public","void",renderCopy,null,magazineSlot);
        }
        return renderCopy;
    }

    boolean drawMagazine(GameSession game)throws Exception {
        if(installedMagazine==null||game.spec.singleRounds())return false;
        Class<?> poseContext=type("243964136");
        Object skeleton=call(rifle,"double","1612003613");
        if(skeleton==null)return false;
        Object rest=get(skeleton,"double");
        Object context=Proxy.newProxyInstance(poseContext.getClassLoader(),new Class<?>[]{poseContext},
            (proxy,method,args)->method.getName().equals("extends")?rest:InvocationHandler.invokeDefault(proxy,method,args));
        Object saved=get(provider,"throws");
        try{
            set(provider,"throws",rifle);
            call(provider,"const","void",source,magazineSlot,rest,context,context,false,false,rifle);
            return true;
        }finally{set(provider,"throws",saved);}
    }
}
