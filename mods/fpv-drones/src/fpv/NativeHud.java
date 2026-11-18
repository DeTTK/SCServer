package fpv;

import java.lang.reflect.Method;
import static fpv.NativeFlight.call;
import static fpv.NativeFlight.get;

/** Uses the active client's own HUD shader, font and scoped render state. */
final class NativeHud {
    private static Method rectangle;
    static void rect(int x,int y,int w,int h,int color) throws Exception {
        if(rectangle==null) rectangle=Class.forName("899237048").getMethod("synchronized",int.class,int.class,int.class,int.class,int.class);
        rectangle.invoke(null,x,y,x+w,y+h,color);
    }
    static void text(Object hud,String text,int x,int y,int color) throws Exception {
        Object font=get(hud,"throw");
        if(font!=null) call(font,"static","double",text,x,y,color);
    }
    static void stick(Object hud,int x,int y,int size,double horizontal,double vertical,String label) throws Exception {
        int r=size/2, cx=x+r, cy=y+r;
        rect(x,y,size,size,0x99081018);
        rect(x,y,size,1,0xffa5c8d0);rect(x,y+size,size,1,0xffa5c8d0);
        rect(x,y,1,size,0xffa5c8d0);rect(x+size,y,1,size,0xffa5c8d0);
        rect(cx,y,1,size,0x6680b0bb);rect(x,cy,size,1,0x6680b0bb);
        int px=cx+(int)(Math.max(-1,Math.min(1,horizontal))*(r-6));
        int py=cy-(int)(Math.max(-1,Math.min(1,vertical))*(r-6));
        rect(px-5,py-5,10,10,0xff80f5ca);
        text(hud,label,x,y-19,0xffddf3f1);
    }
    static void render(Object hud,int w,int h,boolean osd,boolean sticks,boolean hints,
                       String effect,boolean armed,boolean contact,double throttle,double yaw,double roll,double pitch,
                       boolean server,float battery,double snapshotAge) throws Exception {
        try(AutoCloseable state=(AutoCloseable)call(Class.forName("1973624787"),"do","1973624787")) {
            if(osd) {
                rect(16,16,310,48,0x99081018);
                text(hud,(contact?"IMPACT":armed?"ARMED":"DISARMED")+"  THR "+Math.round(throttle*100)+"%",24,22,armed?0xff80f5ca:0xffffc878);
                String telemetry=server ? "BAT "+Math.round(battery*100)+"% | UPDATE "+Math.round(snapshotAge*1000)+" ms" : "LOCAL TEST | BAT / LINK: N/A";
                text(hud,telemetry,24,42,server&&snapshotAge>.5?0xffff8866:0xffddf3f1);
            }
            if(hints) {
                rect(16,76,440,65,0x99081018);
                text(hud,"R — арминг   G — выход из FPV",24,82,0xffddf3f1);
                text(hud,"Space / Ctrl — газ   Q / E — рыскание",24,102,0xffddf3f1);
                text(hud,"W / S — тангаж   A / D — крен",24,122,0xffddf3f1);
            }
            if(sticks) {
                int size=Math.max(48,Math.min(130,Math.min(w/4,h/4)));
                stick(hud,24,h-size-24,size,yaw,throttle*2-1,"YAW / THR");
                stick(hud,w-size-24,h-size-24,size,roll,pitch,"ROLL / PITCH");
            }
        }
    }
}
