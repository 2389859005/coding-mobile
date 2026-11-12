package io.github.abyxs.codingremote;
import android.net.Uri;

/** QR-approved HTTPS entrances and private-network HTTP addresses only. */
final class TrustedOrigin {
    static boolean tailnet(String address){try{Uri uri=Uri.parse(address);String[] parts=uri.getHost().split("\\.");return "http".equals(uri.getScheme())&&parts.length==4&&Integer.parseInt(parts[0])==100&&Integer.parseInt(parts[1])>=64&&Integer.parseInt(parts[1])<=127&&valid(address);}catch(Exception e){return false;}}
    static boolean scanOrigin(String address){return valid(address)&&(address.startsWith("https://")||tailnet(address));}
    static boolean valid(String address){try{Uri u=Uri.parse(address);String host=u.getHost();if(host==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||(u.getPath()!=null&&!u.getPath().isEmpty())||u.getPort()>65535)return false;
        if("https".equals(u.getScheme()))return u.getPort()!=0&&host.matches("[A-Za-z0-9.-]+")&&!host.startsWith(".")&&!host.endsWith(".");
        if(!"http".equals(u.getScheme())||u.getPort()<1024)return false;String[] p=host.split("\\.");if(p.length!=4)return false;
        for(String part:p)if(!part.matches("0|[1-9][0-9]{0,2}")||Integer.parseInt(part)>255)return false;int first=Integer.parseInt(p[0]),second=Integer.parseInt(p[1]);return first==10||first==192&&second==168||first==172&&second>=16&&second<=31||first==100&&second>=64&&second<=127;
    }catch(Exception e){return false;}}
}
