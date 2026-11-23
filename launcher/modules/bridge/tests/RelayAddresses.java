public final class RelayAddresses {
    public static int game(){return new java.net.InetSocketAddress("127.0.0.1",29546).getPort();}
    public static int world()throws Exception{return new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(),29547).getPort();}
    public static int unchanged(){return new java.net.InetSocketAddress("127.0.0.1",443).getPort();}
}
