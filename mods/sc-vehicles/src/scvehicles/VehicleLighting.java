package scvehicles;
import java.util.*;import java.io.*;import java.nio.charset.StandardCharsets;import java.util.function.Supplier;
import scvehicles.protocol.VehicleWire;
import static scvehicles.NativeAccess.*;import static org.lwjgl.opengl.GL43.*;

/** Uses the running client's lighting modules, shadow map and world light palette. */
final class VehicleLighting {
    private Object shader,lightVertex,lightFragment,fog;
    private Object game,world,renderer;
    private final Map<UUID,Grid> grids=new HashMap<>();
    private static final class Grid {long sampled;double x,y,z,yaw,pitch,roll;float[] light=new float[32];}
    private final float[] sun=new float[3];private float exposure;private int samples;
    private final Map<String,Object> audit=new LinkedHashMap<>();
    private String vertex,fragment;private boolean pointLights;
    private String text(Object module){try{return (String)call(module,"byte","java.lang.String");}catch(Exception e){throw new IllegalStateException("native vehicle lighting module",e);}}
    private static String resource(String name)throws IOException{try(var in=Objects.requireNonNull(VehicleLighting.class.getResourceAsStream("/scvehicles/"+name))){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
    private String header(){try{return (Boolean)get(Class.forName("1311607083"),"package")?"#version 430 core\n":"#version 330 core\n";}catch(Exception e){throw new IllegalStateException(e);}}
    void init()throws Exception{
        vertex=resource("vehicle-lit.vert");fragment=resource("vehicle-lit.frag");
        lightVertex=get(Class.forName("1084998687"),"interface");lightFragment=get(Class.forName("634030213"),"return");fog=get(Class.forName("249547747"),"else");
        Supplier<String> vs=()->header()+text(lightVertex)+"\n"+vertex;
        // Native graphics reloads invoke these suppliers again. Keep GPU buffer
        // bindings in sync with the POINT_LIGHTS variant actually compiled.
        Supplier<String> fs=()->{String light=text(lightFragment);pointLights=light.contains("#define POINT_LIGHTS 1");return header()+light+"\n"+text(fog)+"\n"+fragment;};
        shader=Class.forName("43254592").getConstructor(Supplier.class,Supplier.class,String.class).newInstance(vs,fs,"sc-vehicles/world-lighting");
    }
    int program()throws Exception{return (Integer)call(shader,"while","int");}
    Scope bind(Object camera,Collection<VehicleWire> cars)throws Exception{
        Scope saved=new Scope(pointLights);
        try{
            game=call(Class.forName("1402131154"),"try","1402131154");world=call(game,"throws","1054393445");renderer=call(game,"const","1535940098");
            int program=program();
            if(pointLights)call(get(renderer,"try"),"static","void");
            call(lightFragment,"public","void",shader,false);call(lightVertex,"public","void",shader,false);
            int fogBlock=glGetUniformBlockIndex(program,"fogStateBlock");if(fogBlock!=-1){glUniformBlockBinding(program,fogBlock,1);call(Class.forName("324057923"),"try","void");}
            Object origin=call(call(get(renderer,"byte"),"double","353488815"),"double","1738045740");
            glUniform3f(glGetUniformLocation(program,"cameraOrigin"),((Number)call(origin,"super","double")).floatValue(),((Number)call(origin,"long","double")).floatValue(),((Number)call(origin,"abstract","double")).floatValue());
            Object shadow=call(Class.forName("1243838467"),"continue","1183745312");call(shadow,"break","void",3);glUniform1i(glGetUniformLocation(program,"shadowMap"),3);
            if(Boolean.getBoolean("sz.vehicle.lighting.audit")){
                glGetUniformfv(program,glGetUniformLocation(program,"g_LightSunColor"),sun);exposure=glGetUniformf(program,glGetUniformLocation(program,"g_LightExposure"));
                float[] matrix=new float[16],originValue=new float[3],direction=new float[3];glGetUniformfv(program,glGetUniformLocation(program,"g_LightShadowMatrix"),matrix);glGetUniformfv(program,glGetUniformLocation(program,"cameraOrigin"),originValue);glGetUniformfv(program,glGetUniformLocation(program,"g_LightSunDir"),direction);audit.put("shadowMatrix",matrix);audit.put("origin",originValue);audit.put("sunDir",direction);audit.put("program",program);audit.put("boundProgram",glGetInteger(GL_CURRENT_PROGRAM));
                for(int unit:new int[]{3,11,12}){glActiveTexture(GL_TEXTURE0+unit);audit.put("texture"+unit,glGetInteger(unit==3?GL_TEXTURE_BINDING_2D:GL_TEXTURE_BINDING_CUBE_MAP));}glActiveTexture(GL_TEXTURE0);
            }
            Set<UUID> live=new HashSet<>();for(VehicleWire car:cars)live.add(car.id);grids.keySet().retainAll(live);glActiveTexture(GL_TEXTURE0);return saved;
        }catch(Throwable failure){saved.close();throw failure;}
    }
    float[] sample(VehicleWire car,VehicleFleet.Type type,double[] q)throws Exception{
        Grid g=grids.computeIfAbsent(car.id,k->new Grid());long now=System.nanoTime();
        if(g.sampled!=0&&now-g.sampled<100_000_000L&&Math.abs(car.x-g.x)+Math.abs(car.y-g.y)+Math.abs(car.z-g.z)<.15&&Math.abs(car.yaw-g.yaw)+Math.abs(car.pitch-g.pitch)+Math.abs(car.roll-g.roll)<.05)return g.light;
        float[] palette=(float[])call(get(renderer,"char"),"abstract","[F");
        for(int i=0;i<8;i++){
            double x=((i&1)==0?-.5:.5)*type.width,y=(i&2)==0?.12:type.height,z=((i&4)==0?-.5:.5)*type.length;
            double[] p=VehiclePose.rotate(q,x,y-type.cg,z);int wx=(int)Math.floor(car.x+p[0]),wy=(int)Math.floor(car.y+type.cg+p[1]),wz=(int)Math.floor(car.z+p[2]);int mixed=(Integer)call(world,"int","int",wx,wy,wz,0);int packed=(mixed>>4&15)|(mixed>>16&240);
            // Sparse custom chunks may omit skylight nibbles. The native height
            // map still identifies open sky; closed roofs retain their dark value.
            if((packed&240)==0&&(Boolean)call(world,"strictfp","boolean",wx,wy,wz))packed|=240;
            for(int c=0;c<3;c++)g.light[i*4+c]=palette[packed*3+c];g.light[i*4+3]=(packed>>4&15)/15f;samples++;
        }
        g.sampled=now;g.x=car.x;g.y=car.y;g.z=car.z;g.yaw=car.yaw;g.pitch=car.pitch;g.roll=car.roll;return g.light;
    }
    void grid(float[] samples,VehicleFleet.Type type)throws Exception{
        int p=program();glUniform4fv(glGetUniformLocation(p,"lightSamples"),samples);
        glUniform3f(glGetUniformLocation(p,"lightBoxMin"),(float)(-.5*type.width),.12f,(float)(-.5*type.length));glUniform3f(glGetUniformLocation(p,"lightBoxMax"),(float)(.5*type.width),(float)type.height,(float)(.5*type.length));
    }
    Map<String,Object> inspect(){var out=new LinkedHashMap<String,Object>();out.put("nativeModules",true);out.put("pointLights",pointLights);out.put("sunColor",sun.clone());out.put("exposure",exposure);out.put("worldSamples",samples);out.put("cachedVehicles",grids.size());if(Boolean.getBoolean("sz.vehicle.lighting.audit")){out.put("audit",audit);out.put("grids",grids.values().stream().map(g->g.light.clone()).toList());}return out;}
    void reset(){grids.clear();}
    void close()throws Exception{reset();if(shader!=null){Object compiler=get(Class.forName("703466785"),"static");((List<?>)get(compiler,"boolean")).remove(shader);call(shader,"switch","void");shader=null;}}
    static final class Scope implements AutoCloseable {
        final int active=glGetInteger(GL_ACTIVE_TEXTURE),uniformBuffer=glGetInteger(GL_UNIFORM_BUFFER_BINDING),fogBuffer=glGetIntegeri(GL_UNIFORM_BUFFER_BINDING,1);final boolean seamless=glIsEnabled(GL_TEXTURE_CUBE_MAP_SEAMLESS);final int[] units,targets,bindings;final AutoCloseable nativeState;final boolean points,lightBufferBound;final int storage,lights,indices;
        Scope(boolean points)throws Exception{
            this.points=points;lightBufferBound=points&&(Boolean)get(Class.forName("1481781475"),"switch");storage=points?glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING):0;lights=points?glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING,3):0;indices=points?glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING,4):0;
            nativeState=(AutoCloseable)call(get(Class.forName("1973624787"),"package"),"strictfp","1973624787");
            units=points?new int[]{3,11,12,17}:new int[]{3,11,12};targets=points?new int[]{GL_TEXTURE_2D,GL_TEXTURE_CUBE_MAP,GL_TEXTURE_CUBE_MAP,0x9009}:new int[]{GL_TEXTURE_2D,GL_TEXTURE_CUBE_MAP,GL_TEXTURE_CUBE_MAP};bindings=new int[units.length];
            for(int i=0;i<units.length;i++){glActiveTexture(GL_TEXTURE0+units[i]);bindings[i]=glGetInteger(i==0?GL_TEXTURE_BINDING_2D:i==3?0x900A:GL_TEXTURE_BINDING_CUBE_MAP);}glActiveTexture(active);
        }
        public void close()throws Exception{nativeState.close();for(int i=0;i<units.length;i++){glActiveTexture(GL_TEXTURE0+units[i]);glBindTexture(targets[i],bindings[i]);}glBindBufferBase(GL_UNIFORM_BUFFER,1,fogBuffer);glBindBuffer(GL_UNIFORM_BUFFER,uniformBuffer);if(points){glBindBufferBase(GL_SHADER_STORAGE_BUFFER,3,lights);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,4,indices);glBindBuffer(GL_SHADER_STORAGE_BUFFER,storage);Class.forName("1481781475").getField("switch").setBoolean(null,lightBufferBound);}if(seamless)glEnable(GL_TEXTURE_CUBE_MAP_SEAMLESS);else glDisable(GL_TEXTURE_CUBE_MAP_SEAMLESS);glActiveTexture(active);}
    }
}
