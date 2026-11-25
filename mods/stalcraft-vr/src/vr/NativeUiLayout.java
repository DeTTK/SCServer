package vr;
import static vr.NativeAccess.*;

/** Reads the active inventory layout, without changing native widget identities or scale. */
final class NativeUiLayout {
    record Layout(UiRegion main,UiRegion visible){}
    private static java.lang.reflect.Field floating,tooltips;
    static Layout inventory(Object menu,int width,int height)throws Exception {
        if(!type("1595982208").isInstance(menu))return null;
        Object root=call(menu,"enum","1235189211");if(root==null)return null;
        UiRegion result=null;
        // Direct children include scroll containers and floating native windows.
        // Descending into a scroller would include its clipped, offscreen contents.
        for(Object child:(Iterable<?>)get(root,"case")){
            if(!(boolean)call(child,"return","boolean"))continue;
            // The item popup host covers the whole window even with no popups.
            // Its empty scaffold is not part of the inventory's useful area.
            if(type("41947413").isInstance(child))continue;
            UiRegion bounds=bounds(child,width,height);if(bounds!=null)result=result==null?bounds:result.union(bounds);
        }
        if(result==null)return null;UiRegion main=result.padded(24,width,height),visible=main;
        for(Object child:(Iterable<?>)get(root,"case"))if(type("41947413").isInstance(child)&&(boolean)call(child,"return","boolean")){
            for(Object popup:(Iterable<?>)get(child,"case"))if((boolean)call(popup,"return","boolean")){
                UiRegion box=bounds(popup,width,height);if(box!=null)visible=visible.union(box.padded(12,width,height));
            }
        }
        // These fields are shadowed by inventory subclasses: use the verified
        // declaring class, not the nearest field with the same obfuscated name.
        if(floating==null){Class<?> screen=type("1222473535");floating=screen.getDeclaredField("native");tooltips=screen.getDeclaredField("const");floating.setAccessible(true);tooltips.setAccessible(true);}
        for(java.lang.reflect.Field field:new java.lang.reflect.Field[]{floating,tooltips})for(Object widget:(Iterable<?>)field.get(menu)){
            if(!(boolean)call(widget,"return","boolean"))continue;UiRegion box=bounds(widget,width,height);if(box!=null)visible=visible.union(box.padded(12,width,height));
        }
        return new Layout(main,visible);
    }
    private static UiRegion bounds(Object child,int width,int height)throws Exception{Object top=call(child,"extends","1500420601"),parent=call(child,"case","1500420601"),size=call(child,"switch","61438557");long x=((Number)get(top,"class")).longValue()+((Number)get(parent,"class")).longValue(),y=((Number)get(top,"abstract")).longValue()+((Number)get(parent,"abstract")).longValue();int w=((Number)get(size,"try")).intValue(),h=((Number)get(size,"enum")).intValue();return UiRegion.clipped(x,y,x+w,y+h,width,height);}
}
