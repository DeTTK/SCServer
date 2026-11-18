package replay;

import java.io.*;

/** Source 975259204: native scene light data, in exact writer order. */
final class SourceLight {
    record State(float radius,float offset,float range,float innerAngle,float outerAngle,
                 float red,float green,float blue,float ambientRed,float ambientGreen,float ambientBlue,
                 boolean shadows,boolean enabled) {
        void apply(Object light)throws Exception {
            ReplayClock.field(light,"assert").setFloat(light,radius);
            ReplayClock.field(light,"new").setFloat(light,offset);
            ReplayClock.field(light,"extends").setFloat(light,range);
            ReplayClock.field(light,"double").setFloat(light,innerAngle);
            ReplayClock.field(light,"super").setFloat(light,outerAngle);
            color(Native.field(light,"if"),red,green,blue);
            color(Native.field(light,"volatile"),ambientRed,ambientGreen,ambientBlue);
            ReplayClock.field(light,"import").setBoolean(light,shadows);
            ReplayClock.field(light,"throws").setBoolean(light,enabled);
        }
    }
    private static void color(Object color,float r,float g,float b)throws Exception {
        ReplayClock.field(color,"assert").setFloat(color,r);
        ReplayClock.field(color,"byte").setFloat(color,g);
        ReplayClock.field(color,"catch").setFloat(color,b);
    }
    static State read(DataInput in)throws IOException {
        return new State(in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),
            in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readFloat(),in.readBoolean(),in.readBoolean());
    }
}
