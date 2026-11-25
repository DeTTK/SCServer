package vr;
import static vr.NativeAccess.*;
import static vr.Math3.*;
import static org.lwjgl.opengl.GL33.*;
import java.util.*;

/** Equipped attachment, native mounting adapters, actual lens bounds and native reticle asset. */
final class NativeOptic {
    Pose lens;double rx,ry,zoom,recess;Object reticle;
    float noiseTime;
    String description="";
    private final Set<String> marks=new HashSet<>();
    private boolean tube,lookingThrough;
    void clear(){lens=null;reticle=null;marks.clear();tube=false;lookingThrough=false;recess=0;}
    Pose imagePlane(){return lens.compose(new Pose(new V(0,0,-recess),Q.ID));}
    OpticMath.View view(Pose eye){return lens==null?null:OpticMath.view(lens,rx,ry,zoom,recess,portal(),eye);}
    void eye(OpticMath.View view){lookingThrough=view!=null;}
    boolean portal(){
        return OpticMath.magnified(zoom)||tube;
    }
    void prepare(GameSession game,Object context,NativePose weapon)throws Exception {
        clear();noiseTime=(System.currentTimeMillis()%10000)/1000f;description="weapon not held";if(!game.gun.held||weapon==null)return;
        Object provider=call(game.item,"continue","168071319");description="provider="+(provider==null?"null":provider.getClass().getName());if(!type("289750496").isInstance(provider))return;
        Object definition=get(provider,"continue"),slot=call(definition,"char","1536296909",game.item);
        Object sight=slot==null?null:call(definition,"abstract","1710540327",game.item);description="sight="+sight+" slot="+slot;if(sight==null)return;
        zoom=((Number)call(sight,"return","float",game.item)).doubleValue();
        Object rifle=call(provider,"class","1304833431",game.item,true);description="rifle loading";if(rifle==null||!(boolean)call(rifle,"catch","boolean"))return;
        Object prototype=call(sight,"switch","1720634751",game.item),model=rifle;Pose mount=Pose.ID;String part="lens";
        if(prototype!=null){
            Object variant=call(type("986343158"),"finally","986343158",call(definition,"throws","java.lang.String",game.item));
            model=call(prototype,"catch","1304833431",variant);description="sight model loading";if(!(boolean)call(model,"catch","boolean"))return;
            Object saved=get(provider,"throws");call(type("889335306"),"if","void");
            try{
                set(provider,"throws",rifle);call(get(type("889335306"),"implements"),"native","447252483");
                description="attachment mount unavailable";if(!(boolean)call(provider,"const","boolean",game.item,sight,slot,weapon.pose,""))return;
                float[] matrix=new float[16];call(get(type("889335306"),"implements"),"void","[F",(Object)matrix);mount=OpticMath.matrix(matrix);
            }finally{set(provider,"throws",saved);call(type("889335306"),"default","void");}
        }
        Object geometry=call(model,"final","530006582"),mesh=null;
        // Reflex attachments often provide fp_collimator/not_aiming_lens instead
        // of lens. Native FP hides these by its ADS animation, which physical VR
        // aiming does not run. Discover the aperture from the equipped geometry.
        for(String name:new String[]{"lens","default_lens","fp_collimator","not_aiming_lens","default_not_aiming_lens"}){
            Object candidate=call(geometry,"try","1639883046",name);
            if(candidate!=null&&type("1639883046").getDeclaredField("do").get(candidate)!=null){mesh=candidate;part=name;break;}
        }
        description="aperture not found";if(mesh==null)return;
        // The concrete mesh has an integer field with the same obfuscated name.
        Object box=type("1639883046").getDeclaredField("do").get(mesh);
        double x0=number(box,"synchronized"),x1=number(box,"interface"),y0=number(box,"throw"),y1=number(box,"boolean"),z1=number(box,"do");
        rx=(x1-x0)/2;ry=(y1-y0)/2;
        description="aperture size="+rx+","+ry;if(!(rx>.002&&ry>.002&&rx<.12&&ry<.12))return;
        // +Z is the rear ocular surface, -Z is the AK muzzle direction.
        lens=game.result.weapon().compose(mount).compose(new Pose(new V((x0+x1)/2,(y0+y1)/2,z1+.0005),Q.ID));
        reticle=get(sight,"continue");
        Object materials=call(model,"byte","353540541");
        if(materials!=null){
            // Modern sights author their reticles in mesh materials, leaving the
            // old 1710540327.continue texture field empty. Keep the actual native
            // material, including its diffuse texture, colour and angular scale.
            for(Object candidate:(List<?>)call(geometry,"continue","java.util.ArrayList")){
                String name=(String)type("1639883046").getDeclaredField("char").get(candidate);
                String material=(String)type("1639883046").getDeclaredField("native").get(candidate);
                Object selected=call(materials,"transient","535595151",material);
                boolean collimator=((Map<?,?>)get(get(selected,"private"),"volatile")).containsKey("collimator");
                if(collimator||name.equals("fp_collimator")||name.equals("fp_optika")||name.equals("optika")){
                    marks.add(name);
                    if(name.equals("fp_optika")||name.equals("optika"))tube=true;
                    if(get(get(selected,"native"),"if")!=null)reticle=selected;
                }
            }
        }
        if(portal()){
            // The native lens is an ocular stencil, not the image surface.
            // Place our optical image inside the authored housing. Never move
            // the external glass or replace its native Fresnel/PBR material.
            double length=0;
            for(Object candidate:(List<?>)call(geometry,"continue","java.util.ArrayList")){
                String name=(String)type("1639883046").getDeclaredField("char").get(candidate);
                if(!name.equals("body")&&!name.equals("not_aiming_lens")&&!name.equals("default_not_aiming_lens"))continue;
                Object bounds=type("1639883046").getDeclaredField("do").get(candidate);
                if(bounds!=null)length=Math.max(length,number(bounds,"do")-number(bounds,"continue"));
            }
            recess=OpticMath.recess(length,Math.min(rx,ry));
        }
        description="zoom="+zoom+" aperture="+part+" lens="+rx+","+ry+" recess="+recess+" mount="+mount+" reticle="+reticle;
    }
    private static double number(Object box,String method)throws Exception {
        // Canonical 242639986 stores obfuscated double bits. Its public getters
        // expose those bits; native callers decode them before using bounds.
        long bits=Double.doubleToRawLongBits(((Number)call(box,method,"double")).doubleValue());
        return Double.longBitsToDouble(switch(method){
            case "synchronized" -> bits+0xd069d003942113a7L;
            case "throw","continue" -> bits+0x32e68c7cd6fd93c9L;
            case "interface" -> bits+0xf1cd099211abde89L;
            case "boolean" -> bits+0x8766900746bd0d71L;
            case "do" -> (bits+0x7a7a8ffe14d414aL)^0x78084ea73d22c536L;
            default -> throw new IllegalArgumentException(method);
        });
    }
    int reticleTexture()throws Exception {
        if(reticle==null)return 0;
        Object resource=type("535595151").isInstance(reticle)?get(get(reticle,"native"),"if"):reticle;
        Object texture=call(type("1705762298"),"abstract","722078779",resource,true,true);
        if(texture==null)return 0;
        // Reticle strokes disappear in the native 32px tail mip. The VR pass
        // bypasses native mesh screen-size requests, so request its authored mip.
        call(texture,"goto","void",0);
        // default() is the texture ID; strictfp() is its GL target, not an ID.
        // Resolve through the native cache without modifying its GL binding cache.
        int id=((Number)call(texture,"default","int")).intValue();
        return id>0&&glIsTexture(id)?id:0;
    }
    boolean replaces(Object mesh){
        if(lens==null)return false;
        // Native attachment draw may use a separately cached renderer/LOD of
        // the same resource. Its mesh objects need not share identity with the
        // aperture model. This hook is scoped to our owned FP rig draw only.
        try{
            String name=(String)type("1639883046").getDeclaredField("char").get(mesh);
            return marks.contains(name)||replacesSurface(name,lookingThrough);
        }catch(Exception e){return false;}
    }
    static boolean replacesSurface(String name,boolean lookingThrough){
        // Match native roles exactly: lens is the depth-only ADS stencil;
        // not_aiming_lens is the real exterior glass, hidden only while aiming.
        return switch(name){
            case "lens","default_lens","fp_collimator","fp_optika","optika" -> true;
            case "not_aiming_lens","default_not_aiming_lens" -> lookingThrough;
            default -> false;
        };
    }
}
