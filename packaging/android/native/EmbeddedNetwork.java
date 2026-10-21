package io.github.abyxs.codingremote;
import android.content.Context;
import java.io.*;
import java.net.*;
import java.util.UUID;
import org.json.*;

/** Private userspace Tailscale helper shipped inside the APK, without a VPN app. */
final class EmbeddedNetwork {
    private static Process process;private static int port;private static String secret="",interfaces="";private static long checked;private static boolean running;private static long lastLogin=-30000;
    private static boolean interfaces(Context c)throws Exception {JSONArray rows=new JSONArray();java.util.Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();while(all!=null&&all.hasMoreElements()){NetworkInterface iface=all.nextElement();JSONArray addresses=new JSONArray();for(InterfaceAddress address:iface.getInterfaceAddresses()){String ip=address.getAddress().getHostAddress();int zone=ip.indexOf('%');if(zone>=0)ip=ip.substring(0,zone);addresses.put(ip+"/"+address.getNetworkPrefixLength());}rows.put(new JSONObject().put("Name",iface.getName()).put("Index",iface.getIndex()).put("MTU",iface.getMTU()).put("Up",iface.isUp()).put("Loopback",iface.isLoopback()).put("Addresses",addresses));}String value=rows.toString();if(value.equals(interfaces))return false;File directory=new File(c.getFilesDir(),"tailnet");directory.mkdirs();File temporary=new File(directory,"interfaces.tmp");try(FileOutputStream out=new FileOutputStream(temporary)){out.write(value.getBytes("UTF-8"));}java.nio.file.Files.move(temporary.toPath(),new File(directory,"interfaces.json").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);interfaces=value;return true;}
    static synchronized void stop(){if(process!=null)process.destroy();process=null;port=0;running=false;checked=0;}
    static synchronized void start(Context c)throws Exception {
        if(process!=null&&process.isAlive())return;File binary=new File(c.getApplicationInfo().nativeLibraryDir,"libtailnet.so");if(!binary.isFile())throw new IOException("本机架构缺少内置网络组件");
        File dir=new File(c.getFilesDir(),"tailnet");dir.mkdirs();interfaces="";interfaces(c);File credential=new File(dir,"local-secret");secret=UUID.randomUUID().toString()+UUID.randomUUID().toString();try(FileOutputStream out=new FileOutputStream(credential)){out.write(secret.getBytes("UTF-8"));}
        process=new ProcessBuilder(binary.getAbsolutePath(),"--state",dir.getAbsolutePath(),"--hostname","coding-phone","--secret-file",credential.getAbsolutePath()).redirectError(ProcessBuilder.Redirect.appendTo(new File(dir,"network.log"))).start();
        BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream(),"UTF-8"));String line=reader.readLine();if(line==null)throw new IOException("内置网络启动失败");port=new JSONObject(line).getInt("port");checked=0;
    }
    static synchronized JSONObject status(Context c,boolean login)throws Exception {start(c);if(interfaces(c))control("/network-changed",true);JSONObject status=control("/status",false);long now=android.os.SystemClock.elapsedRealtime();if(login&&status.optString("state").equals("NeedsLogin")&&status.optString("authUrl").isEmpty()&&now-lastLogin>=15000){lastLogin=now;control("/login",true);}running=status.optString("state").equals("Running");checked=System.currentTimeMillis();return status;}
    static String loginUrl(JSONObject status){try{String raw=status.optString("authUrl");URL url=new URL(raw);return !status.optString("state").equals("Running")&&url.getProtocol().equals("https")&&url.getHost().equals("login.tailscale.com")&&(url.getPort()==-1||url.getPort()==443)&&url.getUserInfo()==null?raw:"";}catch(Exception e){return "";}}
    static String describe(JSONObject status){switch(status.optString("state")){case "Running":return "已连接设备网络";case "NoState":return "正在联系设备网络";case "Starting":return "正在建立设备连接";case "NeedsLogin":return "等待完成浏览器登录";case "NeedsMachineAuth":return "等待管理员批准此设备";case "Stopped":return "设备网络已停止，请重新打开应用";default:return "正在读取设备网络状态";}}
    private static JSONObject control(String path,boolean post)throws Exception {HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+port+path).openConnection();connection.setRequestProperty("Authorization","Bearer "+secret);connection.setConnectTimeout(2000);connection.setReadTimeout(5000);if(post){connection.setRequestMethod("POST");connection.setDoOutput(true);connection.getOutputStream().close();}try{ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream input=connection.getInputStream()){byte[] b=new byte[4096];int n;while((n=input.read(b))!=-1)out.write(b,0,n);}return new JSONObject(out.toString("UTF-8"));}finally{connection.disconnect();}}
    static HttpURLConnection open(Context c,String address)throws Exception {
        URL url=new URL(address);if(!TrustedOrigin.valid(url.getProtocol()+"://"+url.getAuthority()))throw new IOException("不支持的连接地址");boolean privateAddress=url.getProtocol().equals("http")&&url.getHost().startsWith("100.");
        if(privateAddress&&c.getSharedPreferences("ui",0).getBoolean("embeddedNetwork",false)){
            int localPort=node(c);
            if(localPort>0){HttpURLConnection connection=(HttpURLConnection)url.openConnection(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",localPort)));connection.setRequestProperty("Proxy-Authorization","Bearer "+secret);return connection;}
            // The helper is not usable right now. Fall through to a direct
            // connection instead of failing: the OS may already route the private
            // address, and a false failure here is what the user sees as a drop.
        }return (HttpURLConnection)url.openConnection();
    }
    /** Drop the cached health so the next request re-reads the node state. */
    static synchronized void invalidate(){checked=0;}
    /** Local helper port, or 0 while it is not serving the private address. */
    private static int node(Context c){
        // Never hold the class lock across control-plane I/O: a single slow call
        // must not stall every other request on this device.
        if(running&&System.currentTimeMillis()-checked<8000)return port;
        try{JSONObject status=status(c,false);return status.optString("state").equals("Running")?port:0;}
        catch(Exception error){return running?port:0;}
    }
}
