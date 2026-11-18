package btf;
public final class CameraMath {
    public static double smooth(double value,double target,double speed,double dt){return target+(value-target)*Math.exp(-speed*Math.max(0,Math.min(.1,dt)));}
    public static double mix(double a,double b,double t){return a+(b-a)*t;}
    public static double[] offset(double yaw,double pitch,double distance,double shoulder,double height){
        double y=Math.toRadians(yaw),p=Math.toRadians(pitch);
        return new double[]{Math.sin(y)*Math.cos(p)*distance-Math.cos(y)*shoulder,
            Math.sin(p)*distance+height,-Math.cos(y)*Math.cos(p)*distance-Math.sin(y)*shoulder};
    }
    public static double length(double[] a){return Math.sqrt(a[0]*a[0]+a[1]*a[1]+a[2]*a[2]);}
    public static double[] add(double[] a,double[] b,double scale){return new double[]{a[0]+b[0]*scale,a[1]+b[1]*scale,a[2]+b[2]*scale};}
    public static double[] subtract(double[] a,double[] b){return add(a,b,-1);}
    public static double[] unit(double[] v){double n=length(v);if(n<1e-9||!Double.isFinite(n))throw new IllegalArgumentException("Invalid aim direction");return new double[]{v[0]/n,v[1]/n,v[2]/n};}
    public static double[] converge(double[] origin,double[] target,double range){return add(origin,unit(subtract(target,origin)),range);}
    public static double safeFraction(double[] start,double[] end,double[] hit,double padding){
        double length=length(subtract(end,start));
        return length<1e-9?0:Math.max(0,Math.min(1,(length(subtract(hit,start))-padding)/length));
    }
}
