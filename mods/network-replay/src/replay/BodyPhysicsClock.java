package replay;

/** A body's native Bullet world keeps its own recorded origin and native remainder. */
final class BodyPhysicsClock {
    interface Step {void run(float seconds)throws Exception;}
    interface Running {boolean get()throws Exception;}
    static final float FIXED_STEP=0.016666668f;
    final double origin;
    double previous;
    long batches;
    double elapsed;
    BodyPhysicsClock(double seconds){origin=previous=seconds;}
    long wanted(double seconds){return (long)Math.floor(Math.max(0,seconds-origin)*60+1e-8);}
    void advance(double seconds,int maxSteps,Step nativeStep)throws Exception {
        advance(seconds,maxSteps,nativeStep,()->true);
    }
    void advance(double seconds,int maxSteps,Step nativeStep,Running running)throws Exception {
        if(!Double.isFinite(seconds)||maxSteps<1)throw new IllegalArgumentException("Invalid body physics clock");
        if(seconds<previous)throw new IllegalStateException("Body physics world survived reconstruction reset");
        // Schedule whole 60 Hz steps from the body's recorded origin. Passing
        // one native step at a time also avoids float partition rounding making
        // the number of steps depend on playback speed or a seek's frame sizes.
        long wanted=wanted(seconds);
        while(batches<wanted&&running.get()){
            nativeStep.run(FIXED_STEP);batches++;elapsed+=FIXED_STEP;
        }
        previous=seconds;
    }
}
