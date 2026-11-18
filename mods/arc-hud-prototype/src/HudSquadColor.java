import java.util.*;
/** Uses the same member color slot and palette as the game's native party HUD. */
final class HudSquadColor {
 static final int SOLO=0xffdaddd6;
 static int read(Object player)throws Exception{
  Object manager=HudNative.get(Class.forName("1069186728"),"for");if(manager==null)return SOLO;
  Object party=HudNative.get(manager,"static");if(party==null)return SOLO;
  Map<?,?> members=(Map<?,?>)HudNative.get(party,"char");Object member=members.get(HudNative.call(player,"finally","java.lang.String"));if(member==null)return SOLO;
  int index=((Number)HudNative.get(member,"finally")).intValue();int[] colors=(int[])HudNative.get(Class.forName("527115043"),"void");return index>=0&&index<colors.length?colors[index]|0xff000000:SOLO;
 }
}
