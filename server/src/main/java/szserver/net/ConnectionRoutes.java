package szserver.net;

import java.util.*;

/** Immutable advertised endpoints; host parsing never performs DNS or filesystem access. */
public record ConnectionRoutes(String bindAddress,List<String> hosts,int gatewayPort,int worldPort) {
    public ConnectionRoutes {
        hosts=List.copyOf(hosts);if(hosts.isEmpty()||hosts.size()>16||gatewayPort<0||gatewayPort>65535||worldPort<0||worldPort>65535)throw new IllegalArgumentException("game_routes_bounds");
        if(!bindAddress.equals("0.0.0.0")&&!bindAddress.equals("::")&&!Objects.equals(configHost(bindAddress),bindAddress))throw new IllegalArgumentException("game_bind_address");
        for(String host:hosts)if(!Objects.equals(configHost(host),host))throw new IllegalArgumentException("game_advertised_host");
    }
    public static ConnectionRoutes loopback(int gatewayPort,int worldPort){return new ConnectionRoutes("127.0.0.1",List.of("127.0.0.1"),gatewayPort,worldPort);}
    public static ConnectionRoutes configured(Properties properties,int gatewayPort,int worldPort){
        String bind=properties.getProperty("game.bindAddress","127.0.0.1").strip();if(!bind.equals("0.0.0.0")&&!bind.equals("::")){bind=configHost(bind);if(bind==null)throw new IllegalArgumentException("game_bind_address");}
        Set<String> hosts=new LinkedHashSet<>();for(String value:properties.getProperty("game.advertisedHosts","127.0.0.1").split(",",-1)){String host=configHost(value.strip());if(host==null)throw new IllegalArgumentException("game_advertised_host");hosts.add(host);}
        return new ConnectionRoutes(bind,List.copyOf(hosts),port(properties,"game.advertisedGatewayPort",gatewayPort),port(properties,"game.advertisedWorldPort",worldPort));
    }
    private static int port(Properties properties,String key,int fallback){int value=Integer.parseInt(properties.getProperty(key,Integer.toString(fallback)));if(value<1||value>65535)throw new IllegalArgumentException("game_advertised_port:"+key);return value;}
    public String primary(){return hosts.get(0);}
    public List<String> addresses(String preferred){return (preferred==null?hosts:List.of(preferred)).stream().map(host->endpoint(host,gatewayPort)).toList();}
    public static String endpoint(String host,int port){return (host.indexOf(':')>=0?"["+host+"]":host)+":"+port;}
    public static String configHost(String value){if(value==null||value.isEmpty())return null;return requestHost(value.indexOf(':')>=0?"["+value+"]":value);}
    /** Equivalent to the old request_host boundary: reject schemes, credentials, paths and invalid ports. */
    public static String requestHost(String authority){
        if(authority==null||authority.isEmpty()||authority.length()>1024||authority.codePoints().anyMatch(c->c==0x85||Character.isWhitespace(c)||Character.isSpaceChar(c)||"/\\?#@".indexOf(c)>=0))return null;
        String host,port=null;
        if(authority.startsWith("[")){int close=authority.indexOf(']');if(close<2)return null;host=authority.substring(1,close);String tail=authority.substring(close+1);if(!tail.isEmpty()){if(tail.charAt(0)!=':')return null;port=tail.substring(1);}host=ipv6(host);if(host==null)return null;}
        else{int colon=authority.indexOf(':');if(colon>=0){if(authority.indexOf(':',colon+1)>=0)return null;host=authority.substring(0,colon);port=authority.substring(colon+1);}else host=authority;if(host.isEmpty()||host.length()>253)return null;
            String numeric=ipv4(host);if(numeric!=null){int first=Integer.parseInt(numeric.substring(0,numeric.indexOf('.')));if(numeric.equals("0.0.0.0")||first>=224&&first<=239)return null;host=numeric;}
            else{String labels=host;while(labels.endsWith("."))labels=labels.substring(0,labels.length()-1);if(labels.isEmpty())return null;for(String label:labels.split("\\.",-1))if(!label.matches("[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?"))return null;host=host.toLowerCase(Locale.ROOT);}
        }
        if(port!=null&&!port.isEmpty()){if(!port.matches("[0-9]+"))return null;port=port.replaceFirst("^0+(?!$)","");if(port.length()>5)return null;try{if(Integer.parseInt(port)>65535)return null;}catch(NumberFormatException invalid){return null;}}
        return host;
    }
    private static String ipv4(String value){String[] parts=value.split("\\.",-1);if(parts.length!=4)return null;for(String part:parts){if(!part.matches("0|[1-9][0-9]{0,2}")||Integer.parseInt(part)>255)return null;}return value;}
    private static String ipv6(String value){
        String scope="";int percent=value.indexOf('%');if(percent>=0){scope=value.substring(percent);value=value.substring(0,percent);if(scope.length()<2||scope.indexOf('%',1)>=0||scope.indexOf(']')>=0||scope.indexOf(':')>=0)return null;}
        if(!value.matches("[0-9a-fA-F:.]+"))return null;
        if(value.indexOf('.')>=0){int colon=value.lastIndexOf(':');if(colon<0)return null;String dotted=ipv4(value.substring(colon+1));if(dotted==null)return null;String[] bytes=dotted.split("\\.");value=value.substring(0,colon+1)+Integer.toHexString(Integer.parseInt(bytes[0])*256+Integer.parseInt(bytes[1]))+":"+Integer.toHexString(Integer.parseInt(bytes[2])*256+Integer.parseInt(bytes[3]));}
        int gap=value.indexOf("::");if(gap>=0&&value.indexOf("::",gap+2)>=0)return null;
        String left=gap<0?value:value.substring(0,gap),right=gap<0?"":value.substring(gap+2);String[] a=left.isEmpty()?new String[0]:left.split(":",-1),b=right.isEmpty()?new String[0]:right.split(":",-1);
        if(gap<0&&a.length!=8||gap>=0&&a.length+b.length>=8)return null;int[] words=new int[8];int at=0;
        for(String word:a){if(!word.matches("[0-9a-fA-F]{1,4}"))return null;words[at++]=Integer.parseInt(word,16);}at=8-b.length;for(String word:b){if(!word.matches("[0-9a-fA-F]{1,4}"))return null;words[at++]=Integer.parseInt(word,16);}
        if(Arrays.stream(words).allMatch(word->word==0)||(words[0]&0xff00)==0xff00)return null;
        int best=-1,length=1;for(int i=0;i<8;){if(words[i]!=0){i++;continue;}int end=i;while(end<8&&words[end]==0)end++;if(end-i>length){best=i;length=end-i;}i=end;}
        StringBuilder result=new StringBuilder();for(int i=0;i<8;){if(i==best){result.append("::");i+=length;continue;}if(result.length()>0&&result.charAt(result.length()-1)!=':')result.append(':');result.append(Integer.toHexString(words[i++]));}return result+scope;
    }
}
