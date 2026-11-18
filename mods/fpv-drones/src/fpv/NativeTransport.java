package fpv;
import static fpv.NativeFlight.call;

/** Sends through the existing authenticated game connection on the game thread. */
final class NativeTransport {
    static boolean sendInput(Object game, byte[] payload) throws Exception {
        validate(payload);
        Object connection=call(game,"transient","1219970978");
        if(connection==null) return false;
        Object packet=Class.forName("szmods.bridge.FpvInput").getConstructor(byte[].class).newInstance((Object)payload.clone());
        Object envelope=Class.forName("385693344").getConstructor(Class.forName("499058674")).newInstance(packet);
        call(connection,"else","void",envelope);
        return true;
    }
    static void validate(byte[] payload){
        if(payload==null||payload.length<5||payload[0]!='F'||payload[1]!='P'||payload[2]!='V'||payload[3]!='1')throw new IllegalArgumentException("Invalid FPV header");
        int length=switch(payload[4]){case 1->43;case 4,5,6,10->5;case 9->89;case 11->37;case 12->25;case 13->21;default->-1;};
        if(payload.length!=length)throw new IllegalArgumentException("Invalid FPV input length");
    }
}
