package dev.luma.tv;

import org.json.JSONObject;

/** Checks the WiZ message codec against replies real bulbs send. Run from the repository root:
 *    javac -cp json.jar -d build/test src/dev/luma/tv/Wiz.java tools/WizTest.java && java -cp build/test:json.jar dev.luma.tv.WizTest
 *  json.jar is org.json (Android's own org.json is stubbed on a desktop JVM). */
public final class WizTest {
    static int failures;
    static void expect(String what,Object actual,Object expected){
        boolean ok=expected==null?actual==null:expected.equals(actual);
        if(!ok)failures++;
        System.out.println((ok?"ok    ":"FAIL  ")+what+" → "+actual+(ok?"":"   expected "+expected));
    }
    public static void main(String[] args){
        JSONObject probe=new JSONObject(Wiz.probe());
        expect("probe method",probe.getString("method"),"registration");
        expect("probe asks not to register",probe.getJSONObject("params").getBoolean("register"),false);
        expect("getPilot",new JSONObject(Wiz.getPilot()).getString("method"),"getPilot");

        expect("registration mac",Wiz.mac("{\"method\":\"registration\",\"env\":\"pro\",\"result\":{\"mac\":\"6c2990d14743\",\"success\":true}}"),"6c2990d14743");
        expect("other method is not a mac",Wiz.mac("{\"method\":\"getPilot\",\"result\":{\"mac\":\"6c2990d14743\"}}"),null);
        expect("empty mac",Wiz.mac("{\"method\":\"registration\",\"result\":{\"mac\":\"\"}}"),null);
        expect("garbage",Wiz.mac("not json"),null);

        Wiz.State rgb=Wiz.pilot("{\"method\":\"getPilot\",\"env\":\"pro\",\"result\":{\"mac\":\"6c2990d14743\",\"rssi\":-60,\"state\":true,\"sceneId\":0,\"r\":255,\"g\":120,\"b\":0,\"c\":0,\"w\":0,\"dimming\":80}}");
        expect("rgb on",rgb.on,true);
        expect("rgb dimming",rgb.dimming,80);
        expect("rgb colour",Integer.toHexString(rgb.color()),"ffff7800");
        expect("rgb status",rgb.status(),"Encendida · 80%");

        Wiz.State white=Wiz.pilot("{\"method\":\"getPilot\",\"result\":{\"state\":true,\"temp\":2700,\"dimming\":100}}");
        expect("warm white is warm",(white.color()&0xFF)<((white.color()>>16)&0xFF),true);
        Wiz.State off=Wiz.pilot("{\"method\":\"getPilot\",\"result\":{\"state\":false,\"dimming\":10}}");
        expect("off",off.on,false);
        expect("off status",off.status(),"Apagada");
        expect("bad dimming",Wiz.pilot("{\"method\":\"getPilot\",\"result\":{\"state\":true,\"dimming\":140}}"),null);
        expect("error reply",Wiz.pilot("{\"method\":\"getPilot\",\"error\":{\"code\":-32601}}"),null);
        expect("number posing as boolean",Wiz.pilot("{\"method\":\"getPilot\",\"result\":{\"state\":1}}"),null);

        JSONObject set=new JSONObject(Wiz.setPilot(true,60,2700));
        expect("setPilot method",set.getString("method"),"setPilot");
        expect("setPilot params",set.getJSONObject("params").toString(),new JSONObject("{\"state\":true,\"dimming\":60,\"temp\":2700}").toString());
        expect("setPilot off has only state",new JSONObject(Wiz.setPilot(false,-1,-1)).getJSONObject("params").toString(),"{\"state\":false}");

        expect("red",java.util.Arrays.toString(Wiz.rgb(0)),"[255, 0, 0]");
        expect("green",java.util.Arrays.toString(Wiz.rgb(120)),"[0, 255, 0]");
        expect("purple",java.util.Arrays.toString(Wiz.rgb(270)),"[128, 0, 255]");
        expect("setColor params",new JSONObject(Wiz.setColor(40,240)).getJSONObject("params").toString(),new JSONObject("{\"state\":true,\"dimming\":40,\"r\":0,\"g\":0,\"b\":255,\"c\":0,\"w\":0}").toString());

        expect("ack",Wiz.ack("{\"method\":\"setPilot\",\"env\":\"pro\",\"result\":{\"success\":true}}"),true);
        expect("ack must be boolean true",Wiz.ack("{\"method\":\"setPilot\",\"result\":{\"success\":1}}"),false);
        expect("ack of another method",Wiz.ack("{\"method\":\"getPilot\",\"result\":{\"success\":true}}"),false);

        System.out.println(failures==0?"all passed":failures+" failed");
        if(failures>0)System.exit(1);
    }
}
