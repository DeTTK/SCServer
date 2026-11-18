package replay;

import java.io.*;

/** An individual creation message, independent of any preceding packet sequence. */
final class SceneSpawnRecipe {
    static final int MAX_BODY=32*1024*1024;
    static SceneTracks.Bytes encode(DemoFile.Event event)throws IOException {
        validate(event);var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.writeLong(event.timeNs());out.writeInt(event.connection());out.writeInt(event.stream());out.writeInt(event.sourceId());out.writeInt(event.targetId());out.writeInt(event.sequence());out.writeInt(event.offset());byte[] body=event.body();out.writeInt(body.length);out.write(body);
        return new SceneTracks.Bytes(bytes.toByteArray());
    }
    static DemoFile.Event decode(SceneTracks.Bytes recipe)throws IOException {
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(recipe.data()))){
            long time=in.readLong();int connection=in.readInt(),stream=in.readInt(),source=in.readInt(),target=in.readInt(),sequence=in.readInt(),offset=in.readInt(),length=in.readInt();
            if(length<0||length>MAX_BODY||length!=in.available())throw new IOException("Invalid scene spawn recipe length");
            DemoFile.Event event=new DemoFile.Event(time,connection,stream,true,source,target,sequence,offset,in.readNBytes(length));validate(event);return event;
        }
    }
    static void validate(DemoFile.Event event)throws IOException {
        if(!event.incoming()||event.timeNs()<0||event.connection()<0||event.stream()<0||event.sourceId()<0||event.targetId()<0||event.offset()<0||event.offset()>event.body().length||event.body().length>MAX_BODY)throw new IOException("Invalid scene spawn recipe metadata");
    }
}
