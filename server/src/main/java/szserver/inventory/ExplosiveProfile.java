package szserver.inventory;

import szserver.net.Wire;

/** Prepared canonical properties. Throwing/flight and damage resolution use a separate service. */
public record ExplosiveProfile(int item,String kind,float strength,float minimumStrength,float radius,float minimumRadius,
                               boolean onCollision,int activationTicks,boolean onGround,byte[] nativeProperties,boolean lowestPoint) {
    public ExplosiveProfile(int item,String kind,float strength,float minimumStrength,float radius,float minimumRadius,boolean onCollision,int activationTicks,boolean onGround,byte[] nativeProperties){this(item,kind,strength,minimumStrength,radius,minimumRadius,onCollision,activationTicks,onGround,nativeProperties,false);}
    public ExplosiveProfile {
        if(item<0||item>65535||kind==null||nativeProperties.length<1||nativeProperties.length>8192)throw new IllegalArgumentException("explosive_profile");
        for(float value:new float[]{strength,minimumStrength,radius,minimumRadius})if(!Float.isFinite(value)||value<0)throw new IllegalArgumentException("explosive_profile_value");
        nativeProperties=nativeProperties.clone();
    }
    @Override public byte[] nativeProperties(){return nativeProperties.clone();}
    public byte[] presentation(double x,double y,double z){
        for(double value:new double[]{x,y,z})if(!Double.isFinite(value)||Math.abs(value)>szserver.world.MapBounds.HORIZONTAL)throw new IllegalArgumentException("explosion_position");
        return Wire.bytes(out->{out.writeInt(item);out.writeBoolean(false);out.write(nativeProperties);out.writeDouble(x);out.writeDouble(y);out.writeDouble(z);out.writeBoolean(true);out.writeFloat(1);out.writeBoolean(false);out.writeBoolean(false);});
    }
}
