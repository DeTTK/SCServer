package scvehicles;

/** Short shaped-block edges are averaged over a tyre-sized contact patch. */
final class WheelSupport {
    interface Ground {
        double height(double x,double z)throws Exception;
        boolean ramp(double x,double height,double z)throws Exception;
    }
    record Contact(double height,boolean ramp) {}
    static Contact sample(Ground ground,double x,double z,double sn,double cs)throws Exception {
        double middle=ground.height(x,z),back=ground.height(x-sn*.24,z-cs*.24),front=ground.height(x+sn*.24,z+cs*.24);
        boolean ramp=isRamp(ground,x,middle,z)||isRamp(ground,x-sn*.24,back,z-cs*.24)||isRamp(ground,x+sn*.24,front,z+cs*.24);
        double envelope=middle;
        for(double h:new double[]{back,front})if(Double.isFinite(h)&&(!Double.isFinite(envelope)||h-.12>envelope))envelope=h-.12;
        if(!ramp||!Double.isFinite(middle))return new Contact(envelope,ramp);
        double sum=middle*4,weight=4;
        double[][] samples={{back,2},{front,2},
            {ground.height(x-sn*.48,z-cs*.48),1},{ground.height(x+sn*.48,z+cs*.48),1},
            {ground.height(x-cs*.24,z+sn*.24),2},{ground.height(x+cs*.24,z-sn*.24),2}};
        for(double[] a:samples)if(Double.isFinite(a[0])&&Math.abs(a[0]-middle)<.8){sum+=a[0]*a[1];weight+=a[1];}
        return new Contact(VehiclePhysics.clamp(sum/weight,middle-.22,middle+.22),true);
    }
    private static boolean isRamp(Ground ground,double x,double h,double z)throws Exception{return Double.isFinite(h)&&ground.ramp(x,h,z);}
}
