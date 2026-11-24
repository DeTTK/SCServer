/** Resolve every generated dynamic packet without initializing the game. */
public final class BootstrapAbiTest {
 public static void main(String[] args)throws Exception {
  for(String name:new String[]{"LegacyAdapter","CommandRequest","CommandCatalog","FpvInput","FpvSnapshot","PlayerAnimation","MedicineUse","VehicleInput","VehicleSnapshot","VrWeapon","VrWeaponReply"})Class.forName("szmods.bridge."+name,false,BootstrapAbiTest.class.getClassLoader());
  Class.forName("exbo.stalcraft.engine.model.lktk.JNI",false,BootstrapAbiTest.class.getClassLoader());
  try (var input = CustomLoadingScreen.class.getResourceAsStream("/szcustom/loading.gif")) {
   if (input == null) throw new AssertionError("Missing startup GIF in bootstrap.jar");
   if (CustomLoadingScreen.decode(input).frames.length < 2) throw new AssertionError("Startup GIF is not animated");
  }
  System.out.println("Bootstrap generated packet ABI: PASS");
  System.out.println("Packaged startup animation: PASS");
 }
}
