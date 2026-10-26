package dev.luma.tv;

import org.json.JSONObject;

/** Checks the CozyLife message codec against replies the household's bulbs (pid qwialM, firmware 1.0.9) sent.
 *  Run from the repository root:
 *    javac -cp json.jar -d build/test src/dev/luma/tv/Wiz.java src/dev/luma/tv/Cozy.java tools/CozyTest.java && java -cp build/test:json.jar dev.luma.tv.CozyTest */
public final class CozyTest {
    static int failures;
    static void expect(String what,Object actual,Object expected){
        boolean ok=expected==null?actual==null:expected.equals(actual);
        if(!ok)failures++;
        System.out.println((ok?"ok    ":"FAIL  ")+what+" → "+actual+(ok?"":"   expected "+expected));
    }
    public static void main(String[] args){
        JSONObject probe=new JSONObject(Cozy.probe("42"));
        expect("probe cmd",probe.getInt("cmd"),0);
        expect("probe sn",probe.getString("sn"),"42");
        expect("query",new JSONObject(Cozy.query("7")).getJSONObject("msg").getJSONArray("attr").toString(),"[0]");
        expect("query ends with CRLF",Cozy.query("7").endsWith("\r\n"),true);

        String info="{\"cmd\":0,\"pv\":0,\"sn\":\"42\",\"msg\":{\"did\":\"763342810050c2184b74\",\"dtp\":\"02\",\"pid\":\"qwialM\",\"mac\":\"0050c2184b74\",\"ip\":\"192.168.x.x\",\"rssi\":-50,\"sv\":\"1.0.9\",\"hv\":\"0.0.1\"},\"res\":0}";
        expect("discovery mac",Cozy.mac(info,"42"),"0050c2184b74");
        expect("discovery for another probe",Cozy.mac(info,"43"),null);
        expect("discovery failed",Cozy.mac(info.replace("\"res\":0","\"res\":1"),"42"),null);

        Wiz.State on=Cozy.state("{\"cmd\":2,\"pv\":0,\"sn\":\"1\",\"msg\":{\"attr\":[1,2,3,4,5,6],\"data\":{\"1\":1,\"2\":0,\"3\":0,\"4\":500,\"5\":65535,\"6\":65535}},\"res\":0}");
        expect("on",on.on,true);
        expect("brightness in percent",on.dimming,50);
        expect("warmest is 2000 K",on.temp,2000);
        expect("on status",on.status(),"Encendida · 50%");
        Wiz.State pushed=Cozy.state("{\"cmd\":10,\"pv\":0,\"sn\":\"1\",\"res\":0,\"msg\":{\"attr\":[1,2,3,4,5,6],\"data\":{\"1\":0,\"2\":0,\"3\":1000,\"4\":1000,\"5\":65535,\"6\":65535}}}");
        expect("pushed state off",pushed.on,false);
        expect("coldest is 6500 K",pushed.temp,6500);
        expect("unknown brightness",Cozy.state("{\"cmd\":2,\"msg\":{\"data\":{\"1\":1,\"4\":65535}},\"res\":0}").dimming,-1);
        expect("set reply is not a state",Cozy.state("{\"cmd\":3,\"pv\":0,\"sn\":\"1\",\"msg\":{\"attr\":[1],\"data\":{\"1\":0}},\"res\":0}"),null);
        expect("error",Cozy.state("{\"cmd\":2,\"msg\":{\"data\":{\"1\":1}},\"res\":1}"),null);
        expect("garbage",Cozy.state("nope"),null);

        JSONObject set=new JSONObject(Cozy.set("9",true,60,2000));
        expect("set cmd",set.getInt("cmd"),3);
        expect("set data",set.getJSONObject("msg").getJSONObject("data").toString(),new JSONObject("{\"1\":255,\"2\":0,\"3\":0,\"4\":600}").toString());
        expect("set attrs",set.getJSONObject("msg").getJSONArray("attr").toString(),"[1,2,3,4]");
        expect("off sends only the switch",new JSONObject(Cozy.set("9",false,-1,-1)).getJSONObject("msg").getJSONObject("data").toString(),"{\"1\":0}");
        expect("on without values",new JSONObject(Cozy.set("9",true,-1,-1)).getJSONObject("msg").getJSONObject("data").toString(),"{\"1\":255}");

        System.out.println(failures==0?"all passed":failures+" failed");
        if(failures>0)System.exit(1);
    }
}
