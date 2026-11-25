package vr;
import java.awt.*;
import java.awt.image.BufferedImage;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33.*;
import static vr.Math3.*;

/** Own GL resources and a text texture. No second native ImGui frame is created. */
final class SpatialMenuDraw implements AutoCloseable {
    private int program,vao,vbo,texture;
    private String uploaded;
    void nativeScreen(Pose panel,double width,double height,int source,Pose eye,Fov fov,Tracking.Hand hand,double distance){
        nativeScreen(panel,width,height,source,eye,fov,hand,distance,0,0,1,1);
    }
    void nativeScreen(Pose panel,double width,double height,int source,Pose eye,Fov fov,Tracking.Hand hand,double distance,double u0,double v0,double u1,double v1){
        int oldProgram=glGetInteger(GL_CURRENT_PROGRAM),oldVao=glGetInteger(GL_VERTEX_ARRAY_BINDING),oldBuffer=glGetInteger(GL_ARRAY_BUFFER_BINDING),active=glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);int oldTexture=glGetInteger(GL_TEXTURE_BINDING_2D);boolean depth=glIsEnabled(GL_DEPTH_TEST),blend=glIsEnabled(GL_BLEND),cull=glIsEnabled(GL_CULL_FACE),scissor=glIsEnabled(GL_SCISSOR_TEST),stencil=glIsEnabled(GL_STENCIL_TEST),mask=glGetBoolean(GL_DEPTH_WRITEMASK);
        boolean srgb=glIsEnabled(GL_FRAMEBUFFER_SRGB);float point=glGetFloat(GL_POINT_SIZE),line=glGetFloat(GL_LINE_WIDTH);
        try{
            init();glUseProgram(program);glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,vbo);glBindTexture(GL_TEXTURE_2D,source);glDisable(GL_DEPTH_TEST);glDepthMask(false);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);glDisable(GL_SCISSOR_TEST);glDisable(GL_STENCIL_TEST);
            // Native postprocessing has already encoded these menu pixels.
            glDisable(GL_FRAMEBUFFER_SRGB);
            glUniformMatrix4fv(glGetUniformLocation(program,"projection"),false,fov.projection(.03,1500));glUniformMatrix4fv(glGetUniformLocation(program,"view"),false,eye.view());glUniformMatrix4fv(glGetUniformLocation(program,"model"),false,Pose.matrix(panel));glUniform1i(glGetUniformLocation(program,"image"),0);glUniform1i(glGetUniformLocation(program,"textured"),2);
            float x=(float)width/2,y=(float)height/2,l=(float)u0,t=(float)v0,r=(float)u1,b=(float)v1;vertices(new float[]{-x,y,0,l,t,-x,-y,0,l,b,x,-y,0,r,b,-x,y,0,l,t,x,-y,0,r,b,x,y,0,r,t});glDrawArrays(GL_TRIANGLES,0,6);
            if(hand.valid()){
                glUniform1i(glGetUniformLocation(program,"textured"),0);glUniformMatrix4fv(glGetUniformLocation(program,"model"),false,Pose.matrix(Pose.ID));
                V start=hand.aim().p(),end=start.add(hand.aim().forward().mul(distance));vertices(new float[]{(float)start.x(),(float)start.y(),(float)start.z(),0,0,(float)end.x(),(float)end.y(),(float)end.z(),0,0});glLineWidth(1);glDrawArrays(GL_LINES,0,2);glPointSize(8);glDrawArrays(GL_POINTS,1,1);
            }
        }finally{glUseProgram(oldProgram);glBindVertexArray(oldVao);glBindBuffer(GL_ARRAY_BUFFER,oldBuffer);glBindTexture(GL_TEXTURE_2D,oldTexture);glActiveTexture(active);toggle(GL_DEPTH_TEST,depth);toggle(GL_BLEND,blend);toggle(GL_CULL_FACE,cull);toggle(GL_SCISSOR_TEST,scissor);toggle(GL_STENCIL_TEST,stencil);toggle(GL_FRAMEBUFFER_SRGB,srgb);glDepthMask(mask);glPointSize(point);glLineWidth(line);}
    }
    void draw(SpatialMenu menu,GameSession game,Pose eye,Fov fov,Tracking.Hand hand){
        draw(menu,game,eye,fov,hand,null);
    }
    void draw(SpatialMenu menu,GameSession game,Pose eye,Fov fov,Tracking.Hand hand,VrFrameStats timings){
        if(!menu.open)return;
        int oldProgram=glGetInteger(GL_CURRENT_PROGRAM),oldVao=glGetInteger(GL_VERTEX_ARRAY_BINDING),oldBuffer=glGetInteger(GL_ARRAY_BUFFER_BINDING),active=glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);int oldTexture=glGetInteger(GL_TEXTURE_BINDING_2D);
        boolean depth=glIsEnabled(GL_DEPTH_TEST),blend=glIsEnabled(GL_BLEND),cull=glIsEnabled(GL_CULL_FACE),mask=glGetBoolean(GL_DEPTH_WRITEMASK),scissor=glIsEnabled(GL_SCISSOR_TEST),stencil=glIsEnabled(GL_STENCIL_TEST),srgb=glIsEnabled(GL_FRAMEBUFFER_SRGB);
        int srcRGB=glGetInteger(GL_BLEND_SRC_RGB),dstRGB=glGetInteger(GL_BLEND_DST_RGB),srcAlpha=glGetInteger(GL_BLEND_SRC_ALPHA),dstAlpha=glGetInteger(GL_BLEND_DST_ALPHA),eqRGB=glGetInteger(GL_BLEND_EQUATION_RGB),eqAlpha=glGetInteger(GL_BLEND_EQUATION_ALPHA),unpack=glGetInteger(GL_UNPACK_ALIGNMENT);
        float point=glGetFloat(GL_POINT_SIZE),line=glGetFloat(GL_LINE_WIDTH);
        try{
            init();upload(menu,game,timings);glUseProgram(program);glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,vbo);glBindTexture(GL_TEXTURE_2D,texture);
            // AWT menu pixels and the completed native eye are display encoded,
            // like nativeScreen. Blend them without a second sRGB decode/encode.
            glDisable(GL_FRAMEBUFFER_SRGB);glDisable(GL_DEPTH_TEST);glDepthMask(false);glDisable(GL_CULL_FACE);glDisable(GL_SCISSOR_TEST);glDisable(GL_STENCIL_TEST);glEnable(GL_BLEND);glBlendEquation(GL_FUNC_ADD);glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);
            glUniformMatrix4fv(glGetUniformLocation(program,"projection"),false,fov.projection(.03,1500));glUniformMatrix4fv(glGetUniformLocation(program,"view"),false,eye.view());
            glUniformMatrix4fv(glGetUniformLocation(program,"model"),false,Pose.matrix(menu.panel));glUniform1i(glGetUniformLocation(program,"image"),0);glUniform1i(glGetUniformLocation(program,"textured"),1);
            float x=(float)SpatialMenu.WIDTH/2,y=(float)SpatialMenu.HEIGHT/2;
            vertices(new float[]{-x,y,0,0,0,-x,-y,0,0,1,x,-y,0,1,1,-x,y,0,0,0,x,-y,0,1,1,x,y,0,1,0});glDrawArrays(GL_TRIANGLES,0,6);
            if(hand.valid()){
                glUniform1i(glGetUniformLocation(program,"textured"),0);glUniformMatrix4fv(glGetUniformLocation(program,"model"),false,Pose.matrix(Pose.ID));
                V start=hand.aim().p(),end=start.add(hand.aim().forward().mul(menu.hit==null?2:menu.hit.distance()));
                vertices(new float[]{(float)start.x(),(float)start.y(),(float)start.z(),0,0,(float)end.x(),(float)end.y(),(float)end.z(),0,0});glLineWidth(1);glDrawArrays(GL_LINES,0,2);glPointSize(8);glDrawArrays(GL_POINTS,1,1);
            }
        }finally{
            glPixelStorei(GL_UNPACK_ALIGNMENT,unpack);glUseProgram(oldProgram);glBindVertexArray(oldVao);glBindBuffer(GL_ARRAY_BUFFER,oldBuffer);glBindTexture(GL_TEXTURE_2D,oldTexture);glActiveTexture(active);
            toggle(GL_FRAMEBUFFER_SRGB,srgb);toggle(GL_DEPTH_TEST,depth);toggle(GL_BLEND,blend);toggle(GL_CULL_FACE,cull);toggle(GL_SCISSOR_TEST,scissor);toggle(GL_STENCIL_TEST,stencil);glDepthMask(mask);glBlendFuncSeparate(srcRGB,dstRGB,srcAlpha,dstAlpha);glBlendEquationSeparate(eqRGB,eqAlpha);glPointSize(point);glLineWidth(line);
        }
    }
    private void upload(SpatialMenu menu,GameSession game,VrFrameStats timings){
        String key=menu.hovered+":"+game.turnDegrees+":"+game.status+":"+game.nativeAmmo+":"+game.headBlocked+(timings==null?"":timings.summary+timings.phases);if(key.equals(uploaded))return;
        BufferedImage image=new BufferedImage(640,384,BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();
        try{
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);g.setColor(new Color(16,22,31,245));g.fillRect(0,0,640,384);
            g.setFont(new Font("Dialog",Font.BOLD,28));g.setColor(Color.WHITE);g.drawString("STALCRAFT VR · "+game.spec.name,32,48);
            g.setFont(new Font("Dialog",Font.PLAIN,17));g.setColor(new Color(165,184,207));g.drawString("Правый луч + спуск — выбор",32,80);
            String[] labels={"Продолжить","Центрировать голову", "Ступенчатый поворот: "+(int)game.turnDegrees+"°","Инвентарь / назад"};
            for(int i=0;i<labels.length;i++){g.setColor(menu.hovered==i?new Color(43,112,166):new Color(34,47,62));g.fillRoundRect(32,SpatialMenu.ROW_Y+i*SpatialMenu.ROW_STEP,576,SpatialMenu.ROW_HEIGHT,12,12);g.setColor(Color.WHITE);g.setFont(new Font("Dialog",Font.PLAIN,22));g.drawString(labels[i],48,SpatialMenu.ROW_Y+28+i*SpatialMenu.ROW_STEP);}
            g.setFont(new Font("Dialog",Font.PLAIN,16));g.setColor(new Color(180,200,215));g.drawString(game.headBlocked?"Вернитесь из стены или центрируйте голову":"Патроны: "+game.nativeAmmo+" · "+game.status,32,324);g.drawString("Левая вторичная кнопка — меню",32,355);
            if(timings!=null){g.setColor(new Color(16,22,31));g.fillRect(24,302,600,68);g.setColor(new Color(180,200,215));g.setFont(new Font("Dialog",Font.PLAIN,14));g.drawString(timings.summary,32,324);g.drawString(timings.phases,32,355);}
        }finally{g.dispose();}
        var pixels=BufferUtils.createByteBuffer(640*384*4);for(int yy=0;yy<384;yy++)for(int xx=0;xx<640;xx++){int c=image.getRGB(xx,yy);pixels.put((byte)(c>>16)).put((byte)(c>>8)).put((byte)c).put((byte)(c>>24));}pixels.flip();
        glBindTexture(GL_TEXTURE_2D,texture);glPixelStorei(GL_UNPACK_ALIGNMENT,1);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,640,384,0,GL_RGBA,GL_UNSIGNED_BYTE,pixels);uploaded=key;
    }
    private void init(){if(program!=0)return;
        int v=shader(GL_VERTEX_SHADER,"#version 330 core\nlayout(location=0)in vec3 position;layout(location=1)in vec2 uv;uniform mat4 projection,view,model;out vec2 tex;void main(){tex=uv;gl_Position=projection*view*model*vec4(position,1);}");
        int f=shader(GL_FRAGMENT_SHADER,"#version 330 core\nin vec2 tex;uniform sampler2D image;uniform int textured;out vec4 color;void main(){color=textured>0?texture(image,textured==2?vec2(tex.x,1-tex.y):tex):vec4(.2,.8,1,1);}");
        program=glCreateProgram();glAttachShader(program,v);glAttachShader(program,f);glLinkProgram(program);glDeleteShader(v);glDeleteShader(f);if(glGetProgrami(program,GL_LINK_STATUS)==0)throw new IllegalStateException(glGetProgramInfoLog(program));
        vao=glGenVertexArrays();vbo=glGenBuffers();glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,vbo);glEnableVertexAttribArray(0);glVertexAttribPointer(0,3,GL_FLOAT,false,20,0L);glEnableVertexAttribArray(1);glVertexAttribPointer(1,2,GL_FLOAT,false,20,12L);
        texture=glGenTextures();glBindTexture(GL_TEXTURE_2D,texture);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
    }
    private void vertices(float[] data){glBufferData(GL_ARRAY_BUFFER,BufferUtils.createFloatBuffer(data.length).put(data).flip(),GL_STREAM_DRAW);}
    private static int shader(int type,String source){int s=glCreateShader(type);glShaderSource(s,source);glCompileShader(s);if(glGetShaderi(s,GL_COMPILE_STATUS)==0)throw new IllegalStateException(glGetShaderInfoLog(s));return s;}
    private static void toggle(int flag,boolean value){if(value)glEnable(flag);else glDisable(flag);}
    public void close(){if(program!=0)glDeleteProgram(program);if(vao!=0)glDeleteVertexArrays(vao);if(vbo!=0)glDeleteBuffers(vbo);if(texture!=0)glDeleteTextures(texture);program=vao=vbo=texture=0;uploaded=null;}
}
