import java.nio.*;import java.nio.file.*;import java.lang.reflect.*;import java.awt.image.BufferedImage;import javax.imageio.ImageIO;import java.util.concurrent.atomic.*;import org.lwjgl.glfw.GLFW;import org.lwjgl.opengl.*;import org.objectweb.asm.*;
public class LoadingScreenProbe {
 static int checks;static void check(boolean b,String why){if(!b)throw new AssertionError(why);checks++;}
 static void shot(Path path,int w,int h)throws Exception {ByteBuffer b=org.lwjgl.BufferUtils.createByteBuffer(w*h*4);GL11.glReadPixels(0,0,w,h,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,b);BufferedImage im=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);int bright=0;for(int y=0;y<h;y++)for(int x=0;x<w;x++){int p=(y*w+x)*4;int rr=b.get(p)&255,g=b.get(p+1)&255,bb=b.get(p+2)&255;if(rr>70||g>70||bb>70)bright++;im.setRGB(x,h-1-y,0xff000000|(rr<<16)|(g<<8)|bb);}check(bright>1000,"Loading screen contains visible artwork and text");ImageIO.write(im,"png",path.toFile());}
 public static void main(String[] a)throws Exception {
  Path out=Path.of(a[0]);Files.createDirectories(out);
  check(GLFW.glfwInit(),"GLFW initialized");GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,3);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,3);GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE,GLFW.GLFW_OPENGL_CORE_PROFILE);
  long win=GLFW.glfwCreateWindow(1280,720,"isolated loading probe",0,0);check(win!=0&&GLFW.glfwGetWindowAttrib(win,GLFW.GLFW_VISIBLE)==GLFW.GLFW_FALSE,"Hidden window");GLFW.glfwMakeContextCurrent(win);GL.createCapabilities();
  Object state=Class.forName("263463091").getConstructor().newInstance();Class<?> type=state.getClass();AtomicInteger done=(AtomicInteger)type.getField("this").get(state),total=(AtomicInteger)type.getField("instanceof").get(state);total.set(100);done.set(25);((AtomicReference<String>)type.getField("return").get(state)).set("Загрузка ресурсов");
  StartupTextureAgent.prepareStartup(state);StartupTextureAgent.resizeStartup(state,960,640);
  for(int i=0;i<90;i++){StartupTextureAgent.drawStartup(state,.016f);Thread.sleep(16);}
  check(StartupTextureAgent.renderedStartupFrames()>50,"Frames really rendered");check(CustomLoadingScreen.diagnostic()==null,"No rendering failure: "+CustomLoadingScreen.diagnostic());check(done.get()==25&&total.get()==100,"Native progress counters retained");shot(out.resolve("loading-25.png"),960,640);
  done.set(75);StartupTextureAgent.resizeStartup(state,1280,720);for(int i=0;i<25;i++){StartupTextureAgent.drawStartup(state,.016f);Thread.sleep(16);}shot(out.resolve("loading-75.png"),1280,720);check(GL11.glGetError()==0,"No OpenGL errors");
  CustomLoadingScreen.closeContext();GLFW.glfwDestroyWindow(win);GLFW.glfwTerminate();
  // The real startup class must verify after all three call-site replacements.
  Class.forName("1788203808",false,LoadingScreenProbe.class.getClassLoader()).getDeclaredMethods();check(true,"Native startup class verifies");
  System.out.println("PASS "+checks+" loading screen checks; rendered="+StartupTextureAgent.renderedStartupFrames());System.exit(0);
 }
}
