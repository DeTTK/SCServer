package vr;
import static vr.Math3.*;

/** The eye sees a physical aperture; zoom changes only the camera behind that aperture. */
final class OpticMath {
    record View(Pose camera,Fov fov,V eye,double transmission){}
    static boolean magnified(double zoom){return zoom>=2.4-1e-4;}
    static double recordingScore(View view,double rx,double ry){
        if(view==null||view.transmission()<=0)return 0;
        return 1+view.transmission()/(1+Math.hypot(view.eye().x()/rx,view.eye().y()/ry));
    }
    static int recordingEye(Pose lens,double rx,double ry,double zoom,Pose left,Pose right){if(lens==null)return 1;return recordingScore(view(lens,rx,ry,zoom,left),rx,ry)>recordingScore(view(lens,rx,ry,zoom,right),rx,ry)?0:1;}
    static View view(Pose lens,double rx,double ry,double zoom,Pose eye){
        return view(lens,rx,ry,zoom,0,magnified(zoom),eye);
    }
    static double recess(double housingLength,double radius){return Math.max(.008,Math.min(.035,Math.min(Math.max(.008,housingLength*.28),radius*1.5)));}
    static View view(Pose lens,double rx,double ry,double zoom,double recess,boolean tube,Pose eye){
        V local=lens.inverse().transform(eye.p());double d=local.z();
        if(!local.finite()||d<.035||d>1.2)return null;
        // Low-power reflex sights have a generous eye box, scopes have eye relief.
        double lateral=Math.hypot(local.x()/rx,local.y()/ry);
        if(tube&&(d>.32||lateral>2.2))return null;
        double transmission=tube?Math.min(1,(d-.035)/.015):1;
        double scale=magnified(zoom)?zoom:1;
        double distance=d+recess;
        Fov fov=new Fov(Math.atan((-rx-local.x())/(distance*scale)),Math.atan((rx-local.x())/(distance*scale)),Math.atan((ry-local.y())/(distance*scale)),Math.atan((-ry-local.y())/(distance*scale)));
        // Low-power tube sights contain opaque native FP image surfaces.
        // A 1:1 portal from the eye preserves the world rays exactly while our
        // replacement aperture removes those surfaces. Only >=2.4x narrows FOV.
        return new View(new Pose(magnified(zoom)?lens.transform(new V(0,0,-recess-.08)):eye.p(),lens.q()),fov,local,transmission);
    }
    static Pose matrix(float[] a){
        double trace=a[0]+a[5]+a[10],x,y,z,w;
        if(trace>0){double s=Math.sqrt(trace+1)*2;w=s/4;x=(a[6]-a[9])/s;y=(a[8]-a[2])/s;z=(a[1]-a[4])/s;}
        else if(a[0]>a[5]&&a[0]>a[10]){double s=Math.sqrt(1+a[0]-a[5]-a[10])*2;w=(a[6]-a[9])/s;x=s/4;y=(a[4]+a[1])/s;z=(a[8]+a[2])/s;}
        else if(a[5]>a[10]){double s=Math.sqrt(1+a[5]-a[0]-a[10])*2;w=(a[8]-a[2])/s;x=(a[4]+a[1])/s;y=s/4;z=(a[9]+a[6])/s;}
        else{double s=Math.sqrt(1+a[10]-a[0]-a[5])*2;w=(a[1]-a[4])/s;x=(a[8]+a[2])/s;y=(a[9]+a[6])/s;z=s/4;}
        return new Pose(new V(a[12],a[13],a[14]),new Q(x,y,z,w).unit());
    }
}
