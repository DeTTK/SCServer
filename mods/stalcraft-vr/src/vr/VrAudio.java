package vr;
import static vr.NativeAccess.*;

/** Native mixer/output/volume; event edges are sampled once, never once per eye. */
final class VrAudio {
    private static final java.util.Properties STOCK_SOUNDS=stockSounds();
    private final java.util.function.BiConsumer<WeaponSpec,String> sink;
    private static java.util.Properties stockSounds(){
        var sounds=new java.util.Properties();
        try(var source=VrAudio.class.getResourceAsStream("/native-action-sounds.properties")){
            if(source!=null)sounds.load(source);
        }catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}
        return sounds;
    }
    VrAudio(){this(null);}
    VrAudio(java.util.function.BiConsumer<WeaponSpec,String> sink){this.sink=sink;}
    szmods.api.ModContext context;
    private boolean warned,emptyHeld;
    private double previousBolt;
    private boolean boltMoving;
    int played;
    String last;
    void play(WeaponSpec spec,String cue){
        String sound="stalcraft_vr:"+spec.audio+"_"+cue;
        if(sink!=null){sink.accept(spec,cue);played++;last=sound;return;}
        String stock=STOCK_SOUNDS.getProperty(spec.audio+"_"+cue);
        if(stock!=null)sound=stock;
        try{Object mixer=call(type("1831969298"),"extends","1831969298");
            int voice=((Number)call(mixer,"new","int",sound,.75f,1f,null,false)).intValue();
            if(voice<0)throw new IllegalStateException("Sound not resolved: "+sound);
            played++;last=sound;
        }catch(Exception e){if(!warned&&context!=null){context.log("VR action audio: "+e);warned=true;}}
    }
    void update(WeaponSpec spec,Ak15 gun,Ak15.Result result,Ak15.Magazine before,boolean supported,Tracking tracking,boolean blocked){
        if(blocked||!tracking.focused()){emptyHeld=true;previousBolt=0;boltMoving=false;return;}
        switch(result.event()){
            case GRAB_WEAPON->play(spec,"draw");
            case RELEASE_WEAPON->play(spec,"holster");
            case REQUEST_MAGAZINE->play(spec,"take");
            case REMOVE_MAGAZINE->play(spec,"remove");
            case DROP_MAGAZINE->play(spec,"drop");
            case INSERT_MAGAZINE->play(spec,"seat");
            case OPEN_ACTION->play(spec,"open");
            case CLOSE_ACTION->play(spec,"close");
            case EMPTY_CYLINDER->play(spec,"remove");
            default->{}
        }
        if(before!=gun.magazine){if(gun.magazine==Ak15.Magazine.UNLATCHING)play(spec,"unlatch");if(gun.magazine==Ak15.Magazine.ALIGNING)play(spec,"align");}
        if(!supported&&result.supported())play(spec,"support");
        if(supported&&!result.supported()&&result.event()==Ak15.Event.NONE)play(spec,"support_release");
        double travel=result.boltTravel();
        if(travel>.002&&!boltMoving){play(spec,spec.pump()?"pump_back":spec.holdsCharge()?"charge":"bolt_pull");boltMoving=true;}
        if(gun.boltReleased())play(spec,spec.pump()?"pump_forward":"bolt_release");
        if(travel==0)boltMoving=false;previousBolt=travel;
        boolean empty=gun.mainHand()==1&&tracking.right().trigger()>.65;
        if(empty&&!emptyHeld&&gun.held&&!result.fire()&&(!gun.chambered||gun.serverAmmo==0))play(spec,"empty");
        emptyHeld=empty;
    }
    void reset(){emptyHeld=true;previousBolt=0;boltMoving=warned=false;}
}
