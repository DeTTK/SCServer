package szserver.client;

import java.util.Random;

/** Java Random's original 48-bit generator with an explicit transactional checkpoint.
 * Used only by the isolated NPC world; the client game's global random is untouched. */
final class NativeActorRandom extends Random {
    private static final long serialVersionUID=1L,MASK=(1L<<48)-1;
    private long value;
    NativeActorRandom(long seed){super(0);setSeed(seed);}
    @Override public synchronized void setSeed(long seed){super.setSeed(seed);value=(seed^0x5deece66dL)&MASK;}
    @Override protected synchronized int next(int bits){value=(value*0x5deece66dL+11)&MASK;return (int)(value>>>(48-bits));}
    synchronized long state(){return value;}
    synchronized void restore(long saved){if(saved<0||saved>MASK)throw new IllegalArgumentException("actor_random_state");value=saved;}
    // No native contact currently calls Gaussian. Its private cached value would need a separate snapshot.
    @Override public double nextGaussian(){throw new UnsupportedOperationException("actor_random_gaussian_unported");}
}
