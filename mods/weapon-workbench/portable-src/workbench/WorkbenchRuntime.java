package workbench;

/** Host entry points; this class has no launcher, SDK or server dependency. */
public final class WorkbenchRuntime {
 private static final WorkbenchCore CORE=new WorkbenchCore();
 private WorkbenchRuntime(){}
 public static void frame(Object game,long number,double delta,boolean toggle,boolean escape){CORE.frame(game,number,delta,toggle,escape);}
 public static void draw(int width,int height){CORE.draw(width,height);}
 public static Object hook(String name,Object...args){return CORE.onClientHook(name,args);}
 public static void dispose(){CORE.dispose();}
 public static boolean active(){return CORE.active;}
}
