package cinema;
import java.nio.*;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.lwjgl.opengl.GL33.*;
/** One-shot local framebuffer capture, enabled only by an explicit diagnostic file. */
final class SceneDebug {
    static void capture(CinematicCamera c)throws Exception {
        Path request=c.context.configDirectory().resolve("capture-frame.flag");if(!Files.exists(request))return;Files.delete(request);
        int[] vp=new int[4];glGetIntegerv(GL_VIEWPORT,vp);int w=vp[2],h=vp[3];if(w<1||h<1)return;
        int read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),pack=glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING),alignment=glGetInteger(GL_PACK_ALIGNMENT),readBuffer=glGetInteger(GL_READ_BUFFER);
        ByteBuffer pixels=ByteBuffer.allocateDirect(w*h*4);int targetReadBuffer=-1;
        try{glBindFramebuffer(GL_READ_FRAMEBUFFER,draw);targetReadBuffer=glGetInteger(GL_READ_BUFFER);glReadBuffer(glGetInteger(GL_DRAW_BUFFER0));glBindBuffer(GL_PIXEL_PACK_BUFFER,0);glPixelStorei(GL_PACK_ALIGNMENT,1);glReadPixels(vp[0],vp[1],w,h,GL_RGBA,GL_UNSIGNED_BYTE,pixels);}
        finally{if(targetReadBuffer>=0)glReadBuffer(targetReadBuffer);glBindFramebuffer(GL_READ_FRAMEBUFFER,read);glReadBuffer(readBuffer);glBindBuffer(GL_PIXEL_PACK_BUFFER,pack);glPixelStorei(GL_PACK_ALIGNMENT,alignment);}
        BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int i=((h-1-y)*w+x)*4;image.setRGB(x,y,0xff000000|((pixels.get(i)&255)<<16)|((pixels.get(i+1)&255)<<8)|(pixels.get(i+2)&255));}
        ImageIO.write(image,"png",c.context.configDirectory().resolve("live-scene.png").toFile());
        Files.writeString(c.context.configDirectory().resolve("live-scene.txt"),"camera="+c.last+"\nviewer="+c.displayPose+"\nprojection="+java.util.Arrays.toString(c.displayProjection)+"\nFP="+c.fpScene.status+"\nerror="+c.fpError);
    }
}
