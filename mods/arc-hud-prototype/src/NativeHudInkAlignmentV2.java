import java.nio.*;
import java.lang.reflect.*;
import java.util.*;
import com.google.gson.*;
import static org.lwjgl.opengl.GL33.*;

/** Measures the actual native Text-widget pixels, then aligns their visible ink.
 * This corrects the native renderer's extra leading and font side bearings.
 * Only the native text draw position changes; the logical Figma cells stay fixed. Run once after layout/text changes.
 */
public final class NativeHudInkAlignmentV2 {
    static final int ATLAS=2048, PAD=48;
    static class Label {
        Object widget,style,graphics,originalPosition,originalOffset;
        String text,id,horizontal,vertical;Object opacity,node;
        int x,y,w,h,tileW,tileH,dx,dy;
        int[] before,after;
    }
    static Object call(Object o,String name,String result,Object...args)throws Exception {
        return HudNative.call(o,name,result,args);
    }
    static Object get(Object o,String name)throws Exception {
        return HudNative.get(o,name);
    }
    static Object pos(int x,int y)throws Exception {
        return Class.forName("1500420601").getConstructor(int.class,int.class).newInstance(x,y);
    }
    static int coord(Object position,String field)throws Exception {
        return position.getClass().getField(field).getInt(position);
    }
    static String enumName(Object e){return e==null?"":((Enum<?>)e).name();}

