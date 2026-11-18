package szserver.net;

import szserver.inventory.ItemStack;

/** Transport-owned native throwable clock; disk work starts only when an item is actually used. */
public final class GrenadeUse {
    public record Rules(int prepareTicks,int throwTicks,int igniteDelay,int igniteTicks,boolean ignites,boolean handUse) {
        public Rules {if(prepareTicks<0||prepareTicks>2400||throwTicks<1||throwTicks>12000||igniteDelay<0||igniteDelay>12000||igniteTicks<0||igniteTicks>12000||ignites&&igniteTicks<1||ignites&&(long)prepareTicks+igniteDelay+igniteTicks>2400)throw new IllegalArgumentException("grenade_use_budget");}
        public float cooking(int elapsedTicks){if(!ignites)return 0;return Math.max(0,Math.max(0,elapsedTicks-prepareTicks)-igniteDelay)/(float)igniteTicks;}
        public int remaining(int lifetime,int elapsedTicks){return Math.max(1,(int)(lifetime*(1f-Math.max(0f,Math.min(1f,cooking(elapsedTicks))))));}
        public boolean expired(int elapsedTicks){return ignites&&cooking(elapsedTicks)>1f;}
    }
    public record Held(ItemStack item,Rules rules,int mode,long life,int hand,boolean holstered,long started) {
        public Held {if(item==null||rules==null||!rules.handUse()||mode<0||mode>1||mode==0&&(hand!=4||holstered))throw new IllegalArgumentException("grenade_use_selection");}
        public int elapsed(long now){return (int)Math.min(12000,Math.max(0,now-started)/50_000_000L);}
        public boolean valid(ItemStack equipped,long currentLife,int currentHand,boolean currentHolstered){return life==currentLife&&item.equals(equipped)&&(mode!=0||currentHand==hand&&currentHolstered==holstered);}
    }
    private Held held;
    public Held held(){return held;}
    /** Native abstract() rejects repeated starts while active; they must not reset a cooking fuse. */
    public boolean start(ItemStack item,Rules rules,int mode,long life,int hand,boolean holstered,long now){if(held!=null)return false;held=new Held(item,rules,mode,life,hand,holstered,now);return true;}
    public Held take(){Held before=held;held=null;return before;}
    public void cancel(){held=null;}
}
