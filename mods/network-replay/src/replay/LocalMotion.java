package replay;

import java.io.*;

/** Native animation/stance transitions with the recorder's captured trajectory. */
final class LocalMotion {
    final SourceAdapters adapters;Object player;long frames,positions,looks,inputTraceBytes,inputTraceRecords,inputTraceAim;boolean positioned;
    record CameraAnchor(Object player,Pose pose,double yaw,double pitch){}
    CameraAnchor cameraAnchor;
    long cameraEpoch;
    double[] previousPosition;
    LocalMotion(SourceAdapters adapters){this.adapters=adapters;}
    void bind(Object game)throws Exception {
        if(adapters==null)return;
        Object next=Native.field(game,"e");if(next==null||next==player)return;
        for(String name:new String[]{"961798934","378545641","820497227","576005245","1899091998","1704556748"})adapters.verify(name);
        adapters.verifyDependency("1943200621","80a6fd6f1168a15e542ce87c64cfc410ccb5a73b2144c145720f09a94d105736");
        adapters.verifyDependency("861754670","9cc087e9e69da4640fbadac7c5fa39d104cee05780ae932b6016894034cb7c01");
        player=next;positioned=false;previousPosition=null;cameraAnchor=null;cameraEpoch++;ReplayDecode.bindLocal(player,adapters);
        ReplayRender.recorder=player;ReplayRender.recorderWorld=Native.field(game,"g");ReplayRender.recorderVisible=false;
    }
    void detach(){ReplayDecode.clearLocal(adapters);if(ReplayRender.recorder==player){ReplayRender.recorder=ReplayRender.recorderWorld=null;ReplayRender.recorderVisible=false;}player=null;positioned=false;previousPosition=null;cameraAnchor=null;cameraEpoch++;}
    void reset(){detach();frames=positions=looks=inputTraceBytes=inputTraceRecords=inputTraceAim=0;}
    void apply(Object game,SelfMotion.Frame frame)throws Exception {
        bind(game);if(player==null)throw new IOException("Recorded local controls arrived before local player");
        Object current=Native.call(Native.type("576005245"),"char","153304200",player,1f);
        double[] p=frame.position()==null?new double[]{((Number)Native.field(current,"case")).doubleValue(),((Number)Native.field(current,"void")).doubleValue(),((Number)Native.field(current,"import")).doubleValue()}:frame.position();
        float yaw=frame.look()==null?((Number)Native.call(player,"catch","float")).floatValue():frame.look()[0];
        float pitch=frame.look()==null?((Number)Native.call(player,"volatile","float")).floatValue():frame.look()[1];
        // The regular local tick is suppressed. Advance the native controller
        // exactly once per captured input frame, then replace its position with
        // the recorded position. This retains stance transitions and animation
        // state without letting prediction produce a competing trajectory.
        Object controller=Native.call(Native.type("1704556748"),"return","1704556748",player);
        Object state=Native.call(controller,"if","1943200621");
        double[] previous=previousPosition==null?p:previousPosition;
        float step=frame.interpolation();
        if(!(step>0&&step<=1))throw new IOException("Invalid recorded client simulation delta: "+step);
        Native.call(player,"interface","void");
        if(frame.keys()!=null){
            Object intent=Native.type("1899091998").getDeclaredConstructor().newInstance();
            SourceAdapters.put(intent,"int",frame.inputSpeed());Object[] modes=(Object[])Native.call(Native.type("1785380642"),"values","[L1785380642;");
            if(frame.mode()>=modes.length)throw new IOException("Local movement mode unavailable");SourceAdapters.put(intent,"case",modes[frame.mode()]);
            String[] fields={"package","finally","continue","goto","interface","else","static","do"};for(int n=0;n<fields.length;n++)SourceAdapters.put(intent,fields[n],frame.keys()[n]!=0);
            SourceAdapters.put(intent,"long",Native.type("861754670").getConstructor(java.util.List.class).newInstance(java.util.List.of()));
            SourceAdapters.put(controller,"return",intent);
        }
        Object intent=Native.field(controller,"return");SourceAdapters.put(state,"k",Native.field(intent,"int"));SourceAdapters.put(state,"l",Native.field(intent,"case"));
        double clock=((Number)Native.field(state,"return")).doubleValue();
        Native.call(state,"new","void",previous[0],previous[1],previous[2]);
        SourceAdapters.put(state,"af",yaw);SourceAdapters.put(state,"ae",pitch);
        Native.call(controller,"while","void",intent,step);
        // Publish current/previous captured positions through the native copy;
        // teleport/synchronized resets both and destroys render interpolation.
        SourceAdapters.put(state,"return",clock);
        animationState(state,p,previous,step,yaw,pitch);
        Native.call(controller,"new","void");
        if(Boolean.getBoolean("sz.replay.qa.input")){
            validatePosition(player,1f,p);validatePosition(player,0f,previous);
        }
        previousPosition=p.clone();
        // The sender drains the mouse telemetry journal here (source 650017940,
        // native 1385126878). The actual player look is already applied above.
        MouseTrace.Summary trace=MouseTrace.read(frame.actions());inputTraceBytes+=trace.bytes();inputTraceRecords+=trace.records();inputTraceAim+=trace.extendedAim();
        frames++;if(frame.position()!=null){positions++;positioned=true;}if(frame.look()!=null)looks++;
        // Publish only after the native packet application finishes. Camera
        // hooks may run reentrantly while native interpolation is still being
        // initialized; that intermediate position is not a recorded anchor.
        if(frame.position()!=null||cameraAnchor!=null){
            Pose.V eye=frame.position()!=null?new Pose.V(p[0],p[1]+1.8,p[2]):cameraAnchor.pose().p();
            cameraAnchor=new CameraAnchor(player,new Pose(eye,Pose.Q.euler(yaw,pitch,0)),yaw,pitch);
        }
    }
    static void animationState(Object state,double[] p,double[] previous,float step,float yaw,float pitch)throws Exception {
        if(!(step>0&&step<=1))throw new IOException("Invalid recorded client simulation delta: "+step);
        Native.call(state,"switch","void",previous[0],previous[1],previous[2]);
        Native.call(state,"new","void",p[0],p[1],p[2]);
        SourceAdapters.put(state,"assert",step);SourceAdapters.put(state,"af",yaw);SourceAdapters.put(state,"ae",pitch);
        SourceAdapters.put(state,"return",((Number)Native.field(state,"return")).doubleValue()+step);
    }
    static void validatePosition(Object player,float alpha,double[] expected)throws Exception {
        Object actual=Native.call(Native.type("576005245"),"char","153304200",player,alpha);
        String[] axes={"case","void","import"};
        for(int i=0;i<axes.length;i++)if(Math.abs(((Number)Native.field(actual,axes[i])).doubleValue()-expected[i])>1e-6)
            throw new IOException("Recorder native pose diverged from captured trajectory at alpha="+alpha+", axis="+i);
    }
}
