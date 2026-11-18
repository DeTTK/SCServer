package btf;
import szmods.api.*;
import java.nio.file.*;
import java.util.*;
import static btf.Native.*;
import static btf.CameraMath.*;

public final class BetterThirdFace implements ClientMod {
    ModContext context; Object game,player,camera,previousPlayer;
    Properties settings=new Properties();
    double ads,shoulder=1,dt=.016,collision=1; int side=1; boolean keyDown,active;
    public long cameraFrames,crosshairFrames;
    public double[] lastEye,lastCamera,lastShotOrigin,lastTarget;
    public double lastCrosshairX,lastCrosshairY,lastCollision=1;
    public float lastFov; public boolean lastHit;
    public double[] lastAimPoint,lastShotEnd;
    public boolean lastAimBlocked;
    public long correctedShots;
    public void onLoad(ModContext c)throws Exception {
        context=c;
        Path path=c.configDirectory().resolve("camera.properties");
        if(!Files.exists(path))Files.writeString(path,"""
            # BetterThirdFace: distances in metres, vertical FOV in degrees.
            startInThirdPerson=false
            hipDistance=2.65
            aimDistance=1.20
            hipShoulder=0.65
            aimShoulder=0.85
            hipHeight=0.18
            aimHeight=0.10
            hipFov=78
            aimFov=60
            aimSpeed=14
            shoulderSpeed=12
            # GLFW key code: X=88. Changes require a client restart.
            shoulderKey=88
            """);
        try(var reader=Files.newBufferedReader(path)){settings.load(reader);}
        // The shipped default is first person. Migrate the old generated default once.
        if ("true".equalsIgnoreCase(settings.getProperty("startInThirdPerson"))) {
            settings.setProperty("startInThirdPerson", "false");
            Files.writeString(path, settingsToText());
        }
        c.log("Camera, collision and ballistic reticle enabled; X switches shoulder, stock view key switches perspective.");
    }
    double cfg(String key,double fallback,double low,double high){
        try{double v=Double.parseDouble(settings.getProperty(key,""+fallback));return Double.isFinite(v)?Math.max(low,Math.min(high,v)):fallback;}catch(NumberFormatException e){return fallback;}
    }
    boolean back(Object cam)throws Exception{return cam!=null && "THIRD_PERSON_BACK".equals(call(cam,"goto","1308185038").toString());}
    public void onFrame(ClientFrame frame)throws Exception {
        game=frame.game();player=get(game,"e");dt=Math.max(.001,frame.deltaSeconds());
        active=false;
        if(player==null){previousPlayer=null;camera=null;lastCamera=null;ads=0;collision=1;return;}
        camera=call(player,"enum","1389510037");
        if(previousPlayer!=player){
            previousPlayer=player;lastCamera=null;ads=0;collision=1;
            if(Boolean.parseBoolean(settings.getProperty("startInThirdPerson","false")))
                call(camera,"abstract","void",get(type("1308185038"),"THIRD_PERSON_BACK"));
        }
        Object actual=call(type("495112732"),"byte","353488815");
        active=actual==camera&&(back(camera)||"FIRST_PERSON".equals(call(camera,"goto","1308185038").toString()));
        Object aim=call(type("1810277368"),"static","1810277368",player);
        boolean aiming=(boolean)call(aim,"new","boolean");
        ads=smooth(ads,active&&aiming?1:0,cfg("aimSpeed",14,2,40),dt);
        long window=(long)call(type("org.lwjgl.glfw.GLFW"),"glfwGetCurrentContext","long");
        boolean down=(boolean)call(type("877572775"),"interface","boolean",(int)cfg("shoulderKey",88,32,348));
        boolean pressed=(boolean)call(type("877572775"),"void","boolean",(int)cfg("shoulderKey",88,32,348));
        if((pressed||down&&!keyDown)&&active&&call(game,"float","622415611")==null
                &&(int)call(type("org.lwjgl.glfw.GLFW"),"glfwGetWindowAttrib","int",window,0x20001)==1)side=-side;
        keyDown=down;
        shoulder=smooth(shoulder,side,cfg("shoulderSpeed",12,2,40),dt);
        // Perspective is owned by the native aim system. BTF only supplies the
        // shoulder offset and collision-safe camera position; forcing a mode here
        // races the native RMB handler and can leave the camera stuck.
    }
    String settingsToText() {
        StringBuilder out=new StringBuilder(); settings.forEach((k,v)->out.append(k).append('=').append(v).append('\n')); return out.toString();
    }
    public Object onClientHook(String hook,Object... args)throws Exception {
        if(!active)return null;
        return switch(hook){
            case "camera.third-person.v1" -> args[0]==player&&"THIRD_PERSON_BACK".equals(args[4].toString())?position(args):null;
            case "camera.fov.v1" -> args[0]==camera?(lastFov=(float)mix(cfg("hipFov",78,40,110),cfg("aimFov",60,30,100),ads)):null;
            case "hud.crosshair.v1" -> reticle(args);
            case "weapon.aim.v1" -> aimShot(args);
            case "audio.local-player.v1" -> args[0]==player||args[0]==camera?Boolean.TRUE:null;
            default -> null;
        };
    }
    double[] position(Object[] args)throws Exception {
        double[] eye=xyz(args[1]);
        double[] offset=offset((float)args[2],(float)args[3],mix(cfg("hipDistance",2.65,.4,5),cfg("aimDistance",1.20,.3,3),ads),
            shoulder*mix(cfg("hipShoulder",.65,.1,1.2),cfg("aimShoulder",.85,.1,1.2),ads),mix(cfg("hipHeight",.18,-.3,.8),cfg("aimHeight",.10,-.3,.8),ads));
        double fraction=1;
        for(int i=0;i<9;i++) {
            double[] corner=i==8?new double[3]:new double[]{((i&1)*2-1)*.14,(((i>>1)&1)*2-1)*.14,(((i>>2)&1)*2-1)*.14};
            double[] start=add(eye,corner,1),end=add(start,offset,1);
            Object hit=ray(start,end);
            if(hit!=null)fraction=Math.min(fraction,safeFraction(start,end,xyz(call(hit,"while","153304200")),.07));
        }
        // Pull in immediately; release smoothly. Never smooth through an obstacle.
        collision=fraction<collision?fraction:smooth(collision,fraction,8,dt);
        lastEye=eye;lastCollision=collision;lastCamera=add(eye,offset,collision);cameraFrames++;
        return lastCamera;
    }
    Object ray(double[] start,double[] end)throws Exception {
        return call(type("94534399"),"synchronized","1473643318",false,player,vector(start),vector(end));
    }
    record AimSolution(double[] point,double[] end,double[] impact,boolean blocked){}
    double[] floatXYZ(Object v)throws Exception {
        return new double[]{((Number)get(v,"assert")).doubleValue(),((Number)get(v,"byte")).doubleValue(),((Number)get(v,"catch")).doubleValue()};
    }
    AimSolution solveAim(double[] origin,double range)throws Exception {
        Object near=type("1987590071").getConstructor().newInstance();
        Object direction=type("1987590071").getConstructor().newInstance();
        // Unproject the actual rendered centre; includes camera rotation/animation.
        call(get(camera,"int"),"default","447252483",.5f,.5f,new int[]{0,0,1,1},near,direction);
        double[] forward=unit(floatXYZ(direction));
        double[] cameraPosition=xyz(call(camera,"double","1738045740"));
        double[] far=add(cameraPosition,forward,Math.max(1000,range+10));
        Object cameraHit=ray(cameraPosition,far);
        double[] point=cameraHit==null?far:xyz(call(cameraHit,"while","153304200"));
        double[] toPoint=subtract(point,origin);
        // A camera-side obstruction behind the muzzle must never reverse a shot.
        if(toPoint[0]*forward[0]+toPoint[1]*forward[1]+toPoint[2]*forward[2]<.1)point=far;
        double[] end=converge(origin,point,range);
        Object muzzleHit=ray(origin,end);
        double[] impact=muzzleHit==null?end:xyz(call(muzzleHit,"while","153304200"));
        boolean blocked=muzzleHit!=null&&length(subtract(impact,origin))+.15<length(subtract(point,origin));
        return new AimSolution(point,end,impact,blocked);
    }
    void turnBodyTo(double[] origin,double[] target)throws Exception {
        double dx=target[0]-origin[0], dz=target[2]-origin[2];
        if(dx*dx+dz*dz<1e-8)return;
        float yaw=(float)Math.toDegrees(Math.atan2(dx,dz));
        Object graph=call(Class.forName("578131936"),"break","578131936",player);
        // 578131936.throws is the current body/view yaw field.  Write only
        // yaw; pitch and locomotion remain owned by the native graph.
        graph.getClass().getField("throws").setFloat(graph,yaw);
    }

