package playerscale;

/** Uniform dimensions relative to the actor's feet; never scales world coordinates or FOV. */
public record ScaleValue(float factor) {
    public static final float MIN=.25f,MAX=4f;
    public static final ScaleValue NORMAL=new ScaleValue(1);
    public ScaleValue {if(!Float.isFinite(factor)||factor<MIN||factor>MAX)throw new IllegalArgumentException("character_scale_range");}
    public double distance(double base){if(!Double.isFinite(base)||base<0)throw new IllegalArgumentException("character_scale_dimension");return base*factor;}
    /** Mob size N uses N/5. Keep fractional factors rather than rounding them to N. */
    public static ScaleValue nativeMobSize(int nativeSize){return new ScaleValue(nativeSize/5f);}
    public double[] point(double[] feet,double[] local){
        if(feet.length!=3||local.length!=3)throw new IllegalArgumentException("character_scale_point");
        double[] point=new double[3];for(int i=0;i<3;i++){if(!Double.isFinite(feet[i])||!Double.isFinite(local[i]))throw new IllegalArgumentException("character_scale_point");point[i]=feet[i]+local[i]*factor;}return point;
    }
    public double[] body(double[] feet,double nativeWidth,double nativeHeight){
        double half=distance(nativeWidth)*.5,height=distance(nativeHeight);point(feet,new double[3]);
        return new double[]{feet[0]-half,feet[1],feet[2]-half,feet[0]+half,feet[1]+height,feet[2]+half};
    }
}
