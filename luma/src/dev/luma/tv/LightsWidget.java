package dev.luma.tv;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Lights: the household's bulbs over the local network, with no app, account or cloud. WiZ bulbs speak Wiz,
 *  CozyLife bulbs speak Cozy. One tile per room (OK switches every bulb in it, holding OK steps their brightness),
 *  one for every bulb at once, and one that cycles ambiences. Bulbs are found by broadcast each time the page shows,
 *  so a new IP address from the router is picked up; the last known address is kept for the next start. */
final class LightsWidget extends Widget {
    static final float PAD=18,GAP=12;
    // ponytail: this household's bulbs by MAC (WiZ names from smart-home-local); a picker if bulbs ever change.
    static final String[] ROOMS={"Cuarto","Sala"};
    static final Bulb[] BULBS={new Bulb(true,"6c2990d14743",0),new Bulb(true,"6c2990d12892",1),
        new Bulb(false,"0050c2184b74",1),new Bulb(false,"0050c2d7aa8a",1)};
    static final String[] AMBIENCES={"Cálida","Blanca","Película"};
    static final int[][] AMBIENCE={{100,2700},{100,4500},{20,2200}}; // dimming, kelvin
    static final int[] STEPS={100,50,20};

    /** One bulb: its protocol, MAC, room, last known address and state (null when it did not answer). */
    static final class Bulb {
        final boolean wiz;final String mac;final int room;String ip;Wiz.State state;
        Bulb(boolean wiz,String mac,int room){this.wiz=wiz;this.mac=mac;this.room=room;}
    }

    final float tileW=(w-PAD*2-GAP)/2,tileH=(WidgetBoard.ROW_H-PAD*2-GAP)/2;
    final Tile[] rooms=new Tile[ROOMS.length];
    final Tile all,ambience;
    int nextAmbience;boolean searching;
    final Runnable tick=new Runnable(){public void run(){if(!active)return;scan();postDelayed(this,20000);}};

    LightsWidget(Context context,WidgetBoard.Host host){
        super(context,host,WidgetBoard.width("lights"));
        for(Bulb b:BULBS)b.ip=host.prefs().getString("light_ip_"+b.mac,null);
        for(int i=0;i<ROOMS.length;i++){
            final int room=i;
            rooms[i]=tile("bulb",ROOMS[i],i,i%2,0,()->toggle(inRoom(room)));
            rooms[i].setOnLongClickListener(v->{stepBrightness(inRoom(room));return true;});
        }
        all=tile("bulb","Todas",2,0,1,()->toggle(inRoom(-1)));
        ambience=tile("sun","Ambiente",3,1,1,this::cycleAmbience);
        paint();
    }
    private Tile tile(String kind,String title,int index,int col,int row,Runnable action){Tile t=new Tile(kind,title,tileW,tileH,index,action);addView(t,pos(tileW,tileH,PAD+col*(tileW+GAP),PAD+row*(tileH+GAP)));return t;}
    @Override void setActive(boolean value){boolean was=active;super.setActive(value);removeCallbacks(tick);if(value&&!was)post(tick);}
    @Override void refresh(){if(active)scan();}

    /** Bulbs of a room that answered, or of every room for -1. */
    private List<Bulb> inRoom(int room){List<Bulb> list=new ArrayList<>();for(Bulb b:BULBS)if((room<0||b.room==room)&&b.state!=null)list.add(b);return list;}

    private void paint(){
        for(int i=0;i<ROOMS.length;i++)paintGroup(rooms[i],inRoom(i),true);
        paintGroup(all,inRoom(-1),false);
        ambience.set(AMBIENCES[nextAmbience],"OK pone todas las luces en "+AMBIENCES[nextAmbience].toLowerCase(Locale.forLanguageTag("es-MX"))+".",false);
        // The hero line is set on focus; a scan or a change that lands later updates it too.
        for(Tile t:new Tile[]{rooms[0],rooms[1],all,ambience})if(t.hasFocus())host.hero("tile:"+t.key+":"+t.detail,t.name.getText().toString(),t.detail); // the hero skips a repeated key
    }
    private void paintGroup(Tile t,List<Bulb> found,boolean room){
        if(found.isEmpty()){t.set(searching?"Buscando…":"Sin respuesta","No responde · revisa el apagador · OK busca de nuevo",false);return;}
        Bulb lit=null;for(Bulb b:found)if(b.state.on){lit=b;break;}
        if(lit==null){t.set("Apagada"+(room?"":"s"),"OK "+(room?"la enciende · Mantén OK: brillo":"enciende todas las luces"),false);return;}
        String status=room?lit.state.status():"Encendidas";
        t.tint=lit.state.color();t.set(status,"OK "+(room?"la apaga · Mantén OK: brillo":"apaga todas las luces"),true);t.paint();
    }