    Object aimShot(Object[] args)throws Exception {
        if(args[0]!=player||lastCamera==null||!back(camera)||call(game,"float","622415611")!=null)return null;
        double[] origin=xyz(args[1]);
        double range=length(subtract(xyz(args[2]),origin));
        if(range<.01)return null;
        AimSolution aim=solveAim(origin,range);
        // Turn the local body toward the camera-selected target while retaining
        // the muzzle as the shot origin. This is the PUBG-style client-side
        // convergence; the server still validates the resulting ray.
        try{turnBodyTo(origin,aim.point());}catch(Exception ignored){}
        lastAimPoint=aim.point();lastShotEnd=aim.end();lastAimBlocked=aim.blocked();correctedShots++;
        return aim.end();
    }
    Object reticle(Object[] args)throws Exception {
        // First-person aim must use the stock reticle and stock spread presentation.
        if(lastCamera==null||call(game,"float","622415611")!=null)return null;
        if("FIRST_PERSON".equals(call(camera,"goto","1308185038").toString())) return null;
        Object clock=get(type("1402131154"),"h");
        float partial=(float)call(clock,"static","float")>0?(float)call(clock,"class","float"):1f;
        double[] origin=xyz(call(type("576005245"),"char","153304200",player,partial));
        Object eyeOffset=call(camera,"try","239386075");
        origin[1]+=((Number)call(eyeOffset,"interface","float")).doubleValue();
        Object weapon=call(type("1863158923"),"boolean","1863158923",player);
        float range=weapon==null?100f:(float)call(weapon,"throws","float");
        range=Math.max(1,Math.min(500,range));
        AimSolution aim=solveAim(origin,range);
        double[] target=aim.impact();boolean blocked=aim.blocked();
        lastAimPoint=aim.point();lastAimBlocked=blocked;
        lastShotOrigin=origin;lastTarget=target;lastHit=blocked;
        double[] cameraPosition=xyz(call(camera,"double","1738045740"));
        double[] relative=subtract(target,cameraPosition);
        Object vec=type("1987590071").getConstructor(float.class,float.class,float.class).newInstance((float)relative[0],(float)relative[1],(float)relative[2]);
        int w=(int)args[1],h=(int)args[2];
        Object projected=call(get(camera,"int"),"char","1987590071",vec,new int[]{0,0,w,h},type("1987590071").getConstructor().newInstance());
        double x=((Number)get(projected,"assert")).doubleValue(),y=h-((Number)get(projected,"byte")).doubleValue();
        if(!blocked){x=w*.5;y=h*.5;}
        if(!Double.isFinite(x)||!Double.isFinite(y))return null;
        x=Math.max(8,Math.min(w-8,x));y=Math.max(8,Math.min(h-8,y));lastCrosshairX=x;lastCrosshairY=y;
        double spread=weapon==null?0:(float)call(weapon,"for","float");
        // Reuse the HUD's own crosshair renderer, including its depth/blend conventions.
        // Only its anchor changes, and is restored before other HUD elements run.
        Object anchor=call(type("1600819111"),"public","921590923");
        var ax=anchor.getClass().getField("import");var ay=anchor.getClass().getField("try");
        float oldX=ax.getFloat(anchor),oldY=ay.getFloat(anchor);
        float sx=(float)w/(int)call(game,"while","int"),sy=(float)h/(int)call(game,"throws","int");
        try {
            ax.setFloat(anchor,(float)x/sx);ay.setFloat(anchor,(float)y/sy);
            if(weapon==null)call(args[0],"int","void",(float)x,(float)y,1f,blocked?.65f:1f,blocked?.35f:1f,1f);
            else call(args[0],"void","void",1f,blocked?.65f:1f,blocked?.35f:1f,1f,(float)Math.max(.03,spread),sx,sy,true);
        }finally{ax.setFloat(anchor,oldX);ay.setFloat(anchor,oldY);}
        call(args[0],"try","void",get(type("652978842"),"CROSSHAIRS"));
        crosshairFrames++;return Boolean.TRUE;
    }
    public void onUnload(){if(context!=null)context.log("Stopped; camera frames="+cameraFrames+", reticle frames="+crosshairFrames);}
}

