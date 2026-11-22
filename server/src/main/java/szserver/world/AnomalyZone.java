package szserver.world;

import com.google.gson.JsonObject;
import szserver.client.AnomalySettings;
import java.util.*;

/** Immutable worker-prepared instance. Geometry, definitions and NBT never decode on a tick owner. */
public record AnomalyZone(UUID id,String asset,String kind,World.Vec center,double radius,SceneCollision.Box bounds,
                          AnomalySettings settings,float damage,String channel,int period,int cooldown,int duration,
                          int targets,boolean disabledDamage,boolean disabledEffect,float[] affine,int revision,int maximumCooldown,int maximumDuration) {
    private static final Set<String> SPHERES=Set.of("electra","funnel","carousel","tramp","stasis","tesla","circus");
    public AnomalyZone{affine=affine.clone();}
    @Override public float[] affine(){return affine.clone();}
    public static double radius(SceneRegistry.Definition d,SceneObject o){return Math.max(.01,Math.min(256,o.scale()*switch(d.behaviour()){
        case "tesla"->number(d.definition(),"attack_area",5);case "stasis"->3;case "carousel"->number(d.definition(),"remove_target_distance",5);case "circus"->4;default->1.5;}));}
    public static SceneCollision.Box indexBounds(SceneRegistry.Definition d,SceneObject o){double r=radius(d,o);var h=o.halfExtent();r=Math.max(r,Math.max(h.x(),Math.max(h.y(),h.z()))*4);return new SceneCollision.Box(o.x()-r,o.y()-r,o.z()-r,o.x()+r,o.y()+r,o.z()+r);}
    public static AnomalyZone from(ActorSceneObject descriptor){return from(descriptor,descriptor.bounds());}
    public static AnomalyZone from(ActorSceneObject descriptor,SceneCollision.Box feedback){var d=descriptor.definition();var o=descriptor.source();var j=d.definition();String k=d.behaviour();var settings=AnomalySettings.from(o.asset(),o.configuration());
        float damage=(float)switch(k){case "simple_anomaly"->number(j,"damage",0);case "puh"->number(j,"puh_damage",0);case "tesla"->number(j,"attack_damage_per_tick",8);default->-1;};
        String channel=switch(k){case "electra","tesla"->"ELECTROSHOCK";case "lighter","steam","circus"->"BURN";case "simple_anomaly"->text(j,"damage_type","CHEMICAL_BURN");case "puh"->"CHEMICAL_BURN";default->"TEAR";};
        int period=(int)(k.equals("puh")?number(j,"puh_damage_rate",10):number(j,"damage_rate",k.equals("mirror")?16:10));
        int cooldown=k.equals("tramp")?settings.cooldown():k.equals("electra")?(int)Math.ceil(number(j,"charge_cooldown",2250)/50):k.equals("tesla")?arrayTicks(j,"attack_cooldown",2500):60;
        int duration=k.equals("tesla")?arrayTicks(j,"attack_duration",1000):k.equals("stasis")?(int)number(j,"death_time",60):k.equals("funnel")?120:k.equals("carousel")?300:200;
        return new AnomalyZone(o.identity(),o.asset(),k,new World.Vec(o.x(),o.y(),o.z()),radius(d,o),feedback,settings,damage,channel,Math.max(1,period),Math.max(1,cooldown),Math.max(1,duration),Math.max(1,Math.min(128,(int)number(j,"max_targets",3))),bool(j,"disable_damage"),bool(j,"disable_effect"),o.affine(),o.hashCode(),k.equals("tesla")?arrayTicks(j,"attack_cooldown",2500,true):Math.max(1,cooldown),k.equals("tesla")?arrayTicks(j,"attack_duration",1000,true):Math.max(1,duration));
    }
    private static int arrayTicks(JsonObject j,String key,int fallback){return arrayTicks(j,key,fallback,false);}
    private static int arrayTicks(JsonObject j,String key,int fallback,boolean maximum){var v=j.get(key);double millis=v!=null&&v.isJsonArray()&&!v.getAsJsonArray().isEmpty()?v.getAsJsonArray().get(maximum?v.getAsJsonArray().size()-1:0).getAsDouble():fallback;if(!Double.isFinite(millis)||millis<0||millis>3600000)throw new IllegalArgumentException("anomaly_definition_"+key);return Math.max(1,(int)Math.ceil(millis/50));}
    private static double number(JsonObject j,String key,double fallback){var v=j.get(key);double n=v!=null&&v.isJsonPrimitive()&&v.getAsJsonPrimitive().isNumber()?v.getAsDouble():fallback;if(!Double.isFinite(n)||n<0||n>100000)throw new IllegalArgumentException("anomaly_definition_"+key);return n;}
    private static String text(JsonObject j,String key,String fallback){return j.has(key)?j.get(key).getAsString():fallback;}
    private static boolean bool(JsonObject j,String key){return j.has(key)&&j.get(key).getAsBoolean();}
    public boolean nearby(World.Vec from,World.Vec to,double width,double height){
        double x=Math.min(bounds.x(),center.x()-radius),y=Math.min(bounds.y(),center.y()-radius),z=Math.min(bounds.z(),center.z()-radius),xx=Math.max(bounds.xx(),center.x()+radius),yy=Math.max(bounds.yy(),center.y()+radius),zz=Math.max(bounds.zz(),center.z()+radius);
        return Math.max(from.x(),to.x())+width>=x&&Math.min(from.x(),to.x())-width<=xx&&Math.max(from.y(),to.y())+height>=y&&Math.min(from.y(),to.y())<=yy&&Math.max(from.z(),to.z())+width>=z&&Math.min(from.z(),to.z())-width<=zz;
    }
    public boolean touches(World.Vec from,World.Vec to,double width,double height){
        // Capture anomalies use their native spherical area; authored volumes retain their AABB.
        if(SPHERES.contains(kind)){
            double cx=center.x(),cy=center.y(),cz=center.z(),y=Math.max(to.y(),Math.min(cy,to.y()+height));
            double dx=to.x()-cx,dz=to.z()-cz,dy=y-cy,r=radius+width;
            if(dx*dx+dy*dy+dz*dz<=r*r)return true;
            double ax=from.x()-cx,ay=Math.max(from.y(),Math.min(cy,from.y()+height))-cy,az=from.z()-cz,bx=dx-ax,by=dy-ay,bz=dz-az,den=bx*bx+by*by+bz*bz,t=den==0?0:Math.max(0,Math.min(1,-(ax*bx+ay*by+az*bz)/den));return sq(ax+bx*t)+sq(ay+by*t)+sq(az+bz*t)<=r*r;
        }
        var expanded=new SceneCollision.Box(bounds.x()-width,bounds.y()-height,bounds.z()-width,bounds.xx()+width,bounds.yy(),bounds.zz()+width);
        return SceneCollision.ray(from,new World.Vec(to.x()-from.x(),to.y()-from.y(),to.z()-from.z()),expanded,1)!=null;
    }
    private static double sq(double x){return x*x;}
    public double[] impulse(){return new double[]{affine[3]*14.8*settings.strength(),affine[4]*14.8*settings.strength(),affine[5]*14.8*settings.strength()};}
}
