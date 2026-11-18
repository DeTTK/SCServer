package replay;

/** Client branch of source 1895495626.synchronized. */
final class SourceExtractor {
    interface Access {
        void progress()throws Exception;
        boolean hasEffect();
        void start()throws Exception;
        float rewardRange();
        void parameter(int index,float value)throws Exception;
        void finish()throws Exception;
    }
    static void tick(SourceExtractorState.Phase state,Access access)throws Exception {
        if(state==SourceExtractorState.Phase.EXTRACTING){
            access.progress();if(!access.hasEffect())access.start();
            if(access.hasEffect()&&access.rewardRange()>0f)access.parameter(0,access.rewardRange());
        }else if(state==SourceExtractorState.Phase.FINISHED)access.finish();
    }
}
