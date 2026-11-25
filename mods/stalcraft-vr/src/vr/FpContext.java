package vr;

/** Native FP interface has overloads distinguishable only by JVM return descriptors. */
final class FpContext {
    static Object create(Object delegate,Object hands,Object sleeves,Object weapon,Object item)throws Exception {
        return Class.forName("vr.NativeFpAdapter",true,FpContext.class.getClassLoader()).getConstructor(Object.class,Object.class,Object.class,Object.class,Object.class).newInstance(delegate,hands,sleeves,weapon,item);
    }
}
