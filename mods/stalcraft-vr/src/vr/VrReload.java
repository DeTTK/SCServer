package vr;
import java.nio.*;
import static vr.NativeAccess.*;

/** Reliable authenticated protocol; server inventory is the only ammo authority. */
final class VrReload {
    static final int ENABLE=1,REMOVE=2,DROP=3,TAKE=4,SEAT=5,RACK=6,DISABLE=7,SHELL_TAKE=8,SHELL_SEAT=9,PUMP=10,EJECT=11,OPEN=12,CLOSE=13,EMPTY_CYLINDER=14,CHAMBER_SEAT_0=15,CHAMBER_SEAT_1=16,CYCLE_BACK=17,CYCLE_FORWARD=18;
    private Object connection;
    private Object sequenceConnection;
    private int sequence,rackSequence,sentEvent,lastReceived;
    private byte[] rackPayload;
    private long retryAt;
    interface Sender {void send(Object connection,byte[] payload)throws Exception;}
    private final Sender sender;
    private final java.util.function.LongSupplier clock;
    VrReload(){this(VrReload::nativeSend,System::nanoTime);}
    VrReload(Sender sender,java.util.function.LongSupplier clock){this.sender=sender;this.clock=clock;}
    boolean ready,failed;
    int rounds,chamberState,lastReplyEvent,replyVersion;
    boolean lastAccepted;
    long pending;
    boolean open(Object game)throws Exception {
        Object current=call(game,"transient","1219970978");
        if(current==null)return false;
        connect(current);retry();
        return true;
    }
    void connect(Object current)throws Exception {if(connection!=current){if(sequenceConnection!=current){sequence=0;sequenceConnection=current;}connection=current;lastReceived=0;ready=failed=false;rounds=0;rackSequence=sentEvent=0;pending=retryAt=0;rackPayload=null;send(ENABLE,0);}}
    void retry()throws Exception {if(connection!=null&&pending!=0&&rackPayload!=null&&sequence==rackSequence&&clock.getAsLong()>=retryAt){retryAt=clock.getAsLong()+1_000_000_000L;sender.send(connection,rackPayload);}}
    boolean send(int event,int ammo)throws Exception {
        if(connection==null)return false;
        int seq=++sequence;
        byte[] payload=ByteBuffer.allocate(11).order(ByteOrder.BIG_ENDIAN).putInt(0x56525731).putInt(seq).put((byte)event).putShort((short)ammo).array();
        sentEvent=event;sender.send(connection,payload);
        if(event!=ENABLE)ready=false;
        pending=retryAt=0;rackPayload=null;
        if(event==ENABLE||event==REMOVE)failed=false;
        if(event==RACK||event==PUMP||event==SHELL_SEAT||event==EJECT||event==OPEN||event==CLOSE||event==EMPTY_CYLINDER||event>=CHAMBER_SEAT_0&&event<=CYCLE_FORWARD){rackSequence=seq;pending=clock.getAsLong();retryAt=pending+1_000_000_000L;rackPayload=payload;failed=false;}
        return true;
    }
    private static void nativeSend(Object connection,byte[] payload)throws Exception {Object packet=type("szmods.bridge.VrWeapon").getConstructor(byte[].class).newInstance((Object)payload);Object envelope=type("385693344").getConstructor(type("499058674")).newInstance(packet);call(connection,"else","void",envelope);}
    void receive(Object source,byte[] payload){
        if(source!=connection||payload==null||payload.length!=15)return;
        ByteBuffer b=ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);if(b.getInt()!=0x56525731)return;
        int seq=b.getInt(),event=b.get()&255;boolean accepted=b.get()!=0;int phase=b.get()&255;int state=b.getShort()&65535;int ammo=b.getShort()&65535;
        // A reply to an obsolete manipulation cannot revoke a newer ready rack.
        if(seq<=0||seq!=sequence||event!=sentEvent||phase>5||seq==lastReceived)return;
        lastReceived=seq;
        lastReplyEvent=event;lastAccepted=accepted;chamberState=state;replyVersion++;
        if(seq==sequence&&(event==ENABLE||event==DROP||((event==RACK||event==PUMP||event==SHELL_SEAT||event==EJECT||event==OPEN||event==CLOSE||event==EMPTY_CYLINDER||event>=CHAMBER_SEAT_0&&event<=CYCLE_FORWARD)&&seq==rackSequence))){
            ready=accepted&&phase==0&&ammo>0;rounds=ammo;
            if(event!=ENABLE){pending=retryAt=0;rackPayload=null;failed=!accepted;}
        }else if(!accepted){ready=false;failed=true;}
    }
    void close()throws Exception {try{if(connection!=null)send(DISABLE,0);}finally{connection=null;ready=failed=false;pending=retryAt=0;rackPayload=null;}}
}
