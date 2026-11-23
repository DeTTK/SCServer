package szserver.net;

import java.util.*;
import szserver.inventory.*;

/** Native creative hints preserve their own bytes and never become survival placement. */
public final class NativeCreativeSlotTests {
    private static int checks;private static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    public static void main(String[] args){
        var slots=new CreativeGrantSlots();var ammo=new ItemStack(12714,60,0,new byte[0]);var placed=slots.place(List.of(ammo),0);slots.granted(placed);byte[] expected=InventoryPackets.stack(ammo);check(Arrays.equals(slots.nativeStack(0),expected),"granted native state retained");check(slots.factory(0)==12714,"factory matches bytes");byte[] returned=slots.nativeStack(0);returned[1]^=1;check(Arrays.equals(slots.nativeStack(0),expected),"reader cannot change hint");byte[] echo=expected.clone();slots.echo(1,echo);echo[1]^=1;check(Arrays.equals(slots.nativeStack(1),expected),"network buffer cannot change cached hint");slots.echo(1,null);check(slots.nativeStack(1)==null&&slots.factory(1)==-1,"clear removes state and factory together");check(slots.nativeStack(-1)==null&&slots.nativeStack(84)==null,"invalid selected slot has no authority");
        var profile=new WeaponProfile(2,List.of(12714),1,1,10,1,10,60,2.2,1,1,List.of(2.2,1.0,0.0));check(profile.reloadDuration(0)==2.2&&profile.reloadDuration(1)==1&&profile.reloadDuration(2)==0,"actual remaining rounds select original prepared timeline");boolean rejected=false;try{new WeaponProfile(2,List.of(12714),1,1,10,1,10,60,2.2,1,1,List.of(2.2));}catch(IllegalArgumentException expectedError){rejected=true;}check(rejected,"incomplete reload timeline rejected");
        System.out.println("PASS "+checks+" native creative state and reload timeline checks");
    }
}
