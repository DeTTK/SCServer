package scvehicles;
import scvehicles.protocol.VehicleWire;
import java.nio.*;
import java.util.*;
import static org.lwjgl.opengl.GL33.*;
import static scvehicles.NativeAccess.*;



/** Articulated stock Niva rendered into the native scene depth buffer. */
final class VehicleScene {
    private int program;
    private final VehicleLighting lighting=new VehicleLighting();
    private record Draw(VehicleModel.Mesh mesh,VehicleArticulation parts,VehicleFleet.Type type,double[] q,double[] offset,double depth,float[] light) {}
    private long glassDraws;
    private final WheelEffectRenderer effectsRenderer=new WheelEffectRenderer();
    private long lastFrame;
    private final Map<UUID,VehicleArticulation> animation=new LinkedHashMap<>();
    private final Map<Integer,VehicleModel> models=new LinkedHashMap<>(8,.75f,true);
    private final float[] view=new float[16],projection=new float[16];

    void render(Object camera,Collection<VehicleWire> replicas,WheelEffects effects,VehicleRider rider)throws Exception {
        if(replicas.isEmpty()&&effects.particles.isEmpty())return;
        int oldProgram=glGetInteger(GL_CURRENT_PROGRAM),oldVao=glGetInteger(GL_VERTEX_ARRAY_BINDING),oldBuffer=glGetInteger(GL_ARRAY_BUFFER_BINDING);
        int oldActive=glGetInteger(GL_ACTIVE_TEXTURE);glActiveTexture(GL_TEXTURE0);int oldTexture=glGetInteger(GL_TEXTURE_BINDING_2D);glActiveTexture(GL_TEXTURE1);int oldEmission=glGetInteger(GL_TEXTURE_BINDING_2D);glActiveTexture(GL_TEXTURE2);int oldTrack=glGetInteger(GL_TEXTURE_BINDING_2D);glActiveTexture(GL_TEXTURE0);
        int srcRGB=glGetInteger(GL_BLEND_SRC_RGB),dstRGB=glGetInteger(GL_BLEND_DST_RGB),srcAlpha=glGetInteger(GL_BLEND_SRC_ALPHA),dstAlpha=glGetInteger(GL_BLEND_DST_ALPHA),eqRGB=glGetInteger(GL_BLEND_EQUATION_RGB),eqAlpha=glGetInteger(GL_BLEND_EQUATION_ALPHA);
        int oldFramebuffer=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);int[] viewport=new int[4];glGetIntegerv(GL_VIEWPORT,viewport);
        Object target=call(Class.forName("449323257"),"break","105717861");
        int targetId=(Integer)call(target,"class","int");
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER,targetId);
        int[] drawBuffers=new int[glGetInteger(GL_MAX_DRAW_BUFFERS)];for(int i=0;i<drawBuffers.length;i++)drawBuffers[i]=glGetInteger(GL_DRAW_BUFFER0+i);
        boolean depth=glIsEnabled(GL_DEPTH_TEST),blend=glIsEnabled(GL_BLEND),cull=glIsEnabled(GL_CULL_FACE),mask=glGetBoolean(GL_DEPTH_WRITEMASK);
        boolean stencil=glIsEnabled(GL_STENCIL_TEST),scissor=glIsEnabled(GL_SCISSOR_TEST);
        boolean discard=glIsEnabled(GL_RASTERIZER_DISCARD);boolean[] clips=new boolean[glGetInteger(GL_MAX_CLIP_DISTANCES)];
        for(int i=0;i<clips.length;i++)clips[i]=glIsEnabled(GL_CLIP_DISTANCE0+i);
        ByteBuffer colorMask=ByteBuffer.allocateDirect(4);glGetBooleanv(GL_COLOR_WRITEMASK,colorMask);
        VehicleLighting.Scope lightingState=null;
        try {
            glDrawBuffer(GL_COLOR_ATTACHMENT0);glViewport(0,0,(Integer)call(target,"const","int"),(Integer)call(target,"case","int"));
            if(program==0)init();program=lighting.program();
            call(get(camera,"static"),"import","[F",(Object)view);
            call(get(camera,"throws"),"void","[F",(Object)projection);
            glUseProgram(program);lightingState=lighting.bind(camera,replicas);glUniformMatrix4fv(glGetUniformLocation(program,"view"),false,view);
            glUniformMatrix4fv(glGetUniformLocation(program,"projection"),false,projection);
            glUniform1i(glGetUniformLocation(program,"diffuse"),0);glUniform1i(glGetUniformLocation(program,"emission"),1);glUniform1i(glGetUniformLocation(program,"trackPath"),2);
            glEnable(GL_DEPTH_TEST);glDepthMask(true);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);
            glDisable(GL_STENCIL_TEST);glDisable(GL_SCISSOR_TEST);glColorMask(true,true,true,true);
            glDisable(GL_RASTERIZER_DISCARD);for(int i=0;i<clips.length;i++)glDisable(GL_CLIP_DISTANCE0+i);
            long now=System.nanoTime();double dt=lastFrame==0?0:Math.min(.1,(now-lastFrame)*1e-9);lastFrame=now;
            Set<UUID> live=new HashSet<>();for(VehicleWire e:replicas)live.add(e.id);animation.keySet().retainAll(live);
            Set<Integer> types=new HashSet<>();for(VehicleWire e:replicas)types.add(e.model);
            if(models.size()>6)for(var it=models.entrySet().iterator();it.hasNext()&&models.size()>6;){var e=it.next();if(!types.contains(e.getKey())){e.getValue().close();it.remove();}}
            List<Draw> draws=new ArrayList<>();
            for(VehicleWire e:replicas) {
                VehicleModel model=models.get(e.model);if(model==null){model=new VehicleModel();model.load(e.model);models.put(e.model,model);}
                VehicleFleet.Type type=VehicleFleet.get(e.model);
                VehicleArticulation parts=animation.computeIfAbsent(e.id,k->new VehicleArticulation());parts.update(e,dt,now*1e-9);
                List<VehicleActionsClient.Sample> actions=rider.actions==null?List.of():rider.actions.samples(e,now);
                for(var action:actions)if(action.progress()<1){double door=(e.flags&VehicleArticulation.DOORS_OPEN)!=0?1:action.door();parts.action(action.seat(),door);}
                double[] q=VehicleBody.quaternion(e.yaw,e.pitch,e.roll);
                double[] centre=VehiclePose.rotate(q,0,type.cg,0);
                double[] offset={e.x-centre[0],e.y+type.cg-centre[1],e.z-centre[2]};
                float[] light=lighting.sample(e,type,q);
                for(VehicleModel.Mesh mesh:model.meshes){
                    if(mesh.material.toLowerCase(Locale.ROOT).contains("glass")&&actions.stream().anyMatch(a->a.windowDown()&&mesh.part==VehicleGeometry.door(e.model,a.seat())))continue;
                    double[] c=VehiclePose.rotate(q,mesh.pivot[0],mesh.pivot[1],mesh.pivot[2]);
                    double viewDepth=view[2]*(offset[0]+c[0])+view[6]*(offset[1]+c[1])+view[10]*(offset[2]+c[2])+view[14];
                    draws.add(new Draw(mesh,parts,type,q,offset,viewDepth,light));
                }
            }
            glUniform1i(glGetUniformLocation(program,"transparentPass"),0);
            for(Draw draw:draws)if(!glass(draw.mesh))draw(draw);
            rider.render(camera);
            // Glass and source texture alpha need the opaque scene depth first.
            // Back-to-front transparent draws never write depth or obscure riders.
            glEnable(GL_BLEND);glBlendEquationSeparate(GL_FUNC_ADD,GL_FUNC_ADD);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);glDepthMask(false);
            glUniform1i(glGetUniformLocation(program,"transparentPass"),1);
            draws.sort(Comparator.comparingDouble(Draw::depth));
            for(Draw draw:draws){draw(draw);if(glass(draw.mesh)||draw.type.glassWindows!=null)glassDraws++;}
            glDepthMask(true);glDisable(GL_BLEND);
            effectsRenderer.render(effects,view,projection,(Integer)call(target,"case","int"));
        } finally {
            if(lightingState!=null)lightingState.close();
            glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,oldTexture);glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,oldEmission);glActiveTexture(GL_TEXTURE2);glBindTexture(GL_TEXTURE_2D,oldTrack);glActiveTexture(oldActive);
            glBindVertexArray(oldVao);glBindBuffer(GL_ARRAY_BUFFER,oldBuffer);glUseProgram(oldProgram);
            glBlendFuncSeparate(srcRGB,dstRGB,srcAlpha,dstAlpha);glBlendEquationSeparate(eqRGB,eqAlpha);
            toggle(GL_DEPTH_TEST,depth);toggle(GL_BLEND,blend);toggle(GL_CULL_FACE,cull);glDepthMask(mask);
            toggle(GL_STENCIL_TEST,stencil);toggle(GL_SCISSOR_TEST,scissor);
            toggle(GL_RASTERIZER_DISCARD,discard);for(int i=0;i<clips.length;i++)toggle(GL_CLIP_DISTANCE0+i,clips[i]);
            glColorMask(colorMask.get(0)!=0,colorMask.get(1)!=0,colorMask.get(2)!=0,colorMask.get(3)!=0);
            glDrawBuffers(drawBuffers);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,oldFramebuffer);glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
        }
    }
    private static boolean glass(VehicleModel.Mesh mesh){return mesh.material.toLowerCase(Locale.ROOT).contains("glass");}
    private void draw(Draw draw)throws Exception{
        lighting.grid(draw.light,draw.type);
        VehicleModel.Mesh mesh=draw.mesh;double[] q=draw.q,offset=draw.offset,rotation=draw.parts.rotation(mesh.part,mesh.radius);float[] pivot=mesh.pivot;
        if(mesh.trackSide!=0&&mesh.trackLoop==null)rotation=draw.parts.roller(mesh.trackSide,mesh.radius);
        glUniform1i(glGetUniformLocation(program,"movingTrack"),mesh.trackLoop==null?0:1);
        if(mesh.trackLoop!=null){
            glUniform1f(glGetUniformLocation(program,"trackLength"),(float)mesh.trackLoop.length);
            glUniform1f(glGetUniformLocation(program,"trackTravel"),(float)Math.IEEEremainder(draw.parts.trackDistance(mesh.trackSide),mesh.trackLoop.length));
            glActiveTexture(GL_TEXTURE2);glBindTexture(GL_TEXTURE_2D,mesh.trackTexture);glActiveTexture(GL_TEXTURE0);
        }
        glUniform3f(glGetUniformLocation(program,"offset"),(float)offset[0],(float)offset[1],(float)offset[2]);
        glUniform4f(glGetUniformLocation(program,"rotation"),(float)q[1],(float)q[2],(float)q[3],(float)q[0]);
        glUniform4f(glGetUniformLocation(program,"partRotation"),(float)rotation[1],(float)rotation[2],(float)rotation[3],(float)rotation[0]);
        glUniform3f(glGetUniformLocation(program,"pivot"),pivot[0],pivot[1],pivot[2]);
        glUniform1i(glGetUniformLocation(program,"glass"),glass(mesh)?1:0);
        // Native BRDM/T-72 body alpha is a skin mask. Only the T-72 fence
        // material opts into coverage transparency in its original MCMTL.
        boolean paintMask=draw.type.id==14||draw.type.id==18&&!mesh.material.equals("setk");
        glUniform1i(glGetUniformLocation(program,"paintMask"),paintMask?1:0);
        glUniform1f(glGetUniformLocation(program,"diffuseTint"),paintMask?.83032f:1f);
        double[][] windows=draw.type.glassWindows;int count=windows==null?0:windows.length;
        glUniform1i(glGetUniformLocation(program,"glassCount"),count);
        for(int i=0;i<count;i++){double[] b=windows[i];glUniform3f(glGetUniformLocation(program,"glassMin["+i+"]"),(float)b[0],(float)b[1],(float)b[2]);glUniform3f(glGetUniformLocation(program,"glassMax["+i+"]"),(float)b[3],(float)b[4],(float)b[5]);}
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,mesh.texture);
        glBindVertexArray(mesh.vao);glDrawArrays(GL_TRIANGLES,0,mesh.count);
    }
    private static void toggle(int flag,boolean value){if(value)glEnable(flag);else glDisable(flag);}
    private void init() throws Exception {lighting.init();program=lighting.program();}
    Map<String,Object> inspect(){var out=new LinkedHashMap<String,Object>();out.put("glassDraws",glassDraws);out.put("lighting",lighting.inspect());out.put("wheelEffects",effectsRenderer.inspect());out.put("parts",models.values().stream().flatMap(m->m.meshes.stream()).map(m->m.name).toList());out.put("models",models.keySet().stream().toList());out.put("vehicles",animation.entrySet().stream().map(e->Map.of("id",e.getKey().toString(),"animation",e.getValue().inspect())).toList());return out;}
    void reset(){animation.clear();lighting.reset();lastFrame=0;}
    void close()throws Exception{reset();effectsRenderer.close();for(VehicleModel model:models.values())model.close();models.clear();lighting.close();program=0;}
}
