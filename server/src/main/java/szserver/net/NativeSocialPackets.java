package szserver.net;

import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import szserver.client.ClientPackets;

/** The shipped client's party, notifications, and profile RPC. */
final class NativeSocialPackets {
    record Member(String name, UUID character, int color) {}
    record Position(String name,float x,float z,float yaw,float health) {}

    static byte[] roster(long id,String leader,String location,List<Member> members,List<String> invited) {
        return Wire.dynamic("79023142",Wire.bytes(out->{
            out.writeLong(id);out.writeInt(0); // OPENWORLD_PARTY
            out.writeInt(invited.size());for(String name:invited)out.writeUTF(name);
            out.writeInt(members.size());
            for(Member member:members){
                out.writeBoolean(false);out.writeUTF(GamePackets.WORLD_KEY);
                out.writeUTF(member.name());out.writeUTF("");
                out.writeBoolean(false);out.writeLong(member.character().getMostSignificantBits());
                out.writeLong(member.character().getLeastSignificantBits());
                out.writeBoolean(false);out.writeByte(0);out.writeByte(255);out.writeUTF(location);out.writeUTF("");
                out.writeInt(member.color());out.writeShort(0);out.writeByte(0);out.writeInt(0);
            }
            out.writeUTF(leader);
        }));
    }

    static byte[] clear() {return Wire.dynamic("370412294",new byte[0]);}

    static byte[] positions(List<Position> positions) {
        return Wire.dynamic("385491619",Wire.bytes(out->{
            out.writeInt(positions.size());
            for(Position position:positions){
                out.writeUTF(position.name());out.writeFloat(position.x());out.writeFloat(position.z());
                out.writeByte((int)(position.yaw()*256f/360f));
                out.writeByte((int)(Math.max(0f,Math.min(1f,position.health()))*255f));
                out.writeByte(0);out.writeBoolean(false);
            }
        }));
    }

    static byte[] invitation(long id,String from,long now) {
        return Wire.dynamic("56596781",Wire.bytes(out->{
            out.writeInt(0);out.writeUTF("party");out.writeLong(now);out.writeLong(now+60_000);
            out.writeByte(10);out.writeUTF("");
            tag(out,"type","invited");tag(out,"Player",from);
            out.writeByte(4);out.writeUTF("PartyId");out.writeLong(id);
            out.writeByte(0);
        }));
    }

    static byte[] equipment(String player,List<String> titles) {
        return Wire.dynamic("szmods.bridge.ProfileEquipment",Wire.bytes(out->{
            out.writeUTF(player);out.writeByte(titles.size());
            for(String title:titles)out.writeUTF(title);
        }));
    }

    private static void tag(DataOutput out,String key,String value)throws IOException {
        out.writeByte(8);out.writeUTF(key);out.writeUTF(value);
    }

    static void verify(ClientPackets client)throws Exception {
        for(byte[] frame:List.of(
            roster(1,"Player","BSCPCRAFT",List.of(new Member("Player",new UUID(0,1),0)),List.of("Guest")),
            positions(List.of(new Position("Guest",1,2,90,1))),
            invitation(1,"Player",10_000),clear())){
            var framed=ByteBuffer.wrap(frame);
            Wire.varint(framed);
            if(Wire.varint(framed)!=5)throw new IOException("invalid_social_frame");
            var payload=Wire.input(Arrays.copyOfRange(frame,framed.position(),frame.length));
            String type=payload.readUTF();
            client.roundTrip(type,payload.readAllBytes());
        }
    }

    private NativeSocialPackets() {}
}
