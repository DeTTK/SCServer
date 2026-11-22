package gamecompat;
import java.io.*;import java.nio.file.*;import java.util.*;
public final class PerspectivePolicyTests{
 static int checks;static void check(boolean value,String text){checks++;if(!value)throw new AssertionError(text);}
 static Map<String,byte[]> methods(byte[] bytes)throws Exception{
  var in=new DataInputStream(new ByteArrayInputStream(bytes));in.skipNBytes(8);String[] pool=new String[in.readUnsignedShort()];
  for(int i=1;i<pool.length;i++){int tag=in.readUnsignedByte();if(tag==1)pool[i]=in.readUTF();else{int size=switch(tag){case 3,4,9,10,11,12,17,18->4;case 5,6->8;case 7,8,16,19,20->2;case 15->3;default->throw new IOException("constant "+tag);};in.skipNBytes(size);if(tag==5||tag==6)i++;}}
  in.skipNBytes(6);in.skipNBytes(2L*in.readUnsignedShort());int fields=in.readUnsignedShort();for(int i=0;i<fields;i++){in.skipNBytes(6);int n=in.readUnsignedShort();for(int k=0;k<n;k++){in.skipNBytes(2);in.skipNBytes(Integer.toUnsignedLong(in.readInt()));}}
  Map<String,byte[]> result=new TreeMap<>();int n=in.readUnsignedShort();for(int i=0;i<n;i++){int at=bytes.length-in.available();in.readUnsignedShort();String name=pool[in.readUnsignedShort()]+pool[in.readUnsignedShort()];int attrs=in.readUnsignedShort();for(int k=0;k<attrs;k++){in.skipNBytes(2);in.skipNBytes(Integer.toUnsignedLong(in.readInt()));}result.put(name,Arrays.copyOfRange(bytes,at,bytes.length-in.available()));}return result;
 }
 public static void main(String[] args)throws Exception{
  byte[] original=Files.readAllBytes(Path.of(args[0]));var patch=new PerspectivePolicyTransformer();byte[] changed=patch.transform(null,"1389510037",null,null,original);check(changed!=null,"verified native base transformed");var before=methods(original);var after=methods(changed);check(before.keySet().equals(after.keySet()),"native API and method set retained");for(String name:before.keySet())check(Arrays.equals(before.get(name),after.get(name))!=name.equals("default()V"),"only policy method changed: "+name);
  check(patch.transform(null,"1389510037",null,null,changed)==null,"repeat transform leaves existing patch alone");check(patch.transform(null,"other",null,null,original)==null,"unrelated classes ignored");byte[] unknown=original.clone();unknown[unknown.length-1]^=1;check(patch.transform(null,"1389510037",null,null,unknown)==null,"unknown base retains stock behavior without invalid class bytes");System.out.println("PASS "+checks+" native camera policy, unchanged method bytes and version fallback checks");
 }
}
