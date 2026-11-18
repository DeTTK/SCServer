package szserver.commands;

import com.google.gson.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Immutable, content-addressed large voxel arrays. Only command/bootstrap workers perform IO. */
final class BlockStateArchive {
    static final int MAX_CELLS=2_000_000,INLINE_CELLS=8192;
    private static final int MAGIC=0x535a4253,VERSION=1,HEADER=12;
    private final Path directory,journal;
    @FunctionalInterface interface BeforePublish {void run(Path temporary)throws IOException;}
    private final BeforePublish beforePublish;
    BlockStateArchive(Path journal)throws IOException{this(journal,path->{});}
    BlockStateArchive(Path journal,BeforePublish beforePublish)throws IOException{
        Path source=journal.toAbsolutePath().normalize();this.journal=source;directory=source.resolveSibling(source.getFileName()+".block-state");this.beforePublish=beforePublish;
        if(Files.isSymbolicLink(directory))throw new IOException("block_archive_symbolic_link");Files.createDirectories(directory);
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("block_archive_directory");
    }
    Path directory(){return directory;}
    Path journal(){return journal;}
    static JsonElement memory(int[] values){if(values.length<=INLINE_CELLS)return BlockEdit.encode(values);var encoded=new JsonObject();encoded.addProperty("format","SZBS1-memory");encoded.addProperty("count",values.length);encoded.add("data",BlockEdit.encode(values));return encoded;}
    JsonElement encode(int[] values)throws IOException{
        if(values.length<1||values.length>MAX_CELLS)throw new IllegalArgumentException("block_archive_count");
        for(int value:values)if(value<0||value>65535)throw new IllegalArgumentException("invalid_edit_value");
        if(values.length<=INLINE_CELLS)return BlockEdit.encode(values);
        checkDirectory();Path temporary=directory.resolve("draft-"+UUID.randomUUID()+".tmp");
        try{
            MessageDigest digest=digest();
            try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
                var out=new DataOutputStream(new BufferedOutputStream(new DigestOutputStream(Channels.newOutputStream(channel),digest),65536));
                out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(values.length);for(int value:values)out.writeShort(value);out.flush();channel.force(true);
            }
            String hash=HexFormat.of().formatHex(digest.digest());JsonObject reference=new JsonObject();reference.addProperty("format","SZBS1");reference.addProperty("sha256",hash);reference.addProperty("count",values.length);reference.addProperty("bytes",HEADER+2L*values.length);
            Path destination=path(hash);beforePublish.run(temporary);
            if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS)){verify(reference,values.length);Files.delete(temporary);}
            else {try{Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE);}catch(FileAlreadyExistsException raced){verify(reference,values.length);Files.delete(temporary);}}
            // Journal admission follows only after the exact, durably written object is published.
            verify(reference,values.length);return reference;
        }finally{Files.deleteIfExists(temporary);}
    }
    int[] decode(JsonObject event,String field,int expected)throws IOException{
        JsonElement encoded=event.get(field);if(encoded!=null&&encoded.isJsonObject()){var object=encoded.getAsJsonObject();if(object.has("format")&&object.get("format").getAsString().equals("SZBS1-memory"))return memory(object,expected);return read(object,expected,true);}
        return BlockEdit.decode(event,field,expected);
    }
    void validateReferences(JsonObject event)throws IOException{
        for(String field:List.of("before","after","data")){JsonElement value=event.get(field);if(value!=null&&value.isJsonObject()){
            JsonObject reference=value.getAsJsonObject();int count=count(reference);if(reference.get("format").getAsString().equals("SZBS1-memory"))memory(reference,count);else verify(reference,count);
            if(event.has("cells")&&event.get("cells").getAsLong()!=count)throw new IOException("block_archive_event_count");
        }}
    }
    private static int[] memory(JsonObject object,int expected)throws IOException{int count=count(object);if(count!=expected||!object.keySet().equals(Set.of("format","count","data")))throw new IOException("block_memory_reference");JsonArray parts=object.getAsJsonArray("data");if(parts==null||parts.size()<1||parts.size()>(MAX_CELLS*8L/3+8191)/8192)throw new IOException("block_memory_parts");StringBuilder joined=new StringBuilder();for(var part:parts){if(!part.isJsonPrimitive()||!part.getAsJsonPrimitive().isString()||part.getAsString().length()>8192)throw new IOException("block_memory_parts");joined.append(part.getAsString());}byte[] raw=Base64.getDecoder().decode(joined.toString());if(raw.length!=count*2)throw new IOException("block_memory_count");int[] values=new int[count];var data=java.nio.ByteBuffer.wrap(raw);for(int i=0;i<count;i++)values[i]=data.getShort()&65535;return values;}
    private void verify(JsonObject reference,int count)throws IOException{read(reference,count,false);}
    private static int count(JsonObject reference)throws IOException{
        try{var number=reference.getAsJsonPrimitive("count");if(!number.isNumber())throw new IOException("block_archive_count");long count=number.getAsLong();if(!number.toString().equals(Long.toString(count))||count<1||count>MAX_CELLS)throw new IOException("block_archive_count");return (int)count;}catch(RuntimeException invalid){throw new IOException("block_archive_reference",invalid);}
    }
    private int[] read(JsonObject reference,int expected,boolean materialize)throws IOException{
        checkDirectory();int count=count(reference);String hash;
        try{
            if(!reference.keySet().equals(Set.of("format","sha256","count","bytes"))||!reference.get("format").getAsString().equals("SZBS1")||count!=expected)throw new IOException("block_archive_reference");
            hash=reference.get("sha256").getAsString();if(!hash.matches("[0-9a-f]{64}")||reference.get("bytes").getAsLong()!=HEADER+2L*count)throw new IOException("block_archive_reference");
        }catch(RuntimeException invalid){throw new IOException("block_archive_reference",invalid);}
        Path file=path(hash);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)!=HEADER+2L*count)throw new IOException("block_archive_missing_or_size");
        MessageDigest digest=digest();int[] result=materialize?new int[count]:null;
        try(InputStream stream=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS);var in=new DataInputStream(new BufferedInputStream(new DigestInputStream(stream,digest),65536))){
            if(in.readInt()!=MAGIC||in.readInt()!=VERSION||in.readInt()!=count)throw new IOException("block_archive_header");
            if(materialize)for(int i=0;i<count;i++)result[i]=in.readUnsignedShort();else{byte[] buffer=new byte[65536];long remaining=2L*count;while(remaining>0){int size=(int)Math.min(remaining,buffer.length);in.readFully(buffer,0,size);remaining-=size;}}
            if(in.read()!=-1)throw new IOException("block_archive_trailing");
        }
        if(!HexFormat.of().formatHex(digest.digest()).equals(hash))throw new IOException("block_archive_checksum");return result;
    }
    private void checkDirectory()throws IOException{if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(directory))throw new IOException("block_archive_directory");}
    private Path path(String hash){Path path=directory.resolve(hash+".bsb").normalize();if(!path.getParent().equals(directory))throw new IllegalArgumentException("block_archive_path");return path;}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
