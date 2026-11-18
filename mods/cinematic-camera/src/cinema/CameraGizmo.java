package cinema;

import imgui.*;
import imgui.flag.ImGuiHoveredFlags;
import static cinema.Pose.*;
import java.util.*;

/** Screen picking with world-space ray/axis and ray/plane dragging. */
final class CameraGizmo {
    boolean rotation,local=true;int drag=-1;
    Pose startShot;V axis,u,v,dragOrigin;double startParameter;
    static final V[] AXES={new V(1,0,0),new V(0,1,0),new V(0,0,1)};
    static final int[] COLORS={0xff6666ff,0xff66ee66,0xffffaa66};
    boolean dragging(){return drag>=0;}
    static double rayAxis(V origin,V direction,V centre,V axis) {
        V w=origin.sub(centre);double b=direction.dot(axis),den=1-b*b;
        return den<1e-5?Double.NaN:(w.dot(axis)-b*w.dot(direction))/den;
    }
    static V rayPlane(V origin,V direction,V centre,V normal) {
        double den=direction.dot(normal);if(Math.abs(den)<1e-6)return null;
        double t=centre.sub(origin).dot(normal)/den;return t<0?null:origin.add(direction.mul(t));
    }
    static double distance(float x,float y,CameraEditor.Point a,CameraEditor.Point b) {
        double dx=b.x()-a.x(),dy=b.y()-a.y(),den=dx*dx+dy*dy;
        double t=den<.01?0:Math.max(0,Math.min(1,((x-a.x())*dx+(y-a.y())*dy)/den));
        return Math.hypot(x-a.x()-t*dx,y-a.y()-t*dy);
    }
    static V ray(CinematicCamera c,float x,float y) {
        ImGuiViewport vp=ImGui.getMainViewport();float[] p=c.displayProjection;
        double nx=2*(x-vp.getPosX())/vp.getSizeX()-1,ny=1-2*(y-vp.getPosY())/vp.getSizeY();
        return c.displayPose.q().rotate(new V(nx/p[0],ny/p[5],-1)).unit();
    }
    static CameraEditor.Point point(CinematicCamera c,V p) {
        ImGuiViewport vp=ImGui.getMainViewport();CameraEditor.Point s=CameraEditor.projectUnclipped("",p,c.displayPose,c.displayProjection,vp.getSizeX(),vp.getSizeY());
        return s==null?null:new CameraEditor.Point("",s.x()+vp.getPosX(),s.y()+vp.getPosY(),s.depth());
    }
    static void line(CinematicCamera c,ImDrawList list,V a,V b,int color,float thickness) {
        var x=point(c,a);var y=point(c,b);if(x!=null&&y!=null)list.addLine(x.x(),x.y(),y.x(),y.y(),color,thickness);
    }
    static double handleLength(Pose view,Pose shot,float[] projection,float height){
        double depth=-view.q().inverse().rotate(shot.p().sub(view.p())).z();
        return Math.max(.02,Math.min(500,110*2*Math.max(.05,depth)/(height*Math.max(.01,Math.abs(projection[5])))));
    }
    static V[] body(Pose shot){
        V[] vertices=new V[8];
        for(int i=0;i<8;i++)vertices[i]=shot.p().add(shot.q().rotate(new V((i&1)==0?-.22:.22,(i&2)==0?-.14:.14,(i&4)==0?-.05:.32)));
        return vertices;
    }
    static void body(CinematicCamera c,ImDrawList list,Pose shot){
        V[] vertices=body(shot);
        int[][] faces={{0,1,3,2},{4,6,7,5},{0,4,5,1},{2,3,7,6},{0,2,6,4},{1,5,7,3}};
        // A shaded world-space camera body, drawn above the stock full-screen HUD.
        Arrays.sort(faces,Comparator.comparingDouble((int[] face)->{
            V centre=new V(0,0,0);for(int i:face)centre=centre.add(vertices[i]);
            return -centre.mul(.25).sub(c.displayPose.p()).length();
        }));
        for(int[] face:faces){
            CameraEditor.Point[] points=new CameraEditor.Point[4];boolean visible=true;
            for(int i=0;i<4;i++){points[i]=point(c,vertices[face[i]]);if(points[i]==null)visible=false;}
            if(!visible)continue;
            var a=points[0];var b=points[1];var d=points[2];var e=points[3];
            int fill=(face[0]==0&&face[1]==1)?0xff8b6853:0xff514235;
            list.addTriangleFilled(a.x(),a.y(),b.x(),b.y(),d.x(),d.y(),fill);
            list.addTriangleFilled(a.x(),a.y(),d.x(),d.y(),e.x(),e.y(),fill);
            for(int i=0;i<4;i++){var x=points[i];var y=points[(i+1)%4];list.addLine(x.x(),x.y(),y.x(),y.y(),0xffffd599,2);}
        }
        V previous=shot.p().add(shot.q().rotate(new V(.1,0,-.18)));
        for(int i=1;i<=24;i++){double angle=i*Math.PI*2/24;V next=shot.p().add(shot.q().rotate(new V(Math.cos(angle)*.1,Math.sin(angle)*.1,-.18)));line(c,list,previous,next,0xffeeeeee,3);previous=next;}
    }
    boolean draw(CinematicCamera c)throws Exception {
        if(c.displayPose==null||c.displayProjection==null||c.last==null||c.anchor==null)return false;
        ImDrawList list=ImGui.getWindowDrawList();Pose shot=c.last;
        double length=handleLength(c.displayPose,shot,c.displayProjection,ImGui.getMainViewport().getSizeY());
        // Camera body and actual field-of-view cone.
        double cone=.7,tan=Math.tan(Math.toRadians(c.rig.fov)*.5),aspect=ImGui.getMainViewport().getSizeX()/ImGui.getMainViewport().getSizeY();
        V[] corners=new V[4];for(int i=0;i<4;i++){corners[i]=shot.p().add(shot.q().rotate(new V(((i==0||i==3)?-1:1)*cone*tan*aspect,(i<2?1:-1)*cone*tan,-cone)));line(c,list,shot.p(),corners[i],0xff88eeff,1.5f);}
        for(int i=0;i<4;i++)line(c,list,corners[i],corners[(i+1)%4],0xff88eeff,1.5f);
        var centre=point(c,shot.p());if(centre!=null){list.addCircleFilled(centre.x(),centre.y(),7,0xffeeeeee);list.addText(centre.x()+12,centre.y()+12,0xff88eeff,"Камера — F: показать");}
        Q basis=local?(rotation?shot.q():c.rig.follow?c.anchor.q():Native.look(c.player)):Q.ID;
        float mx=ImGui.getMousePosX(),my=ImGui.getMousePosY();
        boolean blocked=ImGui.isWindowHovered(ImGuiHoveredFlags.AnyWindow)||ImGui.isAnyItemActive()||ImGui.getIO().getKeyAlt();
        int hot=-1;double nearest=10;
        for(int i=0;i<3;i++) {
            V worldAxis=basis.rotate(AXES[i]);int color=drag==i?0xff66ffff:COLORS[i];
            if(rotation) {
                V ru=basis.rotate(AXES[(i+1)%3]),rv=basis.rotate(AXES[(i+2)%3]);V previous=shot.p().add(ru.mul(length));
                for(int k=1;k<=64;k++){double angle=k*Math.PI*2/64;V next=shot.p().add(ru.mul(Math.cos(angle)*length)).add(rv.mul(Math.sin(angle)*length));var a=point(c,previous);var b=point(c,next);
                    if(a!=null&&b!=null){list.addLine(a.x(),a.y(),b.x(),b.y(),color,2);double d=distance(mx,my,a,b);if(d<nearest){nearest=d;hot=i;}}
                    previous=next;
                }
            } else {
                var end=point(c,shot.p().add(worldAxis.mul(length)));
                if(centre!=null&&end!=null){list.addLine(centre.x(),centre.y(),end.x(),end.y(),color,3);list.addCircleFilled(end.x(),end.y(),5,color);list.addText(end.x()+7,end.y()-7,color,"XYZ".substring(i,i+1));double d=distance(mx,my,centre,end);if(d<nearest){nearest=d;hot=i;}}
            }
        }
        if(!rotation&&centre!=null&&Math.hypot(mx-centre.x(),my-centre.y())<8)hot=3;
        if(drag<0&&!blocked&&hot>=0&&ImGui.isMouseClicked(0)) {
            drag=hot;startShot=shot;dragOrigin=shot.p();axis=hot==3?c.displayPose.q().rotate(new V(0,0,1)):basis.rotate(AXES[hot]);
            u=hot==3?c.displayPose.q().rotate(AXES[0]):basis.rotate(AXES[(hot+1)%3]);v=axis.cross(u).unit();
            V direction=ray(c,mx,my);
            if(rotation){V hit=rayPlane(c.displayPose.p(),direction,startShot.p(),axis);startParameter=hit==null?Double.NaN:Math.atan2(hit.sub(startShot.p()).dot(v),hit.sub(startShot.p()).dot(u));}
            else if(hot==3){V hit=rayPlane(c.displayPose.p(),direction,startShot.p(),axis);if(hit!=null)startShot=new Pose(hit,startShot.q());startParameter=0;}
            else startParameter=rayAxis(c.displayPose.p(),direction,startShot.p(),axis);
        }
        if(drag>=0) {
            if(!ImGui.isMouseDown(0)){drag=-1;return true;}
            V direction=ray(c,mx,my);Pose desired=null;
            if(rotation){V hit=rayPlane(c.displayPose.p(),direction,startShot.p(),axis);if(hit!=null&&Double.isFinite(startParameter)){double angle=Math.atan2(hit.sub(startShot.p()).dot(v),hit.sub(startShot.p()).dot(u));double delta=Math.toDegrees(angle-startParameter);if(ImGui.getIO().getKeyCtrl())delta=Math.rint(delta/5)*5;desired=new Pose(startShot.p(),Q.around(axis,delta).mul(startShot.q()));}}
            else if(drag==3){V hit=rayPlane(c.displayPose.p(),direction,startShot.p(),axis);if(hit!=null)desired=new Pose(dragOrigin.add(hit.sub(startShot.p())),startShot.q());}
            else {double t=rayAxis(c.displayPose.p(),direction,startShot.p(),axis);if(Double.isFinite(t)&&Double.isFinite(startParameter)){double delta=t-startParameter;if(ImGui.getIO().getKeyCtrl())delta=Math.rint(delta*10)/10;desired=new Pose(startShot.p().add(axis.mul(delta)),startShot.q());}}
            if(desired!=null){CameraEditor.setWorldPose(c,desired,c.anchor);c.last=null;}
            return true;
        }
        if(!blocked&&hot>=0){ImGui.setTooltip(rotation?"ЛКМ: вращать; Ctrl: шаг 5°":"ЛКМ: перемещать; Ctrl: шаг 0.1 м");return true;}
        return false;
    }
}
