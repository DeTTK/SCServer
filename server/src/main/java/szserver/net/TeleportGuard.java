package szserver.net;

import szserver.world.World.Vec;

/** Match the old five-second guard against movement queued before a teleport. */
public final class TeleportGuard {
    public void clear(){target=null;}
    private Vec target;private long expires;
    public void install(Vec p,long now){target=p;expires=now+5_000_000_000L;}
    public boolean accept(Vec p,long now){if(target==null)return true;if(now>=expires){target=null;return true;}double x=p.x()-target.x(),y=p.y()-target.y(),z=p.z()-target.z();if(x*x+y*y+z*z<=256){target=null;return true;}return false;}
}
