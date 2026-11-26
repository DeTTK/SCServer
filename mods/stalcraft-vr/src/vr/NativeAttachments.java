package vr;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import static vr.Math3.*;
import static vr.NativeAccess.*;

/** The equipped stack and the native model provider resolve attachments and their mounting transforms. */
final class NativeAttachments {
    private enum Action { LAUNCHER, ZOOM, LASER, STOCK }
    private record Contact(Action action,Pose pose){}
    private final java.util.List<Contact> contacts=new java.util.ArrayList<>();
    private Object source,provider,definition,rifle,magazineSlot,installedMagazine,renderCopy,identity;
    private V support;
    private Pose laserMount;
    private String stockBone,foldedBone;
    private boolean resolved,laserEnabled,folded;
    V beamStart,beamEnd;

    void prepare(GameSession game)throws Exception {
        if(source!=game.item){
            source=game.item;
            provider=call(source,"continue","168071319");
            rifle=definition=installedMagazine=renderCopy=null;
            support=null;laserMount=null;stockBone=foldedBone=null;
            contacts.clear();
            resolved=false;
            if(!java.util.Objects.equals(identity,game.weaponIdentity)){
                identity=game.weaponIdentity;laserEnabled=folded=false;
            }
        }
        if(type("289750496").isInstance(provider)&&!resolved){
            rifle=call(provider,"class","1304833431",source,true);
            if(rifle!=null&&(boolean)call(rifle,"catch","boolean")){
                definition=get(provider,"continue");
                magazineSlot=get(type("1536296909"),"MAGAZINE");
                installedMagazine=call(definition,"implements","211097523",source,magazineSlot);
                resolved=resolveContacts(game.profile.rest);
            }
        }
        if(support!=null)game.gun.mounts(game.profile.withSupport(support));
    }

    private boolean resolveContacts(NativePose rest)throws Exception {
        Object weaponSkeleton=call(rifle,"double","1612003613");
        if(weaponSkeleton==null)return false;
        Object nativePose=get(weaponSkeleton,"double");
        Object sightSlot=call(definition,"char","1536296909",source);
        Object sight=call(definition,"abstract","1710540327",source);
        contacts.clear();support=null;laserMount=null;
        Object saved=get(provider,"throws");
        set(provider,"throws",rifle);
        try{
            for(Object slot:(Object[])get(type("1536296909"),"slots")){
                Object installed=call(definition,"implements","211097523",source,slot);
                Object part=installed==null?null:call(installed,"interface","1366800141");
                if(slot==sightSlot&&sight!=null)part=sight;
                if(part==null)continue;
                var category=(Enum<?>)type("62393481").getField("continue").get(part);
                Action action=switch(category.name()){
                    case "UNDERBARREL" -> Action.LAUNCHER;
                    case "SIGHT" -> Action.ZOOM;
                    case "SIDE_ATTACHMENT","ACCESSORY" -> Action.LASER;
                    default -> null;
                };
                boolean grip=category.name().equals("HANDLE")||category.name().equals("HANDGRIP");
                if(action==null&&!grip)continue;
                Object prototype=call(part,"switch","1720634751",source);
                if(prototype==null){
                    if(action==Action.ZOOM)contactFromBone(rest,slot,action);
                    continue;
                }
                String variant=(String)call(definition,"throws","java.lang.String",source);
                Object model=call(part,"extends","1304833431",prototype,variant,get(get(provider,"private"),"private"),false);
                if(model==null||!(boolean)call(model,"catch","boolean"))return false;
                Pose mount=mount(part,slot,nativePose);
                if(mount==null)continue;
                Object skeleton=call(model,"double","1612003613");
                NativePose pose=skeleton==null?null:new NativePose(skeleton,get(skeleton,"double"));
                if(grip)support=mount.compose(bone(pose,new V(0,-.055,0),"grip","handle","grip_jnt","handle_jnt")).p();
                if(action==Action.LASER){
                    Pose emitter=laserEmitter(model,pose);
                    if(emitter!=null){laserMount=mount.compose(emitter);contacts.add(new Contact(action,mount));}
                }else if(action!=null)contacts.add(new Contact(action,mount));
            }
            if(sight!=null&&sightSlot==null)contactFromBone(rest,null,Action.ZOOM);
            Object butt=get(type("1536296909"),"BUTT");
            String stock=(String)get(butt,"assert"),fold=(String)get(butt,"switch");
            if(rest.index.containsKey(stock)&&rest.index.containsKey(fold)){
                stockBone=stock;foldedBone=fold;
                contacts.add(new Contact(Action.STOCK,rest.sample(stockBone)));
            }
            return true;
        }finally{
            set(provider,"throws",saved);
        }
    }

    private Pose mount(Object part,Object slot,Object nativePose)throws Exception {
        call(type("889335306"),"if","void");
        try{
            Object matrix=get(type("889335306"),"implements");
            call(matrix,"native","447252483");
            if(!(boolean)call(provider,"const","boolean",source,part,slot,nativePose,""))return null;
            float[] values=new float[16];
            call(matrix,"void","[F",(Object)values);
            return OpticMath.matrix(values);
        }finally{
            call(type("889335306"),"default","void");
        }
    }

    private void contactFromBone(NativePose rest,Object slot,Action action)throws Exception {
        String name=slot==null?"sight":(String)get(slot,"assert");
        if(rest.index.containsKey(name))contacts.add(new Contact(action,rest.sample(name)));
    }

    private static Pose bone(NativePose pose,V offset,String... names)throws Exception {
        if(pose!=null)for(String name:names)if(pose.index.containsKey(name))return pose.sample(name);
        return new Pose(offset,Q.ID);
    }

    private static Pose laserEmitter(Object model,NativePose pose)throws Exception {
        if(pose!=null)for(String name:new String[]{"laser_end","laser_emitter","laser"}){
            if(pose.index.containsKey(name))return pose.sample(name);
        }
        Object geometry=call(model,"final","530006582");
        if(geometry==null)return null;
        for(Object part:(Iterable<?>)call(geometry,"continue","java.util.ArrayList")){
            String name=(String)type("1639883046").getField("char").get(part);
            if(name.toLowerCase(java.util.Locale.ROOT).contains("laser"))return new Pose(new V(0,0,-.035),Q.ID);
        }
        return null;
    }

    boolean toggle(GameSession game,V hand)throws Exception {
        V local=game.result.weapon().inverse().transform(hand);
        Contact nearest=null;
        double range=.11;
        for(Contact c:contacts){
            double distance=c.pose().p().sub(local).length();
            if(distance<range){nearest=c;range=distance;}
        }
        if(nearest==null)return false;
        switch(nearest.action()){
            case LAUNCHER -> call(type("366373100"),"interface","void",game.player);
            case ZOOM -> call(game.component,"void","void");
            case LASER -> laserEnabled=!laserEnabled;
            case STOCK -> folded=!folded;
        }
        return true;
    }

    void updateVisuals(GameSession game)throws Exception {
        beamStart=beamEnd=null;
        if(!laserEnabled||laserMount==null||!game.gun.held||game.result==null||game.headBlocked)return;
        Pose emitter=game.result.weapon().compose(laserMount);
        V start=emitter.p(),direction=game.result.weapon().forward();
        V end=lineEnd(game.player,start,start.add(direction.mul(32)));
        if(start.finite()&&end.finite()){beamStart=start;beamEnd=end;}
    }

    void stock(NativePose pose)throws Exception {
        if(folded&&stockBone!=null&&pose.index.containsKey(stockBone)&&pose.index.containsKey(foldedBone))
            pose.transform(stockBone,pose.sample(foldedBone));
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
