package szserver.client;

import szserver.net.EnvironmentCommands.Weather;
import szserver.net.Wire;

/** Installed native global-clock packet, including the promoted weather side channel. */
public final class EnvironmentPackets {
    // A wall-clock companion immediately precedes the solar clock in the same ordered frame.
    private static final float WALL_CLOCK_MARKER=-.25f;
    private EnvironmentPackets(){}
    public static byte[] body(long worldTime,Weather weather,long nowMillis){long midnight=Math.subtractExact(nowMillis,Math.floorMod(nowMillis,86400000L));long ticks=(Math.floorMod(worldTime,24000L)+6000)%24000;long epoch=Math.addExact(midnight,ticks*3600);float speed=switch(weather){case RAIN->-1;case THUNDER->-2;default->0;};return Wire.bytes(out->{out.writeLong(epoch);out.writeFloat(speed);out.writeByte(0);out.writeUTF("UTC");});}
    public static byte[] frame(long worldTime,Weather weather,long nowMillis){return Wire.dynamic("637620157",body(worldTime,weather,nowMillis));}
    /** Ordinary native clock fields; weather is transported independently by the environment protocol. */
    public static byte[] clock(szserver.persistence.EnvironmentClock anchor,long nowMillis){
        long epoch=anchor.epochMillis(nowMillis);float speed=(float)(anchor.speed()*szserver.persistence.EnvironmentClock.SOLAR_SPEED);
        return Wire.concat(clockFrame(nowMillis,WALL_CLOCK_MARKER),clockFrame(epoch,speed));
    }
    private static byte[] clockFrame(long millis,float speed){
        return Wire.dynamic("637620157",Wire.bytes(out->{out.writeLong(millis);out.writeFloat(speed);out.writeByte(0);out.writeUTF("UTC");}));
    }
}