    /** Broadcasts both probes, keeps each known bulb's address, then reads every bulb's state. */
    void scan(){
        if(searching)return;searching=true;paint();
        WidgetBoard.IO.execute(()->{
            Map<String,String> found=new HashMap<>();String sn=sn();
            try(DatagramSocket socket=new DatagramSocket()){
                socket.setBroadcast(true);socket.setSoTimeout(300);
                byte[] wiz=Wiz.probe().getBytes(StandardCharsets.UTF_8),cozy=Cozy.probe(sn).getBytes(StandardCharsets.UTF_8);
                InetAddress everyone=InetAddress.getByName("255.255.255.255");
                long until=System.currentTimeMillis()+1600;int sent=0;
                while(System.currentTimeMillis()<until){
                    if(sent<3){socket.send(new DatagramPacket(wiz,wiz.length,everyone,Wiz.PORT));socket.send(new DatagramPacket(cozy,cozy.length,everyone,Cozy.DISCOVERY_PORT));sent++;}
                    DatagramPacket reply=new DatagramPacket(new byte[1024],1024);
                    try{socket.receive(reply);}catch(SocketTimeoutException e){continue;}
                    String text=new String(reply.getData(),0,reply.getLength(),StandardCharsets.UTF_8),mac=Wiz.mac(text);
                    if(mac==null)mac=Cozy.mac(text,sn);
                    if(mac!=null)found.put(mac.toLowerCase(Locale.US),reply.getAddress().getHostAddress());
                }
            }catch(Exception ignored){}
            String[] ips=new String[BULBS.length];Wiz.State[] read=new Wiz.State[BULBS.length];
            for(int i=0;i<BULBS.length;i++){Bulb b=BULBS[i];ips[i]=found.containsKey(b.mac)?found.get(b.mac):b.ip;if(ips[i]!=null)read[i]=read(b.wiz,ips[i]);}
            post(()->{searching=false;
                for(int i=0;i<BULBS.length;i++){Bulb b=BULBS[i];
                    if(ips[i]!=null&&!ips[i].equals(b.ip)){b.ip=ips[i];host.prefs().edit().putString("light_ip_"+b.mac,ips[i]).apply();}
                    b.state=read[i];}
                paint();});
        });
    }
    private static Wiz.State read(boolean wiz,String ip){return wiz?Wiz.pilot(udp(ip,Wiz.getPilot())):Cozy.state(tcp(ip,Cozy.query(sn())));}
    private static void write(boolean wiz,String ip,boolean on,int dimming,int kelvin){if(wiz)udp(ip,Wiz.setPilot(on,dimming,kelvin));else tcp(ip,Cozy.set(sn(),on,dimming,kelvin));}
    private static String sn(){return String.valueOf(System.currentTimeMillis());}
    /** One WiZ request and its reply, or "" on silence. */
    private static String udp(String ip,String message){
        try(DatagramSocket socket=new DatagramSocket()){
            socket.setSoTimeout(1000);byte[] out=message.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(out,out.length,InetAddress.getByName(ip),Wiz.PORT));
            DatagramPacket reply=new DatagramPacket(new byte[1024],1024);socket.receive(reply);
            return new String(reply.getData(),0,reply.getLength(),StandardCharsets.UTF_8);
        }catch(Exception e){return "";}
    }
    /** One CozyLife request; returns the first state line the bulb answers with (a reply or a push), or "". */
    private static String tcp(String ip,String message){
        try(Socket socket=new Socket()){
            socket.connect(new InetSocketAddress(ip,Cozy.PORT),1000);socket.setSoTimeout(1200);
            socket.getOutputStream().write(message.getBytes(StandardCharsets.UTF_8));
            BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
            for(int i=0;i<4;i++){String line=in.readLine();if(line==null)break;if(Cozy.state(line)!=null)return line;}
        }catch(Exception ignored){}
        return "";
    }

    /** Sends the change to the bulbs, shows it at once, then reads the bulbs back. */
    private void send(List<Bulb> bulbs,boolean on,int dimming,int kelvin){
        if(bulbs.isEmpty()){scan();return;}
        for(Bulb b:bulbs){Wiz.State s=b.state;s.on=on;if(on&&dimming>=0)s.dimming=dimming;if(on&&kelvin>0){s.temp=kelvin;s.r=s.g=s.b=-1;}}
        paint();
        final Bulb[] targets=bulbs.toArray(new Bulb[0]);final String[] ips=new String[targets.length];for(int i=0;i<targets.length;i++)ips[i]=targets[i].ip;
        WidgetBoard.IO.execute(()->{
            Wiz.State[] read=new Wiz.State[targets.length];
            for(int i=0;i<targets.length;i++){write(targets[i].wiz,ips[i],on,dimming,kelvin);read[i]=read(targets[i].wiz,ips[i]);}
            post(()->{for(int i=0;i<targets.length;i++)if(read[i]!=null)targets[i].state=read[i];paint();});
        });
    }
    private void toggle(List<Bulb> bulbs){boolean anyOn=false;for(Bulb b:bulbs)anyOn|=b.state.on;send(bulbs,!anyOn,-1,-1);}
    private void stepBrightness(List<Bulb> bulbs){
        Wiz.State s=null;for(Bulb b:bulbs)if(b.state.on){s=b.state;break;}
        int next=STEPS[0];if(s!=null)for(int k=0;k<STEPS.length;k++)if(s.dimming>=STEPS[k]){next=STEPS[(k+1)%STEPS.length];break;}
        send(bulbs,true,next,-1);
    }
    private void cycleAmbience(){
        List<Bulb> bulbs=inRoom(-1);if(bulbs.isEmpty()){scan();return;}
        int[] a=AMBIENCE[nextAmbience];nextAmbience=(nextAmbience+1)%AMBIENCE.length;
        send(bulbs,true,a[0],a[1]);
    }
}
