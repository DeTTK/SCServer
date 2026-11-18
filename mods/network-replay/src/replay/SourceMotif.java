package replay;

/** Source visual progression is an unsigned level plus fractional stage progress. */
final class SourceMotif {
    record Progress(short packed) {
        int level(){return (packed>>>8)&255;}
        float fraction(){return (packed&255)/255f;}
    }
    record State(boolean override,Progress user,Progress authoritative) {
        Progress effective(){return override&&Short.toUnsignedInt(user.packed())>Short.toUnsignedInt(authoritative.packed())?user:authoritative;}
    }
    static State read(Object component)throws Exception {
        return new State((Boolean)Native.field(component,"szreplay$overrideProgress"),new Progress(((Number)Native.field(component,"szreplay$userProgress")).shortValue()),new Progress(((Number)Native.field(component,"szreplay$progress")).shortValue()));
    }
}
