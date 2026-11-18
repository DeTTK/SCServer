package szmods.runtime;

/** Accept only the two native item-binding prompts, including their bind packet. */
public final class ItemBindingUi {
    public static boolean accept(Object screen) {
        if(screen==null || !screen.getClass().getName().equals("980716871"))return false;
        Runnable action;
        try {
            Class<?> base=Class.forName("1858772939");
            String message=(String)base.getField("public").get(screen);
            String[] strings=(String[])Class.forName("1281655698").getField("if").get(null);
            var translate=Class.forName("255753289").getMethod("else",String.class);
            if(!message.equals(translate.invoke(null,strings[19874])) &&
               !message.equals(translate.invoke(null,strings[46934])))return false;
            action=(Runnable)base.getField("break").get(screen);
            if(action==null)return false;
            base.getField("break").set(screen,null);
        } catch(ReflectiveOperationException e) {
            System.err.println("[ItemBindingUi] Native binding dialog changed: "+e);
            return false;
        }
        action.run();
        return true;
    }
}
