package vr;
import java.nio.*;
import java.util.*;

/** Wire-level reply ordering, retry identity and connection authority. */
public final class VrReloadProtocolProbe {
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static byte[] reply(int seq,int event,boolean accepted,int phase,int ammo){return ByteBuffer.allocate(15).order(ByteOrder.BIG_ENDIAN).putInt(0x56525731).putInt(seq).put((byte)event).put((byte)(accepted?1:0)).put((byte)phase).putShort((short)12728).putShort((short)ammo).array();}
 static int sequence(byte[] payload){return ByteBuffer.wrap(payload).getInt(4);}
 public static void main(String[] args)throws Exception{
  for(int operation=1;operation<=18;operation++)for(int phase=0;phase<=5;phase++){byte[] packet=reply(1,operation,true,phase,3);check(Arrays.equals(packet,szmods.runtime.VrPackets.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(packet)))),"Canonical bridge rejects supported operation/phase");}
  List<byte[]> sent=new ArrayList<>();long[] clock={1_000_000_000L};Object connection=new Object(),foreign=new Object();VrReload reload=new VrReload((owner,payload)->{check(owner==connection||owner==foreign,"Unknown transport");sent.add(payload.clone());},()->clock[0]);
  reload.connect(connection);check(sent.size()==1&&sent.get(0).length==11&&sequence(sent.get(0))==1,"Enable wire payload");reload.receive(connection,reply(1,VrReload.ENABLE,true,0,5));check(reload.ready&&reload.rounds==5,"Initial server ammunition");
  reload.send(VrReload.RACK,0);reload.receive(connection,reply(2,VrReload.RACK,false,0,5));check(!reload.ready&&reload.failed&&reload.pending==0,"Premature rack rejected");
  for(int event:new int[]{VrReload.REMOVE,VrReload.DROP,VrReload.TAKE,VrReload.SEAT})reload.send(event,event==VrReload.TAKE?12728:0);reload.send(VrReload.RACK,12728);
  byte[] rack=sent.get(sent.size()-1).clone();int seq=sequence(rack),before=sent.size();check(!reload.ready&&reload.pending!=0,"Rack requires authoritative reply");
  clock[0]+=999_999_999L;reload.retry();check(sent.size()==before,"Retry must be paced");clock[0]++;reload.retry();check(sent.size()==before+1&&Arrays.equals(sent.get(sent.size()-1),rack),"Retry must keep identical sequence and payload");reload.retry();check(sent.size()==before+1,"No per-frame retry flood");
  reload.receive(foreign,reply(seq,VrReload.RACK,true,0,30));reload.receive(connection,reply(seq,VrReload.SEAT,true,0,30));reload.receive(connection,reply(seq,VrReload.RACK,true,9,30));check(!reload.ready&&reload.pending!=0,"Reject foreign/wrong-operation/invalid-phase replies");
  reload.receive(connection,reply(seq,VrReload.RACK,true,0,30));check(reload.ready&&!reload.failed&&reload.pending==0,"Authoritative rack ready");
  reload.receive(connection,reply(2,VrReload.RACK,false,0,5));check(reload.ready&&!reload.failed&&reload.rounds==30,"Obsolete denial must not disarm a newer successful rack");clock[0]+=5_000_000_000L;reload.retry();check(sent.size()==before+1,"Acknowledged rack must stop retrying");
  reload.send(VrReload.REMOVE,0);reload.receive(connection,reply(seq,VrReload.RACK,true,0,30));check(!reload.ready,"Old success must not rearm a removed magazine");reload.send(VrReload.RACK,0);before=sent.size();reload.send(VrReload.DROP,0);clock[0]+=5_000_000_000L;reload.retry();check(sent.size()==before+1&&reload.pending==0,"A new manipulation cancels old rack retries");
  reload.connect(foreign);int reconnectSeq=sequence(sent.get(sent.size()-1));check(reconnectSeq==1&&!reload.ready,"New connection resets sequence/authority");reload.receive(connection,reply(1,VrReload.ENABLE,true,0,99));check(!reload.ready,"Old connection cannot arm new session");reload.receive(foreign,reply(1,VrReload.ENABLE,true,0,3));check(reload.ready&&reload.rounds==3,"New authenticated connection may arm");
  reload.close();before=sent.size();clock[0]+=5_000_000_000L;reload.retry();check(sent.size()==before&&!reload.ready&&reload.pending==0,"Close cancels outstanding retries");
  reload.connect(foreign);int nextWeaponSeq=sequence(sent.get(sent.size()-1));check(nextWeaponSeq>reconnectSeq&&!reload.ready&&reload.rounds==0,"Weapon switch keeps stream sequence and clears ammunition");reload.receive(foreign,reply(reconnectSeq,VrReload.ENABLE,true,0,30));check(!reload.ready&&reload.rounds==0,"Old weapon enable cannot arm secondary");reload.receive(foreign,reply(nextWeaponSeq,VrReload.ENABLE,true,0,8));check(reload.ready&&reload.rounds==8,"Secondary enable uses own server ammunition");
  for(int operation:new int[]{VrReload.CHAMBER_SEAT_0,VrReload.CHAMBER_SEAT_1,VrReload.CYCLE_BACK,VrReload.CYCLE_FORWARD}){
   reload.send(operation,0);byte[] request=sent.get(sent.size()-1).clone();int current=sequence(request);int count=sent.size();clock[0]+=1_000_000_000L;reload.retry();check(sent.size()==count+1&&Arrays.equals(request,sent.get(sent.size()-1)),"Physical manipulation retains its exact reliable sequence");
   byte[] packet=reply(current,operation,true,operation==VrReload.CYCLE_FORWARD?0:5,1);ByteBuffer.wrap(packet).putShort(11,(short)2);reload.receive(foreign,packet);check(reload.chamberState==2&&reload.lastAccepted&&reload.pending==0,"Independent chamber bits arrive intact");int version=reload.replyVersion;reload.receive(foreign,packet);check(reload.replyVersion==version,"Duplicate ACK cannot resurrect an already fired shell");
  }
  System.out.println("PASS reload wire ordering, latest operation and connection authority, paced identical rack/slot/extraction retries, duplicate chamber ACK rejection and clean close");
 }
}
