package replay;

import java.util.*;

/** Source EntityEfk scale on the existing native effect transform. */
final class SourceEffects implements ReplayRender.EffectTransform {
    final SourceAdapters adapters;
    static final class Scale {float value;double time;Scale(float value,double time){this.value=value;this.time=time;}}
    final Map<Object,Scale> scales=new WeakHashMap<>();
    SourceEffects(SourceAdapters adapters){this.adapters=adapters;}
    void bind()throws Exception {
        for(String name:List.of("1544747408","447252483","1366534706","221705413"))adapters.verify(name);
        scales.clear();ReplayRender.effectTransform=this;
    }
    void unbind(){if(ReplayRender.effectTransform==this)ReplayRender.effectTransform=null;scales.clear();}
    public Object transform(Object matrix,Object entity,boolean initial)throws Exception {
        Object attributes=Native.call(entity,"float","1536302723");Map<String,Object> extra=adapters.extras.get(attributes);
        Object recorded=extra==null?null:extra.get("EntityEfk_scale");float target=recorded==null?1:((Number)recorded).floatValue();
        if(!Float.isFinite(target))throw new IllegalArgumentException("Invalid recorded effect scale");
        double now=ReplayClock.seconds;Scale scale=scales.get(entity);
        if(initial||scale==null){scale=new Scale(target,now);scales.put(entity,scale);}
        else{
            // The source uses exponential smoothing with a one-second half-life.
            double delta=Math.max(0,now-scale.time);scale.value=(float)(target+(scale.value-target)*Math.pow(2,-delta));scale.time=now;
        }
        return Native.call(matrix,"enum","447252483",scale.value);
    }
}
