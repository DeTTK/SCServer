package replay;

/** Advances ordinary forward playback to a 60-Hz capture grid, independent of preparation wall time. */
final class SceneBakeClock {
    long nextFrame=1;boolean begun;
    long elapsed(Timeline timeline,boolean bootstrapped,boolean captured,boolean finished)throws java.io.IOException {
        if(finished){timeline.playing=false;return 0;}
        if(timeline.seeking)throw new java.io.IOException("Do not seek while preparing scene tracks");
        timeline.speed(1);timeline.playing=true;
        if(!begun){begun=true;return 0;}
        if(timeline.position==timeline.duration&&!bootstrapped)throw new java.io.IOException("Recording ended before a scene world became available");
        // Keep normal playback semantics: paused seek settlement consumes motion
        // tails differently and must not run at every prepared frame.
        if(timeline.position!=timeline.target||bootstrapped&&!captured)return 0;
        long selected=Math.min(timeline.duration,Math.multiplyExact(nextFrame++,1_000_000_000L)/60);
        return selected-timeline.target;
    }
}
