package vr;
import java.util.*;
import static vr.NativeAccess.*;
import static vr.Math3.*;

/** Visual props carry no inventory ownership or damage; only server replies spend ammo. */
final class WorldDebris {
    record Item(Object model,NativePose mesh,DebrisPhysics body,long expires,WeaponSpec spec,boolean magazine){}
    final ArrayDeque<Item> items=new ArrayDeque<>();
    private final VrAudio audio;
    WorldDebris(VrAudio audio){this.audio=audio;}
    private Object owner;
    private long lastTick,handTick;
    private Pose previousLeft,previousMain;
    private V leftVelocity=V.ZERO,mainVelocity=V.ZERO;
    private long mainTick;
    private int previousHand=-1;
    private final NativeAk15 fallback=new NativeAk15();
    void tick(long now,Object player,Tracking tracking,int hand)throws Exception {
        if(owner!=player){clear();owner=player;}
        if(player==null)return;
        if(hand!=previousHand){previousHand=hand;previousLeft=previousMain=null;leftVelocity=mainVelocity=V.ZERO;handTick=mainTick=now;}
        double elapsed=lastTick==0?0:(now-lastTick)*1e-9;
        double handElapsed=(now-handTick)*1e-9;
        if(tracking!=null&&tracking.focused()&&(hand==0?tracking.left():tracking.right()).valid()){Pose next=(hand==0?tracking.left():tracking.right()).grip();if(previousLeft!=null&&handElapsed>.001&&handElapsed<.2){V speed=next.p().sub(previousLeft.p()).mul(1/handElapsed);leftVelocity=speed.length()>8?speed.unit().mul(8):speed;}previousLeft=next;handTick=now;}
        if(tracking!=null&&tracking.focused()&&(hand==0?tracking.right():tracking.left()).valid()){Pose next=(hand==0?tracking.right():tracking.left()).grip();double dt=(now-mainTick)*1e-9;if(previousMain!=null&&dt>.001&&dt<.2){V speed=next.p().sub(previousMain.p()).mul(1/dt);mainVelocity=speed.length()>8?speed.unit().mul(8):speed;}else mainVelocity=V.ZERO;previousMain=next;mainTick=now;}else{previousMain=null;mainVelocity=V.ZERO;}
        items.removeIf(item->now>=item.expires());
        if(elapsed>=1./60){for(Item item:items){int contacts=item.body().contacts;item.body().step(elapsed,(from,to)->clearProp(player,from,to,item.body().radius));if(contacts==0&&item.body().contacts>0)audio.play(item.spec(),"impact");}lastTick=now;}
        else if(lastTick==0)lastTick=now;
    }
    void magazine(NativeAk15 profile,Pose pose,long now)throws Exception {
        if(pose==null)return;
        NativePose mesh=profile.magazine();String root=profile.spec.magazineBone;Pose centre=mesh.sample(root);mesh.transform(root,Pose.ID);
        boolean shell=profile.spec.singleRounds();
        add(new Item(profile.model,mesh,new DebrisPhysics(pose.compose(centre),leftVelocity,new V(2,3,1),shell?.014:.055),now+(shell?30_000_000_000L:120_000_000_000L),profile.spec,!shell));
    }
    void eject(NativeAk15 profile,Pose weapon,boolean spent,long now)throws Exception {
        if(profile.spec.holdsCharge()||profile.spec.mechanism==WeaponSpec.Mechanism.ROCKET||weapon==null)return;
        NativeAk15 sourceProfile=profile;String root=spent?profile.spec.shellBone:profile.spec.roundBone;
        if(!profile.propVisible(root)){fallback.select(profile.spec.pistol?WeaponSpec.FMG9:profile.spec.singleRounds()?WeaponSpec.KS23:WeaponSpec.AK15);if(!fallback.load())return;sourceProfile=fallback;root=spent?fallback.spec.shellBone:fallback.spec.roundBone;if(!fallback.propVisible(root))return;}
        NativePose mesh=new NativePose(sourceProfile.skeleton,sourceProfile.rest.pose);
        mesh.only(root);mesh.transform(root,Pose.ID);
        Pose source=weapon.compose(profile.spec.spawnBone.isEmpty()?new Pose(profile.mounts.bolt(),Q.ID):profile.rest.sample(profile.spec.spawnBone));
        V speed=weapon.q().rotate(new V(spent?2.4:2.1,spent?1.05:.8,.25)).add(mainVelocity);
        add(new Item(sourceProfile.model,mesh,new DebrisPhysics(source,speed,new V(5,11,3),.014),now+(spent?12_000_000_000L:30_000_000_000L),profile.spec,false));
    }
    void chamber(NativeAk15 profile,Pose weapon,Pose gate,int slot,boolean spent,long now)throws Exception {
        String root=spent?profile.spec.chamberCases()[slot]:profile.spec.chamberBones()[slot];NativeAk15 source=profile;
        if(root.isEmpty()||!source.propVisible(root)){fallback.select(WeaponSpec.KS23);if(!fallback.load())return;source=fallback;root=spent?source.spec.shellBone:source.spec.roundBone;if(!source.propVisible(root))return;}
        NativePose mesh=new NativePose(source.skeleton,source.rest.pose);mesh.only(root);mesh.transform(root,Pose.ID);
        if(spent&&root.equals("shell")&&mesh.index.containsKey("bullet"))mesh.hide("bullet");
        Pose origin=weapon.compose(gate).compose(profile.rest.sample(profile.spec.chamberBones()[slot]));
        V speed=weapon.q().mul(gate.q()).rotate(new V((slot==0?.12:-.12),.2,2.2)).add(mainVelocity);
        add(new Item(source.model,mesh,new DebrisPhysics(origin,speed,new V(5,7,3),.014),now+(spent?12_000_000_000L:30_000_000_000L),profile.spec,false));
    }
    void cylinder(NativeAk15 profile,Pose weapon,Pose gate,int live,int spent,long now)throws Exception {
        for(int i=0;i<Math.min(profile.spec.capacity,live+spent);i++){
            boolean casing=i>=live;String bone="bullet_"+(i+1);
            NativeAk15 source=profile;String root=casing?profile.spec.shellBone:profile.spec.roundBone;
            if(!source.propVisible(root)){fallback.select(WeaponSpec.AK15);if(!fallback.load())return;source=fallback;root=casing?source.spec.shellBone:source.spec.roundBone;}
            NativePose mesh=new NativePose(source.skeleton,source.rest.pose);mesh.only(root);mesh.transform(root,Pose.ID);
            Pose local=profile.rest.index.containsKey(bone)?profile.rest.sample(bone):new Pose(profile.mounts.magazine(),Q.ID);
            Pose origin=weapon.compose(gate).compose(local);
            V speed=weapon.q().rotate(new V((i-2)*.035,0,.45)).add(new V(0,-.35,0));
            add(new Item(source.model,mesh,new DebrisPhysics(origin,speed,new V(3+i,4,2),.014),now+(casing?12_000_000_000L:30_000_000_000L),profile.spec,false));
        }
    }
    private void add(Item item){int limit=item.magazine()?16:48;while(items.stream().filter(existing->existing.magazine()==item.magazine()).count()>=limit){Iterator<Item> it=items.iterator();while(it.hasNext())if(it.next().magazine()==item.magazine()){it.remove();break;}}items.addLast(item);}
    void clear(){items.clear();lastTick=handTick=mainTick=0;previousHand=-1;previousLeft=previousMain=null;leftVelocity=mainVelocity=V.ZERO;}
}
