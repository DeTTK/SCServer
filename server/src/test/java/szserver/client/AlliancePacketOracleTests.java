package szserver.client;
import szserver.net.*;import java.io.*;import java.nio.*;import java.nio.file.*;import java.lang.reflect.*;import java.util.*;
/** Explicit private-runtime oracle; no graphical client or owner loop is started. */
public final class AlliancePacketOracleTests {
    public static void main(String[] args)throws Exception{
        Path scratch=Path.of(args[1]);Files.createDirectories(scratch);Path directory=Files.createTempDirectory(scratch,"alliance-native-");int checks=0;
        try(AllianceState state=new AllianceState(directory);ClientPackets packets=new ClientPackets(Path.of(args[0]))){
            for(String request:java.util.List.of(AlliancePackets.JOIN)){byte[] body=Wire.bytes(out->out.writeUTF("duty"));packets.roundTrip(request,body);if(!AlliancePackets.faction(body).equals("duty"))throw new AssertionError("native request adapter");checks++;}
            state.set("duty","fee",76543).get();state.set("duty","farm",12.5).get();
            byte[] framed=Wire.readFrame(new ByteArrayInputStream(state.frame()));ByteBuffer buffer=ByteBuffer.wrap(framed);if(Wire.varint(buffer)!=1667)throw new AssertionError("packet ID");
            Field field=ClientPackets.class.getDeclaredField("loader");field.setAccessible(true);ClassLoader loader=(ClassLoader)field.get(packets);
            Class<?> type=Class.forName("957640561",true,loader);Object packet=type.getConstructor().newInstance();DataInputStream in=Wire.input(Arrays.copyOfRange(framed,buffer.position(),framed.length));
            NativeMethods.instance(type,"extends",void.class,DataInput.class).invoke(packet,in);if(in.available()!=0)throw new AssertionError("native packet trailing bytes");checks++;
            Map<?,?> values=(Map<?,?>)type.getField("return").get(packet);if(values.size()!=6)throw new AssertionError("six native alliances");checks++;
            for(String id:AllianceState.ALLIANCES){Object row=values.get(id);Object permission=row.getClass().getField("char").get(row);Class<?> data=permission.getClass();
                if(data.getField("case").getBoolean(permission)||data.getField("class").getBoolean(permission))throw new AssertionError("native recruitment flags");checks++;
                if(data.getField("byte").getInt(permission)!=(id.equals("duty")?76543:100000))throw new AssertionError("native fee");checks++;
                if(data.getField("break").getFloat(permission)!=(id.equals("duty")?12.5f:10f))throw new AssertionError("native farm percentage");checks++;
            }
            if(type.getField("throws").getBoolean(packet))throw new AssertionError("native live refresh flag");checks++;
            byte[] roundtrip=Wire.bytes(out->NativeMethods.instance(type,"switch",void.class,DataOutput.class).invoke(packet,out));packets.roundTrip("957640561",roundtrip);checks++;
        }finally{try(var walk=Files.walk(directory)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
        System.out.println("PASS "+checks+" canonical alliance packet decoding, fee, percentages, recruitment permissions and native roundtrip checks");
    }
}