    static int atlasSize(Object gui,Set<String> ids)throws Exception {
        ArrayList<int[]> cells=new ArrayList<>();
        for(var entry:((Map<?,?>)get(gui,"continue")).entrySet()){
            Object widget=entry.getValue();if(!widget.getClass().getName().equals("1554818945")||ids!=null&&!ids.contains(entry.getKey().toString()))continue;
            String text=(String)get(widget,"return");if(text==null||text.isEmpty())continue;Object size=call(widget,"switch","61438557");cells.add(new int[]{((Number)get(size,"try")).intValue()+2*PAD,((Number)get(size,"enum")).intValue()+2*PAD});
        }
        for(int atlas=256;atlas<=ATLAS;atlas*=2){int x=0,y=0,row=0;boolean fits=true;for(int[] cell:cells){if(cell[0]>atlas||cell[1]>atlas){fits=false;break;}if(x+cell[0]>atlas){x=0;y+=row;row=0;}if(y+cell[1]>atlas){fits=false;break;}x+=cell[0];row=Math.max(row,cell[1]);}if(fits)return atlas;}
        throw new IllegalStateException("Native glyph atlas overflow");
    }
    public static JsonArray prepare(Object gui)throws Exception {return prepare(gui,null);}
    public static JsonArray prepare(Object gui,Set<String> ids)throws Exception {
        final int atlas=atlasSize(gui,ids);
        ArrayList<Label> labels=new ArrayList<>();
        int x=0,y=0,rowH=0;
        for(Object widget:((Map<?,?>)get(gui,"continue")).values()) {
            if(!widget.getClass().getName().equals("1554818945"))continue;
            if(ids!=null&&!ids.contains(get(call(widget,"else","1287567536"),"int").toString()))continue;
            String text=(String)get(widget,"return");
            if(text==null||text.isEmpty())continue;
            Label l=new Label();l.widget=widget;l.text=text;
            Object node=call(widget,"else","1287567536");
            l.id=get(node,"int").toString();l.style=call(node,"new","695281613");
            l.horizontal=enumName(call(l.style,"native","1351327104"));
            l.vertical=enumName(call(l.style,"const","780508252"));
            if(Set.of("svg-124","svg-137","svg-152","svg-171").contains(l.id)){l.horizontal="Center";l.vertical="Center";}
            Object size=call(widget,"switch","61438557");
            l.w=size.getClass().getField("try").getInt(size);
            l.h=size.getClass().getField("enum").getInt(size);
            l.tileW=l.w+2*PAD;l.tileH=l.h+2*PAD;
            if(x+l.tileW>atlas){x=0;y+=rowH;rowH=0;}
            if(y+l.tileH>atlas)throw new IllegalStateException("Native glyph atlas overflow");
            l.x=x;l.y=y;x+=l.tileW;rowH=Math.max(rowH,l.tileH);
            l.graphics=get(widget,"class");l.originalPosition=Class.forName("36549396").getField("final").get(widget);
            l.originalOffset=get(l.graphics,"strictfp");l.node=node;l.opacity=get(node,"if");labels.add(l);
        }
        int oldDraw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),oldRead=glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int[] viewport=new int[4];glGetIntegerv(GL_VIEWPORT,viewport);
        int oldTex=glGetInteger(GL_TEXTURE_BINDING_2D),oldPack=glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING),align=glGetInteger(GL_PACK_ALIGNMENT);
        boolean oldBlend=glIsEnabled(GL_BLEND);int src=glGetInteger(GL_BLEND_SRC_RGB),dst=glGetInteger(GL_BLEND_DST_RGB),srcA=glGetInteger(GL_BLEND_SRC_ALPHA),dstA=glGetInteger(GL_BLEND_DST_ALPHA),eq=glGetInteger(GL_BLEND_EQUATION_RGB),eqA=glGetInteger(GL_BLEND_EQUATION_ALPHA);
        int fbo=glGenFramebuffers(),texture=glGenTextures();
        Class<?> projection=Class.forName("1308394360"),model=Class.forName("889335306");
        boolean matrices=false;
        try {
            glEnable(GL_BLEND);glBlendEquation(GL_FUNC_ADD);glBlendFuncSeparate(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA,GL_ONE,GL_ONE_MINUS_SRC_ALPHA);
            glBindTexture(GL_TEXTURE_2D,texture);
            glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,atlas,atlas,0,GL_RGBA,GL_UNSIGNED_BYTE,(ByteBuffer)null);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glBindFramebuffer(GL_FRAMEBUFFER,fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);
            glDrawBuffer(GL_COLOR_ATTACHMENT0);glReadBuffer(GL_COLOR_ATTACHMENT0);
            if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Native glyph framebuffer incomplete");
            glViewport(0,0,atlas,atlas);
            call(projection,"static","void");call(model,"if","void");matrices=true;call(model,"native","void");
            FloatBuffer ortho=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();
            ortho.put(new float[]{2f/atlas,0,0,0,0,-2f/atlas,0,0,0,0,-.001f,0,-1,1,0,1}).flip();
            call(projection,"switch","void",ortho);
            for(Label l:labels)l.node.getClass().getField("if").set(l.node,1d);
            ByteBuffer before=probe(labels,atlas);
            for(Label l:labels) {
                l.before=bounds(before,l,atlas);
                double errorX=error(l.before,l.w,l.horizontal,true),errorY=error(l.before,l.h,l.vertical,false);
                l.dx=(int)Math.round(errorX);l.dy=(int)Math.round(errorY);
                int baseX=coord(l.originalPosition,"class"),baseY=coord(l.originalPosition,"abstract");
                l.originalPosition=pos(baseX+l.dx,baseY+l.dy);
                Class.forName("36549396").getField("final").set(l.widget,l.originalPosition);
            }
            ByteBuffer after=probe(labels,atlas);JsonArray report=new JsonArray();
            for(Label l:labels) {
                l.after=bounds(after,l,atlas);
                double errorX=error(l.after,l.w,l.horizontal,true),errorY=error(l.after,l.h,l.vertical,false);
                if(Math.abs(errorX)>.51||Math.abs(errorY)>.51)
                    throw new IllegalStateException("Native ink alignment failed for "+l.text+": "+errorX+", "+errorY);
                JsonObject entry=new JsonObject();entry.addProperty("id",l.id);entry.addProperty("text",l.text);
                entry.addProperty("horizontal",l.horizontal);entry.addProperty("vertical",l.vertical);
                entry.addProperty("offsetX",l.dx);entry.addProperty("offsetY",l.dy);
                entry.addProperty("remainingErrorX",errorX);entry.addProperty("remainingErrorY",errorY);
                entry.add("beforeInk",new Gson().toJsonTree(l.before));entry.add("afterInk",new Gson().toJsonTree(l.after));
                entry.addProperty("cellWidth",l.w);entry.addProperty("cellHeight",l.h);report.add(entry);
            }
            return report;
        } finally {
            for(Label l:labels)l.node.getClass().getField("if").set(l.node,l.opacity);
            if(matrices){call(model,"default","void");call(projection,"final","void");}
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,oldDraw);glBindFramebuffer(GL_READ_FRAMEBUFFER,oldRead);
            glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            glBindTexture(GL_TEXTURE_2D,oldTex);glBindBuffer(GL_PIXEL_PACK_BUFFER,oldPack);glPixelStorei(GL_PACK_ALIGNMENT,align);
            glDeleteFramebuffers(fbo);glDeleteTextures(texture);glBlendEquationSeparate(eq,eqA);glBlendFuncSeparate(src,dst,srcA,dstA);if(oldBlend)glEnable(GL_BLEND);else glDisable(GL_BLEND);
        }
    }
    static ByteBuffer probe(List<Label> labels,int atlas)throws Exception {
        glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT);glDisable(GL_SCISSOR_TEST);
        Field position=Class.forName("36549396").getField("final");Object mouse=pos(-2000,-2000);
        for(Label l:labels) {
            position.set(l.widget,pos(l.x+PAD+l.dx,l.y+PAD+l.dy));
            try{call(l.widget,"package","void",mouse,0f);}finally{position.set(l.widget,l.originalPosition);}
        }
        ByteBuffer pixels=ByteBuffer.allocateDirect(atlas*atlas*4);
        glBindBuffer(GL_PIXEL_PACK_BUFFER,0);glPixelStorei(GL_PACK_ALIGNMENT,1);
        glReadPixels(0,0,atlas,atlas,GL_RGBA,GL_UNSIGNED_BYTE,pixels);return pixels;
    }
    static int[] bounds(ByteBuffer pixels,Label l,int atlas) {
        int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,maxX=-1,maxY=-1;
        for(int y=l.y;y<l.y+l.tileH;y++)for(int x=l.x;x<l.x+l.tileW;x++) {
            int a=pixels.get(((atlas-y-1)*atlas+x)*4+3)&255;
            if(a<48)continue;
            minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);
        }
        if(maxX<minX)throw new IllegalStateException("Native glyph probe is empty: "+l.text);
        return new int[]{minX-l.x-PAD,minY-l.y-PAD,maxX-l.x-PAD+1,maxY-l.y-PAD+1};
    }
    static double error(int[] bounds,int size,String alignment,boolean horizontal) {
        int start=bounds[horizontal?0:1],end=bounds[horizontal?2:3];
        if(alignment.equalsIgnoreCase("Center"))return size/2d-(start+end)/2d;
        if(alignment.equalsIgnoreCase("Right")||alignment.equalsIgnoreCase("Bottom"))return size-end;
        return -start;
    }
}
