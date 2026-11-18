package replay;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;

/** Full recorded mip chains have one byte reservation and independent owners.
 * The resource reference count alone does not stop native mip retirement. */
final class SceneTextureResidency {
    private static final class Lease {
        final long bytes;
        int owners=1;
        Lease(long bytes){this.bytes=bytes;}
    }
    private final Map<Object,Lease> leases=new IdentityHashMap<>();
    private long bytes;
    synchronized void acquire(Object texture,long size,long budget)throws IOException {
        if(texture==null||size<0||budget<0)throw new IllegalArgumentException("Invalid texture reservation");
        Lease old=leases.get(texture);
        if(old!=null){if(old.bytes!=size)throw new IOException("Recorded texture allocation changed");old.owners++;return;}
        if(size>budget||bytes>budget-size)throw new IOException("Recorded textures need more than the resident budget: "+bytes+" + "+size+" > "+budget);
        leases.put(texture,new Lease(size));bytes+=size;
    }
    synchronized void release(Object texture){
        Lease lease=leases.get(texture);if(lease==null)throw new IllegalStateException("Unowned recorded texture");
        if(--lease.owners==0){leases.remove(texture);bytes-=lease.bytes;}
    }
    synchronized boolean owns(Object texture){return leases.containsKey(texture);}
    synchronized long bytes(){return bytes;}
    synchronized int textures(){return leases.size();}
}
