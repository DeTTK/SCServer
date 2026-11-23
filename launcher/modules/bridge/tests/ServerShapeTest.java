package bscp.next;
/** Validate every strict production patch against the freshly compiled server. */
public final class ServerShapeTest {
 public static void main(String[] args)throws Exception {
  ServerPatches.verify(args[0]);System.out.println("Fresh server bridge patch shapes: PASS");
 }
}
