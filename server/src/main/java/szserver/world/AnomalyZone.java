package szserver.world;

import com.google.gson.JsonObject;
import szserver.client.AnomalySettings;
import java.util.*;

/** Immutable worker-prepared instance. Geometry, definitions and NBT never decode on a tick owner. */
public record AnomalyZone(UUID id,String asset,String kind,World.Vec center,double radius,SceneCollision.Box bounds,
                          AnomalySettings settings,float damage,String channel,int period,int cooldown,int duration,
                          int targets,boolean disabledDamage,boolean disabledEffect,float[] affine,int revision,int maximumCooldown,int maximumDuration,double releaseRadius) {
    private static final Set<String> SPHERES=Set.of("electra","funnel","carousel","tramp","stasis","tesla","circus");
    public AnomalyZone{affine=affine.clone();}
    @Override public float[] affine(){return affine.clone();}
    /** Original 304663057.case(): length of the first basis column, with native float arithmetic. */
    public static float nativeScale(SceneObject o){float[] a=o.affine();return (float)Math.sqrt(a[0]*a[0]+a[1]*a[1]+a[2]*a[2]);}
    public static double radius(SceneRegistry.Definition d,SceneObject o){return Math.min(256,nativeScale(o)*switch(d.behaviour()){
        case "tesla"->number(d.definition(),"attack_area",5);case "funnel","stasis"->3;case "circus"->4;default->1.5;});}
    private static double releaseRadius(SceneRegistry.Definition d,SceneObject o){return d.behaviour().equals("carousel")?Math.min(256,nativeScale(o)*number(d.definition(),"remove_target_distance",5)):radius(d,o);}
    public static SceneCollision.Box indexBounds(SceneRegistry.Definition d,SceneObject o){double r=Math.max(radius(d,o),releaseRadius(d,o));var h=o.halfExtent();r=Math.max(r,Math.max(h.x(),Math.max(h.y(),h.z()))*4);return new SceneCollision.Box(o.x()-r,o.y()-r,o.z()-r,o.x()+r,o.y()+r,o.z()+r);}
    public static AnomalyZone from(ActorSceneObject descriptor){return from(descriptor,descriptor.bounds());}
    public static AnomalyZone from(ActorSceneObject descriptor,SceneCollision.Box feedback){var d=descriptor.definition();var o=descriptor.source();var j=d.definition();String k=d.behaviour();var settings=AnomalySettings.from(o.asset(),o.configuration());
        float damage=(float)switch(k){case "simple_anomaly"->number(j,"damage",0);case "puh"->number(j,"puh_damage",0);case "tesla"->number(j,"attack_damage_per_tick",8);default->-1;};
        String channel=switch(k){case "electra","tesla"->"ELECTROSHOCK";case "lighter","steam","circus"->"BURN";case "simple_anomaly"->text(j,"damage_type","CHEMICAL_BURN");case "puh"->"CHEMICAL_BURN";default->"TEAR";};
        int period=(int)(k.equals("puh")?number(j,"puh_damage_rate",10):number(j,"damage_rate",k.equals("mirror")?16:10));
        int cooldown=k.equals("tramp")?settings.cooldown():k.equals("electra")?(int)Math.ceil(number(j,"charge_cooldown",2250)/50):k.equals("tesla")?arrayTicks(j,"attack_cooldown",2500):60;
        int duration=k.equals("tesla")?arrayTicks(j,"attack_duration",1000):k.equals("stasis")?(int)number(j,"death_time",60):k.equals("funnel")?120:k.equals("carousel")?300:200;
        return new AnomalyZone(o.identity(),o.asset(),k,new World.Vec(o.x(),o.y(),o.z()),radius(d,o),feedback,settings,damage,channel,Math.max(1,period),Math.max(1,cooldown),Math.max(1,duration),Math.max(1,Math.min(128,(int)number(j,"max_targets",3))),bool(j,"disable_damage"),bool(j,"disable_effect"),o.affine(),o.hashCode(),k.equals("tesla")?arrayTicks(j,"attack_cooldown",2500,true):Math.max(1,cooldown),k.equals("tesla")?arrayTicks(j,"attack_duration",1000,true):Math.max(1,duration),releaseRadius(d,o));
    }
    private static int arrayTicks(JsonObject j,String key,int fallback){return arrayTicks(j,key,fallback,false);}
    private static int arrayTicks(JsonObject j,String key,int fallback,boolean maximum){var v=j.get(key);double millis=v!=null&&v.isJsonArray()&&!v.getAsJsonArray().isEmpty()?v.getAsJsonArray().get(maximum?v.getAsJsonArray().size()-1:0).getAsDouble():fallback;if(!Double.isFinite(millis)||millis<0||millis>3600000)throw new IllegalArgumentException("anomaly_definition_"+key);return Math.max(1,(int)Math.ceil(millis/50));}
    private static double number(JsonObject j,String key,double fallback){var v=j.get(key);double n=v!=null&&v.isJsonPrimitive()&&v.getAsJsonPrimitive().isNumber()?v.getAsDouble():fallback;if(!Double.isFinite(n)||n<0||n>100000)throw new IllegalArgumentException("anomaly_definition_"+key);return n;}
    private static String text(JsonObject j,String key,String fallback){return j.has(key)?j.get(key).getAsString():fallback;}
    private static boolean bool(JsonObject j,String key){return j.has(key)&&j.get(key).getAsBoolean();}
    public boolean nearby(World.Vec from,World.Vec to,double width,double height){
        double r=Math.max(radius,releaseRadius);double x=Math.min(bounds.x(),center.x()-r),y=Math.min(bounds.y(),center.y()-r),z=Math.min(bounds.z(),center.z()-r),xx=Math.max(bounds.xx(),center.x()+r),yy=Math.max(bounds.yy(),center.y()+r),zz=Math.max(bounds.zz(),center.z()+r);
        return Math.max(from.x(),to.x())+width>=x&&Math.min(from.x(),to.x())-width<=xx&&Math.max(from.y(),to.y())+height>=y&&Math.min(from.y(),to.y())<=yy&&Math.max(from.z(),to.z())+width>=z&&Math.min(from.z(),to.z())-width<=zz;
    }
    public boolean touches(World.Vec from,World.Vec to,double width,double height){
        if(SPHERES.contains(kind)){
            double[] lo={-width,-height,-width},hi={width,0,width};boolean cylinder=kind.equals("carousel");
            if(cylinder){
                // Original carousel rotates/scales the entity's relative AABB and ignores vertical distance.
                double ex=(Math.abs(affine[0])+Math.abs(affine[6]))*width,ez=(Math.abs(affine[2])+Math.abs(affine[8]))*width;
                lo[0]=-ex-Math.max(0,affine[3]*height);hi[0]=ex-Math.min(0,affine[3]*height);
                lo[2]=-ez-Math.max(0,affine[5]*height);hi[2]=ez-Math.min(0,affine[5]*height);
            }
            return roundedBox(from,to,center,lo,hi,radius,cylinder);
        }
        var expanded=new SceneCollision.Box(bounds.x()-width,bounds.y()-height,bounds.z()-width,bounds.xx()+width,bounds.yy(),bounds.zz()+width);
        return SceneCollision.ray(from,new World.Vec(to.x()-from.x(),to.y()-from.y(),to.z()-from.z()),expanded,1)!=null;
    }
    /** Exact segment vs rounded AABB: partition at slab crossings, minimise each quadratic. */
    private static boolean roundedBox(World.Vec from,World.Vec to,World.Vec center,double[] lo,double[] hi,double radius,boolean cylinder){
        double[] p={from.x()-center.x(),from.y()-center.y(),from.z()-center.z()},v={to.x()-from.x(),to.y()-from.y(),to.z()-from.z()};
        double[] cuts=new double[8];int count=2;cuts[1]=1;
        for(int axis=0;axis<3;axis++)if(!(cylinder&&axis==1)&&v[axis]!=0)for(double edge:new double[]{lo[axis],hi[axis]}){double t=(edge-p[axis])/v[axis];if(t>0&&t<1)cuts[count++]=t;}
        Arrays.sort(cuts,0,count);double squared=radius*radius;
        for(int n=1;n<count;n++){double low=cuts[n-1],high=cuts[n],mid=(low+high)*.5,a=0,b=0,c=0;
            for(int axis=0;axis<3;axis++)if(!(cylinder&&axis==1)){double at=p[axis]+v[axis]*mid;double edge=at<lo[axis]?lo[axis]:at>hi[axis]?hi[axis]:Double.NaN;if(Double.isNaN(edge))continue;double offset=p[axis]-edge;a+=v[axis]*v[axis];b+=2*v[axis]*offset;c+=offset*offset;}
            double t=a==0?low:Math.max(low,Math.min(high,-b/(2*a)));if(a*t*t+b*t+c<=squared+1e-10)return true;
        }return false;
    }
    public boolean retains(World.Vec position){double dx=position.x()-center.x(),dy=position.y()-center.y(),dz=position.z()-center.z();return dx*dx+dy*dy+dz*dz<releaseRadius*releaseRadius;}
    public double[] impulse(){return new double[]{affine[3]*14.8*settings.strength(),affine[4]*14.8*settings.strength(),affine[5]*14.8*settings.strength()};}
}
