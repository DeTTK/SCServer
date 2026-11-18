package scvehicles;
import java.util.*;
import static scvehicles.NativeAccess.*;

/** Read-only queries against the actual voxel floor and registered block names. */
final class NativeWheelSurface implements WheelEffects.Ground,VehicleSoundScene.Acoustics {
    private final Object world;
    private final VehiclePlatform platform;
    private final VehicleBody body;
    private final Map<Integer,WheelEffects.Surface> cache=new HashMap<>();
    NativeWheelSurface(Object player,Object world,VehiclePlatform platform)throws Exception{this.world=world;this.platform=platform;body=new VehicleBody(player);}
    public void vehicle(UUID id,boolean suppress)throws Exception{platform.suppress(id,suppress);}
    public VehicleSoundScene.Acoustic sample(scvehicles.protocol.VehicleWire s,double[] listener)throws Exception{
        platform.suppress(s.id,true);
        try{
            VehicleFleet.Type type=VehicleFleet.get(s.model);WheelEffects.Surface surface=WheelEffects.Surface.HARD;
            double[] q=VehiclePose.quaternion(s.yaw,s.pitch,s.roll);
            for(int i:new int[]{0,type.wheels.length-1}){double[] wheel=type.wheels[i],r=VehiclePose.rotate(q,wheel[0],-type.cg,wheel[2]);WheelEffects.Sample at=at(s.x+r[0],s.y+type.cg+r[1],s.z+r[2]);if(at!=null){surface=at.surface();if(surface==WheelEffects.Surface.WATER)break;}}
            return new VehicleSoundScene.Acoustic(surface,body.obstructed(listener,new double[]{s.x,s.y+type.height*.4,s.z}));
        }finally{platform.suppress(s.id,false);}
    }
    public WheelEffects.Sample at(double x,double y,double z)throws Exception{
        int ix=(int)Math.floor(x),iz=(int)Math.floor(z),iy=(int)Math.floor(y+.15);
        int above=(Integer)call(world,"strictfp","int",ix,iy,iz);
        if(classify(above)==WheelEffects.Surface.WATER)return new WheelEffects.Sample(WheelEffects.Surface.WATER,iy+.88);
        double h=body.rawGround(x,z,y+.4,y-.6,.055);if(!Double.isFinite(h))return null;
        int id=(Integer)call(world,"strictfp","int",ix,(int)Math.floor(h-.035),iz);
        return new WheelEffects.Sample(classify(id),h);
    }
    WheelEffects.Surface classify(int id)throws Exception{
        WheelEffects.Surface found=cache.get(id);if(found!=null)return found;
        String label="";Class<?> blockType=Class.forName("1959919792");Object[] blocks=(Object[])get(blockType,"switch");
        // Subclasses reuse these obfuscated field names for unrelated data,
        // including float[] "private". Read the base block's material and names.
        if(id>=0&&id<blocks.length&&blocks[id]!=null){Object b=blocks[id];label=String.valueOf(get(b,blockType,"bd"))+" "+String.valueOf(get(b,blockType,"ay"));Object material=get(b,blockType,"private");if(material!=null)label+=" "+call(material,"new","java.lang.String");}
        found=classify(id,label);cache.put(id,found);return found;
    }
    static WheelEffects.Surface classify(int id,String name){
        String s=name.toLowerCase(Locale.ROOT);
        if(id==9||s.contains("water")||s.contains("вода"))return WheelEffects.Surface.WATER;
        if(id==78||id==80||s.contains("snow")||s.contains("снег"))return WheelEffects.Surface.SNOW;
        if(id==88||s.contains("mud")||s.contains("swamp")||s.contains("гряз"))return WheelEffects.Surface.MUD;
        if(id==12||s.contains("sand")||s.contains("песок"))return WheelEffects.Surface.SAND;
        if(id==13||s.contains("gravel")||s.contains("грав"))return WheelEffects.Surface.GRAVEL;
        if(id==2||id==3||id==60||id==110||s.contains("grass")||s.contains("dirt")||s.contains("soil")||s.contains("ground")||s.contains("земл"))return WheelEffects.Surface.DIRT;
        return WheelEffects.Surface.HARD;
    }
}
