package replay;

import java.util.*;

/** A native cache does not count Java references as ownership. Retain each resource
 * once while its quarantined actor can be selected, and release it once on eviction. */
final class SceneResourceLease implements AutoCloseable {
    interface Driver {void retain(Object resource)throws Exception;void release(Object resource)throws Exception;}
    static final Driver NATIVE=new Driver(){
        public void retain(Object resource)throws Exception {Native.call(resource,"while","void");}
        public void release(Object resource)throws Exception {Native.call(resource,"char","void");}
    };
    final Driver driver;
    final Set<Object> held=Collections.newSetFromMap(new IdentityHashMap<>());
    boolean closed;
    SceneResourceLease(){this(NATIVE);}
    SceneResourceLease(Driver driver){this.driver=Objects.requireNonNull(driver);}
    void retain(Object resource)throws Exception {
        if(closed)throw new IllegalStateException("Closed scene resource lease");
        if(resource==null||held.contains(resource))return;
        driver.retain(resource);held.add(resource);
    }
    void retainModels(Object entity)throws Exception {
        Object equipment=SceneSecondaryRigs.equipment(entity);
        Object model=equipment==null?null:Native.field(equipment,"interface");
        if(model!=null&&Native.type("345141456").isInstance(model))retain(model);
        Object hands=SceneSecondaryRigs.hands(entity);
        if(hands!=null)retain(Native.field(hands,"finally"));
        boolean player=Native.type("200241649").isInstance(entity);
        Object owner=player?Native.call(Native.type("1478690128"),"new","1579323378",entity):Native.type("1848416318").isInstance(entity)?Native.field(entity,"native"):null;
        if(owner!=null&&(player||Native.type("859153379").isInstance(owner)))retain(Native.field(Native.call(owner,player?"assert":"if","611440048"),"final"));
    }
    public void close()throws Exception {
        if(closed)return;closed=true;Exception failure=null;
        for(Object resource:held)try{driver.release(resource);}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        held.clear();if(failure!=null)throw failure;
    }
}
