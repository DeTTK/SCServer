package replay;

import java.io.*;

/** Source 811390992: a profile selection, not a world sound playback command. */
final class SourceMusic {
    record Selection(int collection,int track,int startSeconds,int spanSeconds) {
        int endSeconds(){return startSeconds+spanSeconds;}
        boolean empty(){return collection<=0||track<=0||spanSeconds<=0;}
        void write(DataOutput output)throws IOException {
            output.writeByte(1);output.writeShort(collection);output.writeShort(track);
            output.writeShort(startSeconds);output.writeShort(spanSeconds);
        }
    }
    static Selection read(DataInput input)throws IOException {
        if(input.readUnsignedByte()!=1)return new Selection(0,0,0,0);
        return new Selection(input.readUnsignedShort(),input.readUnsignedShort(),input.readUnsignedShort(),input.readUnsignedShort());
    }
}
