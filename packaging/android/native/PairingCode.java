package io.github.abyxs.codingremote;

import android.net.Uri;
import org.json.JSONObject;

/** Accept both historical QR JSON and the gateway's one-time pairing links. */
final class PairingCode {
    static JSONObject parse(String raw)throws Exception {
        raw=raw.trim();
        if(raw.startsWith("{")){
            JSONObject qr=new JSONObject(raw);
            if(!"coding-remote".equals(qr.optString("app"))||qr.optInt("v")!=1)throw new IllegalArgumentException("请使用 coding远程配对码");
            if(qr.optDouble("expires",0)<System.currentTimeMillis()/1000.0)throw new IllegalArgumentException("二维码已过期，请在电脑刷新");
            validate(qr);return qr;
        }
        Uri uri=Uri.parse(raw);String fragment=uri.getEncodedFragment();
        if(uri.getEncodedQuery()!=null||uri.getUserInfo()!=null||!(uri.getPath()==null||uri.getPath().isEmpty()||uri.getPath().equals("/"))||fragment==null||!fragment.matches("pair=[A-Za-z0-9_-]{32,128}"))throw new IllegalArgumentException("请粘贴完整配对码或配对链接");
        JSONObject qr=new JSONObject().put("origin",uri.getScheme()+"://"+uri.getEncodedAuthority()).put("token",fragment.substring(5)).put("mode","permanent");
        validate(qr);return qr;
    }
    private static void validate(JSONObject qr)throws Exception {
        if(!TrustedOrigin.scanOrigin(qr.getString("origin"))||!qr.getString("token").matches("[A-Za-z0-9_-]{32,128}"))throw new IllegalArgumentException("请扫描电脑首页的临时连接、永久连接或SSH连接二维码");
    }
}
