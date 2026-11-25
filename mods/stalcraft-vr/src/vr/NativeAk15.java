package vr;
import static vr.NativeAccess.*;
import static vr.Math3.*;

/** Resolves authored bones for the selected native weapon, with owned animation poses. */
final class NativeAk15 {
    WeaponSpec spec=WeaponSpec.AK15;
    Object model,skeleton;
    NativePose rest;
    Ak15.Mounts mounts;
    private final java.util.Map<String,Boolean> visibleProps=new java.util.HashMap<>();
    void select(WeaponSpec next){if(spec!=next){spec=next;model=skeleton=null;rest=null;mounts=null;visibleProps.clear();}}
    boolean propVisible(String root)throws Exception {if(root.isEmpty()||!rest.index.containsKey(root))return false;Boolean cached=visibleProps.get(root);if(cached!=null)return cached;NativePose pose=new NativePose(skeleton,rest.pose);pose.only(root);int n=0;for(Object data:(java.util.List<?>)get(get(model,"public"),"instanceof")){NativeMeshMask.Filter filter=NativeMeshMask.filter(data,pose.hidden);if(filter!=null)n+=filter.indices().length;}visibleProps.put(root,n>0);return n>0;}
    boolean load()throws Exception {
        if(rest!=null&&mounts!=null)return true;
        if(model==null){Class<?> resource=type("779455578");Object mesh=resource.getConstructor(String.class).newInstance("weapons:models/weapons/"+spec.model+".mcsa"),materials=resource.getConstructor(String.class).newInstance("weapons:models/weapons/"+spec.model+".mcmtl");model=type("303756944").getConstructor(resource,resource).newInstance(mesh,materials);}
        call(model,"while","303756944");
        skeleton=call(get(model,"public"),"strictfp","1612003613");if(skeleton==null)return false;
        rest=new NativePose(skeleton,get(skeleton,"double"));
        // Authored grip centres relative to confirmed handle/forend/magazine/zatvor/barrel bones.
        // Wrist mounts match the equipped AK-15 animation in rifle space.
        Pose grip=new Pose(rest.sample(spec.gripBone).p().add(spec.gripOffset),Q.ID);
        V feed=rest.sample(spec.loadingBone).p().add(spec.singleRounds()&&!spec.slotted()?new V(0,-.025,0):spec.mechanism==WeaponSpec.Mechanism.REVOLVER?spec.magazineOffset:spec.magazineAxis.mul(-spec.magazineOffset.y()));
        // Desert Eagle's authored barrel bone is already at the muzzle; it has no barrel_end.
        V muzzle=rest.sample(spec.muzzleBone).p();
        V[] chambers=new V[spec.chamberBones().length];for(int i=0;i<chambers.length;i++)chambers[i]=rest.sample(spec.chamberBones()[i]).p();
        V bolt=rest.sample(spec.boltBone).p().add(spec.boltOffset),leverPivot=rest.sample(spec.boltBone).p();
        if(spec.boltLiftAngle()>0)bolt=leverPivot.add(new V(.045,-.015,0));
        if(spec.mechanism==WeaponSpec.Mechanism.LEVER_TUBE){String end=spec.model.startsWith("winchester1901/")?"recharge_end":"lever_jnt_end";bolt=leverPivot.add(rest.sample(end).p().sub(leverPivot).mul(.8));}
        mounts=new Ak15.Mounts(grip.inverse(),rest.sample(spec.supportBone).p().add(spec.supportOffset),feed,bolt,muzzle,spec.stroke,spec.gate()?rest.sample(spec.gateBone).p():rest.sample(spec.boltBone).p(),spec.magazineAxis,new Pose(grip.p(),spec.gripRotation),chambers.length==0?new V[]{feed}:chambers,leverPivot);
        return true;
    }
    NativePose weapon(GameSession game)throws Exception {
        NativePose pose=new NativePose(skeleton,rest.pose);
        int magIndex=pose.index.get(spec.magazineBone);
        for(String bone:pose.index.keySet())if(bone.matches("(?i).*(magazine[2-9]|magazine_[2-9]|clip[1-9]).*")||bone.matches("(?i)(bullet.*|shell.*|casing.*|bul[0-9]*|cartridge.*|ammo.*)")){
            boolean inMagazine=pose.child(pose.index.get(bone),magIndex)&&!spec.singleRounds();
            if(!inMagazine||game.nativeAmmo==0)pose.hide(bone);
        }
        if(spec.slotted()){
            String[] live=spec.chamberBones(),cases=spec.chamberCases();
            for(int i=0;i<live.length;i++){pose.hide(live[i]);pose.hide(cases[i]);if((game.gun.chambers.live()&(1<<i))!=0)pose.show(live[i]);else if((game.gun.chambers.spent()&(1<<i))!=0&&!cases[i].isEmpty()){pose.show(cases[i]);if(cases[i].equals(live[i])&&pose.index.containsKey("bullet"))pose.hide("bullet");}}
        }
        if(!spec.singleRounds()&&(game.gun.magazine!=Ak15.Magazine.INSERTED||spec.mechanism==WeaponSpec.Mechanism.ROCKET&&game.nativeAmmo==0))pose.hide(spec.magazineBone);
        if(spec.gate()&&game.gun.actionProgress()>0){Pose delta=game.gun.gateTransform();pose.transform(spec.gateBone,delta.compose(rest.sample(spec.gateBone)));}
        if(spec.mechanism==WeaponSpec.Mechanism.REVOLVER){for(String bone:new String[]{"cylinder_jnt","clip_jnt"})if(pose.index.containsKey(bone)){Pose pivot=pose.sample(bone);pose.transform(bone,new Pose(pivot.p(),pivot.q().mul(Q.axis(new V(0,0,1),2*Math.PI*(spec.capacity-game.nativeAmmo)/spec.capacity))));}}
        if(spec.mechanism==WeaponSpec.Mechanism.ROTARY_MAG){Pose pivot=pose.sample(spec.boltBone);pose.transform(spec.boltBone,new Pose(pivot.p(),pivot.q().mul(Q.axis(new V(0,0,1),game.gun.drumAngle()))));}
        if(spec==WeaponSpec.GAUSS){pose.translate("mag_release",new V(0,-.007*game.gun.extractionProgress(),0));}
        if(game.result!=null&&game.result.boltTravel()>0){
            double travel=game.result.boltTravel();
            if(spec.mechanism!=WeaponSpec.Mechanism.CELL){if(spec.mechanism==WeaponSpec.Mechanism.LEVER_TUBE){Pose pivot=pose.sample(spec.boltBone);pose.transform(spec.boltBone,new Pose(pivot.p(),Q.axis(new V(1,0,0),Math.toRadians(spec.leverAngle())*travel/spec.stroke).mul(pivot.q())));}else pose.translate(spec.boltBone,new V(0,0,travel));}
            if(spec.model.startsWith("l96/")&&pose.index.containsKey("zatvor.001_jnt"))pose.translate("zatvor.001_jnt",new V(0,0,travel));
            if(spec.model.startsWith("pzb38/"))pose.translate("bolt_001",new V(0,-.01624*travel/spec.stroke,0));
            if(spec.pump()&&pose.index.containsKey("zatvor"))pose.translate("zatvor",new V(0,0,travel));
            if(spec==WeaponSpec.FMG9)pose.translate("bolt",new V(0,0,travel));
        }
        if(spec.boltLiftAngle()>0&&game.gun.boltLift()>0){Pose pivot=pose.sample(spec.boltLiftBone());pose.transform(spec.boltLiftBone(),new Pose(pivot.p(),Q.axis(new V(0,0,1),Math.toRadians(game.gun.boltLift())).mul(pivot.q())));}
        if(spec.pistol&&spec.mechanism!=WeaponSpec.Mechanism.REVOLVER){double travel=Math.max(0,game.gun.slideTravel()-(game.result==null?0:game.result.boltTravel()));if(travel>0)pose.translate(spec.boltBone,new V(0,0,travel));}
        return pose;
    }
    NativePose magazine()throws Exception {NativePose pose=new NativePose(skeleton,rest.pose);pose.only(spec.magazineBone);if(spec.singleRounds()){Pose shell=rest.sample(spec.magazineBone);pose.transform(spec.magazineBone,new Pose(mounts.magazine(),shell.q()));}return pose;}
}
