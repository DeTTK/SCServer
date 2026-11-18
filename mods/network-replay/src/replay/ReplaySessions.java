package replay;

/** Playback closes real transports before installing its in-memory connection. */
final class ReplaySessions {
    interface Access {
        Object current(Object game)throws Exception;
        boolean menu(Object connection)throws Exception;
        boolean closed(Object connection)throws Exception;
        void install(Object game,Object connection)throws Exception;
        void leave(Object connection)throws Exception;
    }
    static final Access NATIVE=new Access(){
        public Object current(Object game)throws Exception{return Native.call(game,"transient","1219970978");}
        public boolean menu(Object connection)throws Exception {
            return Native.call(connection,"do","272922522")==Native.field(Native.type("272922522"),"BACKEND_LOGIN")
                &&Native.call(connection,"double","1054393445")==null;
        }
        public boolean closed(Object connection)throws Exception{return (boolean)Native.call(connection,"continue","boolean");}
        public void install(Object game,Object connection)throws Exception{Native.call(game,"protected","void",connection);}
        public void leave(Object connection)throws Exception{Native.call(connection,"package","void");Native.call(connection,"else","void");}
    };
    final Access access;
    Object savedMenu;
    ReplaySessions(){this(NATIVE);}
    ReplaySessions(Access access){this.access=access;}
    static boolean blocked(Object game)throws Exception{return blocked(NATIVE,game,null);}
    static void leaveGame(Object game)throws Exception{leaveGame(NATIVE,game);}
    static void leaveGame(Access access,Object game)throws Exception {
        Object current=access.current(game);
        if(current!=null&&!access.menu(current)&&!access.closed(current))access.leave(current);
    }
    static boolean blocked(Access access,Object game,Object owned)throws Exception {
        Object current=access.current(game);
        return current!=null&&current!=owned&&!access.menu(current);
    }
    void take(Object game,Object owned)throws Exception {
        Object current=access.current(game);
        if(current==null||current==owned)return;
        if(!access.menu(current)){
            if(!access.closed(current))access.leave(current);
            Object remaining=access.current(game);
            if(remaining==current)throw new IllegalStateException("Game session has not finished closing");
            if(remaining==null)return;
            if(!access.menu(remaining))throw new IllegalStateException("Another game session started during replay transition");
            current=remaining;
        }
        // A menu connection is also a real transport and can publish global
        // clock/weather state. Do not keep or restore it during offline replay.
        if(!access.closed(current))access.leave(current);
        if(access.current(game)==current)throw new IllegalStateException("Backend session has not finished closing");
    }
    void restore(Object game)throws Exception {
        Object saved=savedMenu;savedMenu=null;
        if(saved!=null&&access.current(game)==null&&!access.closed(saved)&&access.menu(saved))access.install(game,saved);
    }
}
