package dev.luma.tv;

import org.json.*;

/** Messages of the WiZ local UDP protocol (port 38899), the one pywizlight and the household's own
 *  smart-home-local app speak. Pure: no network, so tools/WizTest.java checks it on a desktop JVM. */
final class Wiz {
    static final int PORT=38899;

    /** What a bulb reports: on/off, brightness 10–100 (or -1), and either an RGB colour or a white temperature. */
    static final class State {
        boolean on;int dimming=-1,r=-1,g=-1,b=-1,temp=-1;
        /** The light's own colour, to tint its tile. */
        int color(){
            // Near-white colours would hide the tile's white glyph: whites show as amber (warm) to sky blue (cold).
            if(r>=0&&g>=0&&b>=0&&(r|g|b)!=0&&Math.min(r,Math.min(g,b))<200)return 0xFF000000|r<<16|g<<8|b;
            if(temp>0){float f=Math.max(0,Math.min(1,(temp-2200)/4300f));return 0xFF000000|Math.round(255-f*155)<<16|Math.round(159+f*51)<<8|Math.round(10+f*245);}
            return 0xFFFFD60A;
        }
        String status(){return on?(dimming>=0?"Encendida · "+dimming+"%":"Encendida"):"Apagada";}
    }

    /** pywizlight's discovery probe; register:false asks the bulb not to push its state to us. */
    static String probe(){return "{\"method\":\"registration\",\"params\":{\"phoneMac\":\"AAAAAAAAAAAA\",\"register\":false,\"phoneIp\":\"1.2.3.4\",\"id\":\"1\"}}";}
    static String getPilot(){return "{\"method\":\"getPilot\",\"params\":{}}";}
    /** dimming and temp below 0 are left out. */
    static String setPilot(boolean on,int dimming,int temp){
        try{
            JSONObject params=new JSONObject().put("state",on);
            if(on&&dimming>=0)params.put("dimming",Math.max(10,Math.min(100,dimming)));
            if(on&&temp>0)params.put("temp",temp);
            return new JSONObject().put("method","setPilot").put("params",params).toString();
        }catch(JSONException e){throw new IllegalStateException(e);}
    }

    /** The bulb's MAC from a registration reply, or null. */
    static String mac(String reply){
        JSONObject result=result(reply,"registration");if(result==null)return null;
        String mac=result.optString("mac","");return mac.isEmpty()?null:mac;
    }
    /** A getPilot reply, or null when it is malformed or out of range. */
    static State pilot(String reply){
        JSONObject result=result(reply,"getPilot");if(result==null)return null;
        State s=new State();
        Object state=result.opt("state");if(!(state instanceof Boolean))return null;s.on=(Boolean)state;
        s.dimming=integer(result,"dimming",0,100);s.r=integer(result,"r",0,255);s.g=integer(result,"g",0,255);s.b=integer(result,"b",0,255);s.temp=integer(result,"temp",1,20000);
        if(s.dimming==-2||s.r==-2||s.g==-2||s.b==-2||s.temp==-2)return null;
        return s;
    }
    /** Only a JSON true counts as success. */
    static boolean ack(String reply){JSONObject result=result(reply,"setPilot");return result!=null&&Boolean.TRUE.equals(result.opt("success"));}

    private static JSONObject result(String reply,String method){
        try{JSONObject o=new JSONObject(reply);return method.equals(o.optString("method"))&&!o.has("error")?o.optJSONObject("result"):null;}
        catch(JSONException e){return null;}
    }
    /** -1 when absent, -2 when not an integer in [min,max]. */
    private static int integer(JSONObject o,String key,int min,int max){
        Object v=o.opt(key);if(v==null)return -1;
        if(!(v instanceof Integer))return -2;int i=(Integer)v;return i>=min&&i<=max?i:-2;
    }
}
