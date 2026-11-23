/** Resolve every generated dynamic packet without initializing the game. */
public final class BootstrapAbiTest {
 public static void main(String[] args)throws Exception {
  for(String name:new String[]{"LegacyAdapter","CommandRequest","CommandCatalog","FpvInput","FpvSnapshot","PlayerAnimation","MedicineUse","VehicleInput","VehicleSnapshot","VrWeapon","VrWeaponReply"})Class.forName("szmods.bridge."+name,false,BootstrapAbiTest.class.getClassLoader());
  Class.forName("exbo.stalcraft.engine.model.lktk.JNI",false,BootstrapAbiTest.class.getClassLoader());
  System.out.println("Bootstrap generated packet ABI: PASS");
 }
}
