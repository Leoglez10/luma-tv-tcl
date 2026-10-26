package dev.luma.tv;

import org.json.*;

/** Messages of the CozyLife local protocol: discovery on UDP 6095, control on TCP 5555 as "\r\n"-framed JSON
 *  (cozylife/hass_cozylife_local_pull; checked on the household's bulbs, pid qwialM, firmware 1.0.9).
 *  Data points: 1 switch (0 or 255; read back as 0/1), 2 work mode (0 white), 3 white temperature 0 (2000 K) to
 *  1000 (6500 K), 4 brightness 0–1000; 65535 means unknown. Pure, so tools/CozyTest.java checks it on a desktop JVM. */
final class Cozy {
    static final int DISCOVERY_PORT=6095,PORT=5555,UNKNOWN=65535;
    static final int MIN_K=2000,MAX_K=6500;

    static String probe(String sn){return "{\"cmd\":0,\"pv\":0,\"sn\":\""+sn+"\",\"msg\":{}}";}
    static String query(String sn){return "{\"cmd\":2,\"pv\":0,\"sn\":\""+sn+"\",\"msg\":{\"attr\":[0]}}\r\n";}
    /** dimming (percent) and kelvin below 0 are left out; turning off sends the switch alone. */
    static String set(String sn,boolean on,int dimming,int kelvin){
        try{
            JSONObject data=new JSONObject().put("1",on?255:0);JSONArray attr=new JSONArray().put(1);
            if(on&&kelvin>0){int t=Math.round((Math.max(MIN_K,Math.min(MAX_K,kelvin))-MIN_K)*1000f/(MAX_K-MIN_K));data.put("2",0).put("3",t);attr.put(2).put(3);}
            if(on&&dimming>=0){data.put("4",Math.max(10,Math.min(100,dimming))*10);attr.put(4);}
            return new JSONObject().put("cmd",3).put("pv",0).put("sn",sn).put("msg",new JSONObject().put("attr",attr).put("data",data)).toString()+"\r\n";
        }catch(JSONException e){throw new IllegalStateException(e);}
    }

    /** The bulb's MAC from the discovery reply to probe(sn), or null. */
    static String mac(String reply,String sn){
        try{
            JSONObject o=new JSONObject(reply);if(o.optInt("cmd",-1)!=0||o.optInt("res",-1)!=0||!sn.equals(o.optString("sn")))return null;
            JSONObject msg=o.optJSONObject("msg");String mac=msg==null?"":msg.optString("mac","");return mac.isEmpty()?null:mac;
        }catch(JSONException e){return null;}
    }
    /** A state line (the reply to query, or a state the bulb pushes), or null for anything else. */
    static Wiz.State state(String line){
        try{
            JSONObject o=new JSONObject(line);int cmd=o.optInt("cmd",-1);
            if(cmd!=2&&cmd!=10||o.optInt("res",-1)!=0)return null;
            JSONObject msg=o.optJSONObject("msg"),data=msg==null?null:msg.optJSONObject("data");
            if(data==null||!data.has("1"))return null;
            Wiz.State s=new Wiz.State();s.on=data.optInt("1")!=0;
            int b=data.optInt("4",UNKNOWN),t=data.optInt("3",UNKNOWN);
            if(b>=0&&b<=1000)s.dimming=Math.max(1,Math.round(b/10f));
            if(t>=0&&t<=1000)s.temp=MIN_K+Math.round(t*(MAX_K-MIN_K)/1000f);
            return s;
        }catch(JSONException e){return null;}
    }
}
