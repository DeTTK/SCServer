package vr;

/** A user-requested connection survives only recoverable runtime disconnects. */
final class VrReconnect {
    private boolean wanted;
    private long retryAt=Long.MAX_VALUE;
    boolean wanted(){return wanted;}
    void request(){wanted=true;retryAt=Long.MAX_VALUE;}
    void cancel(){wanted=false;retryAt=Long.MAX_VALUE;}
    void connected(){retryAt=Long.MAX_VALUE;}
    void disconnected(long now){if(wanted)retryAt=now+3_000_000_000L;}
    boolean due(long now){if(!wanted||now<retryAt)return false;retryAt=Long.MAX_VALUE;return true;}
}
