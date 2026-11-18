package replay;

/** Completes native loading only after the indexed scene has a published frame. */
final class SceneWorldAdmission {
    interface Access {
        boolean connected()throws Exception;
        Object screen()throws Exception;
        boolean ownedLoading(Object screen)throws Exception;
        void finish(Object screen)throws Exception;
    }
    boolean completed;
    void present(boolean frameReady,Access access)throws Exception {
        if(completed||!frameReady||!access.connected())return;
        Object screen=access.screen();
        if(screen!=null){
            if(!access.ownedLoading(screen))return;
            access.finish(screen);
        }
        completed=true;
    }
    void reset(){completed=false;}
}
