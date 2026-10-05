package io.github.abyxs.codingremote;
import android.net.Uri;

/** Plain HTTP is limited to WireGuard-encrypted Tailscale device addresses. */
final class TrustedOrigin {
    static boolean valid(String address){try{Uri u=Uri.parse(address);String host=u.getHost();if(host==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||(u.getPath()!=null&&!u.getPath().isEmpty())||u.getPort()>65535)return false;
        if("https".equals(u.getScheme()))return host.endsWith(".trycloudflare.com")||host.endsWith(".ts.net");
        if(!"http".equals(u.getScheme())||u.getPort()<1024)return false;String[] p=host.split("\\.");if(p.length!=4||!p[0].equals("100"))return false;
        for(String part:p)if(!part.matches("0|[1-9][0-9]{0,2}")||Integer.parseInt(part)>255)return false;int second=Integer.parseInt(p[1]);return second>=64&&second<=127;
    }catch(Exception e){return false;}}
}
