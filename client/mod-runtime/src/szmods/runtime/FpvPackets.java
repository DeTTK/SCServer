package szmods.runtime;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;

/** Network decoding never touches camera, physics, or mod state. */
public final class FpvPackets {
    public static boolean rawCodec(Object packet) {
        String name=packet.getClass().getName();
        if(name.equals("szmods.bridge.FunnelImpact")||name.equals("szmods.bridge.FunnelImpactReady"))return true;
        if(name.equals("szmods.bridge.NearbyPlayers")||name.equals("szmods.bridge.NearbyPlayersReady")
                ||name.equals("szmods.bridge.ProfileEquipment")||name.equals("szmods.bridge.ProfileEquipmentReady"))return true;
        if(name.equals("szmods.bridge.WeaponAction")||name.equals("szmods.bridge.WeaponActionReady"))return true;
        if(name.equals("szmods.bridge.VehicleInput")||name.equals("szmods.bridge.VehicleSnapshot"))return true;
        if(name.equals("szmods.bridge.VrWeapon")||name.equals("szmods.bridge.VrWeaponReply"))return true;
        if(name.equals("szmods.bridge.CommandRequest")||name.equals("szmods.bridge.CommandCatalog")||name.equals("szmods.bridge.MedicineUse"))return true;
        if(name.equals("szmods.bridge.FpvInput")||name.equals("szmods.bridge.FpvSnapshot")||name.equals("szmods.bridge.PlayerAnimation"))return true;
        if(name.equals("385693344"))try {
            Object inner=packet.getClass().getField("import").get(packet);
            return inner!=null && rawCodec(inner);
        } catch(ReflectiveOperationException ignored) {}
        return false;
    }
    private record Received(Object connection, byte[] payload) {}
    private static final ArrayBlockingQueue<Received> incoming=new ArrayBlockingQueue<>(128);
    public static byte[] readSnapshot(DataInput input) throws IOException {
        byte[] fixed=new byte[72]; input.readFully(fixed);
        ByteBuffer b=ByteBuffer.wrap(fixed).order(ByteOrder.BIG_ENDIAN);
        if(b.getInt()!=0x46505631 || (fixed[4]!=2 && fixed[4]!=3 && fixed[4]!=7 && fixed[4]!=8)) throw new IOException("Invalid FPV snapshot header");
        b.position(21);
        if(b.getInt() < -1) throw new IOException("Invalid FPV sequence");
        for(int i=0;i<11;i++) if(!Float.isFinite(b.getFloat())) throw new IOException("Nonfinite FPV snapshot");
        if((fixed[69]&255)>1 || (fixed[70]&255)>1) throw new IOException("Invalid FPV flags");
        int n=fixed[71]&255;
        if(n>16) throw new IOException("Invalid FPV event length");
        byte[] payload=Arrays.copyOf(fixed,72+n); input.readFully(payload,72,n);
        String event=new String(payload,72,n,StandardCharsets.US_ASCII);
        if((fixed[4]==2 || fixed[4]==7) ? n!=0 : !(event.equals("landed")||event.equals("exploded")||event.equals("recalled")||event.equals("placed")||event.equals("connected")||event.equals("detached")||event.equals("denied")))
            throw new IOException("Invalid FPV event");
        if(fixed[4]==7 || fixed[4]==8) {
            payload=Arrays.copyOf(payload,88+n);input.readFully(payload,72+n,16);
            ByteBuffer motors=ByteBuffer.wrap(payload,72+n,16).order(ByteOrder.BIG_ENDIAN);
            for(int i=0;i<4;i++){float rpm=motors.getFloat();if(!Float.isFinite(rpm)||rpm<0||rpm>1)throw new IOException("Invalid drone RPM");}
        }
        return payload;
    }
    public static void receive(Object connection, byte[] payload) {
        if(!incoming.offer(new Received(connection,payload.clone())))
            throw new IllegalStateException("FPV receive queue overflow");
    }
    public static byte[] readAnimation(DataInput input) throws IOException {
        int n=input.readUnsignedByte(); if(n>63) throw new IOException("Invalid animation name");
        byte[] value=new byte[n]; input.readFully(value); return value;
    }
    public static void receiveAnimation(Object connection, byte[] payload) {
        AnimationHooks.receive(connection,payload);
    }
    static void drain() {
        WeaponAction.drain();
        VehiclePackets.drain();
        VrPackets.drain();
        for(int i=0;i<128;i++) {
            Received value=incoming.poll(); if(value==null) break;
            ModLoader.clientHook("network.fpv.snapshot.v1",value.connection,value.payload);
        }
    }
}
