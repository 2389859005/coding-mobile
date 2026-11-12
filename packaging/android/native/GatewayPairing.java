package io.github.abyxs.codingremote;

import android.content.Context;
import java.io.IOException;
import org.json.JSONObject;

/** The QR endpoint is the computer gateway, never a model's Responses API. */
final class GatewayPairing {
    static JSONObject exchange(Context context,ConnectionProfiles.Transport net,JSONObject qr,JSONObject body)throws Exception {
        String address=qr.getString("origin");
        JSONObject gateway;
        try {gateway=net.request(address,"","/api/gateway",null);}
        catch(IOException error){
            if(error instanceof ConnectionProfiles.HttpFailure){
                int code=((ConnectionProfiles.HttpFailure)error).code;
                if(code==502||code==504)throw new IOException("已连接服务器，但服务器暂时无法连接电脑助手（HTTP "+code+"）。请打开电脑助手，并检查SSH连接状态。",error);
                if(code>=300&&code<500)throw new IOException("二维码入口未到达电脑助手（HTTP "+code+"）。请从电脑托盘退出旧助手，打开最新版后刷新二维码再扫描。",error);
            }
            String reason="网络连接失败";
            for(Throwable cause=error;cause!=null;cause=cause.getCause()){
                if(cause instanceof java.net.UnknownHostException){reason="手机 DNS 无法解析域名";break;}
                if(cause instanceof javax.net.ssl.SSLException){reason="HTTPS 安全连接失败，请检查证书或网络代理";break;}
                if(cause instanceof java.net.SocketTimeoutException){reason="连接超时，请检查手机网络与代理";break;}
                if(cause instanceof java.net.ConnectException){reason="无法连接服务器，请检查手机网络与代理";break;}
                if(cause instanceof java.net.SocketException){reason="网络连接被重置，请检查服务器域名与手机网络";break;}
            }
            throw new IOException("无法核验电脑网关："+reason+"。地址："+address+"/api/gateway",error);
        }
        String identity=gateway.optString("computerId");
        boolean recognized=gateway.optString("service").equals("coding-remote")||gateway.optString("name").equals("coding远程");
        if(!recognized||identity.isEmpty())throw new SecurityException("二维码入口指向了其他服务。请从电脑托盘退出旧助手，打开最新版后刷新二维码再扫描。");
        String expected=qr.optString("computerId");
        if(!expected.isEmpty()&&!expected.equals(identity))throw new SecurityException("二维码与当前电脑不一致，请在电脑首页刷新后重新扫描。");
        JSONObject result;
        try{result=ConnectionProfiles.request(context,net,address,"","/api/pair",body);}
        catch(Exception error){throw new IOException("已找到电脑助手，但配对未完成："+error.getMessage(),error);}
        if(!identity.equals(result.optString("computerId"))||result.optString("token").isEmpty())throw new SecurityException("配对回执无效，请刷新二维码重试。");
        return result;
    }
}
