package replay;

import java.util.*;

/** Source keeps primary and secondary quest hints independently. */
final class QuestHints implements ReplayRender.QuestHud {
    record Clear(boolean primary) {}
    Object primary,secondary;
    Object game;SourceAdapters adapters;
    long primaryDraws,secondaryDraws;
    void bind(Object game,SourceAdapters adapters)throws Exception {
        for(String name:List.of("1630029313","1965382740","1074092844","1154824968","482650101"))adapters.verify(name);
        this.game=game;this.adapters=adapters;ReplayRender.questHud=this;
    }
    void unbind(){if(ReplayRender.questHud==this)ReplayRender.questHud=null;}
    void reset()throws Exception {primary=secondary=null;publish();}
    void clear(boolean isPrimary)throws Exception {if(isPrimary)primary=null;else secondary=null;publish();}
    void update(Object hint)throws Exception {
        boolean isPrimary=((Enum<?>)Native.field(hint,"implements")).name().equals("PRIMARY");
        if(isPrimary)primary=hint;else secondary=hint;publish();
    }
    void publish()throws Exception {SourceAdapters.put(Native.call(Native.type("1154824968"),"final","482650101"),"final",primary!=null?primary:secondary);}
    static int id(Object hint)throws Exception {return hint==null?-1:((Number)Native.field(hint,"else")).intValue();}
    int activeSecondary()throws Exception {
        Object player=Native.field(game,"e");if(player==null)return -1;
        Map<String,Object> extra=adapters.extras.get(Native.call(player,"float","1536302723"));
        Object value=extra==null?null:extra.get("active_side_quest");return value instanceof Number number?number.intValue():-1;
    }
    public int draw(Object renderer,Object position,int offset)throws Exception {
        if(primary!=null){int result=((Number)Native.call(renderer,"extends","int",primary,position,offset)).intValue();if(result!=offset)primaryDraws++;offset=result;}
        // Source 775314639.volatile chooses the active quest independently for
        // each hint category. Retain the native formatter, localization, goal
        // handlers and atlas renderer for the second source slot.
        if(secondary!=null&&id(secondary)==activeSecondary()){
            List<String> lines=new ArrayList<>();Native.call(secondary,"assert","java.util.List",lines,Native.field(game,"e"));
            List<Object> formatted=new ArrayList<>();for(String line:lines)formatted.add(Native.call(Native.type("1074092844"),"public","1254743678",line));
            int color=switch(((Enum<?>)Native.field(secondary,"implements")).name()){case "COMMON"->-6388786;case "PRIMARY"->-142746;case "DAILY"->-11615509;default->-1;};
            Object title=Native.call(Native.type("1074092844"),"public","1254743678",Native.field(secondary,"continue"));
            offset=((Number)Native.call(Native.type("1630029313"),"int","int",title,formatted,color,position,offset)).intValue();secondaryDraws++;
        }
        return offset;
    }
}
