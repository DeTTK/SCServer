package vr;
import static vr.NativeAccess.*;

/** Native sky sampler initialization must not depend on weather arriving first. */
final class NativePostprocess {
    static void prepare(Object renderer)throws Exception {
        Object shader=get(get(renderer,"const"),"protected");
        int program=((Number)get(shader,"do")).intValue();
        Object compiler=get(type("703466785"),"static");
        if(program==0||program==((Number)get(compiler,"short")).intValue())return;
        Object scope=call(get(type("1973624787"),"package"),"strictfp","1973624787");
        try{
            call(shader,"case","void");
            // These are the exact string-pool entries/units in 1901576671.throws.
            // That method returns early until the environment sky map is filled;
            // otherwise cube and 2D samplers retain the same default unit zero.
            String[] names=(String[])get(type("1281655698"),"if");
            int[] entry={12262,17405,20382,41100,10935,41673};
            int[] unit={0,1,6,7,8,9};
            for(int i=0;i<entry.length;i++)call(shader,"abstract","void",names[entry[i]],unit[i]);
        }finally{((AutoCloseable)scope).close();}
    }
}
