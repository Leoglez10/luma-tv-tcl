package dev.luma.tv;

import android.content.Context;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Lights: the household's WiZ bulbs over the local network (Wiz), with no app, account or cloud. One tile per bulb
 *  (OK switches it, holding OK steps its brightness), one for every bulb at once, and one that cycles ambiences.
 *  Bulbs are found by a broadcast each time the page shows, so a new IP address from the router is picked up. */
final class LightsWidget extends Widget {
    static final float PAD=18,GAP=12;
    // ponytail: this household's two bulbs by MAC (names from smart-home-local); a picker if bulbs ever change.
    static final String[] MACS={"6c2990d14743","6c2990d12892"},NAMES={"Cuarto","Sala"};
    static final String[] AMBIENCES={"Cálida","Blanca","Película"};
    static final int[][] AMBIENCE={{100,2700},{100,4500},{20,2200}}; // dimming, kelvin
    static final int[] STEPS={100,50,20};
    final float tileW=(w-PAD*2-GAP)/2,tileH=(WidgetBoard.ROW_H-PAD*2-GAP)/2;
    final Tile[] bulbs=new Tile[MACS.length];
    final Tile all,ambience;
    final String[] ips=new String[MACS.length];
    final Wiz.State[] states=new Wiz.State[MACS.length];
    int nextAmbience;boolean searching;
    final Runnable tick=new Runnable(){public void run(){if(!active)return;scan();postDelayed(this,20000);}};

    LightsWidget(Context context,WidgetBoard.Host host){
        super(context,host,WidgetBoard.width("lights"));
        for(int i=0;i<MACS.length;i++){
            final int n=i;ips[i]=host.prefs().getString("wiz_ip_"+MACS[i],null);
            bulbs[i]=tile("bulb",NAMES[i],i,i%2,0,()->toggle(n));
            bulbs[i].setOnLongClickListener(v->{stepBrightness(n);return true;});
        }
        all=tile("bulb","Todas",2,0,1,this::toggleAll);
        ambience=tile("sun","Ambiente",3,1,1,this::cycleAmbience);
        paint();
    }
    private Tile tile(String kind,String title,int index,int col,int row,Runnable action){Tile t=new Tile(kind,title,tileW,tileH,index,action);addView(t,pos(tileW,tileH,PAD+col*(tileW+GAP),PAD+row*(tileH+GAP)));return t;}
    @Override void setActive(boolean value){boolean was=active;super.setActive(value);removeCallbacks(tick);if(value&&!was)post(tick);}
    @Override void refresh(){if(active)scan();}

    private void paint(){
        boolean anyOn=false,anyFound=false;
        for(int i=0;i<MACS.length;i++){
            Wiz.State s=states[i];Tile t=bulbs[i];
            if(s==null){t.set(searching?"Buscando…":"Sin respuesta","No responde · revisa el apagador · OK busca de nuevo",false);continue;}
            anyFound=true;anyOn|=s.on;t.tint=s.color();
            t.set(s.status(),s.status()+". OK "+(s.on?"la apaga":"la enciende")+" · Mantén OK cambia el brillo.",s.on);t.paint();
        }
        all.tint=0xFFFFD60A;
        all.set(!anyFound?"Sin focos":anyOn?"Encendidas":"Apagadas",!anyFound?"No se encontró ningún foco en la red.":"OK "+(anyOn?"apaga":"enciende")+" todas las luces.",anyOn);
        ambience.set(AMBIENCES[nextAmbience],"OK pone todas las luces en "+AMBIENCES[nextAmbience].toLowerCase(Locale.forLanguageTag("es-MX"))+".",false);
    }

    /** Broadcasts the probe, keeps the address of each known bulb, then reads every bulb's state. */
    void scan(){
        if(searching)return;searching=true;paint();
        WidgetBoard.IO.execute(()->{
            String[] found=ips.clone();
            try(DatagramSocket socket=new DatagramSocket()){
                socket.setBroadcast(true);socket.setSoTimeout(400);byte[] probe=Wiz.probe().getBytes(StandardCharsets.UTF_8);
                long until=System.currentTimeMillis()+1600;int sent=0;
                while(System.currentTimeMillis()<until){
                    if(sent<3){socket.send(new DatagramPacket(probe,probe.length,InetAddress.getByName("255.255.255.255"),Wiz.PORT));sent++;}
                    DatagramPacket reply=new DatagramPacket(new byte[1024],1024);
                    try{socket.receive(reply);}catch(SocketTimeoutException e){continue;}
                    String mac=Wiz.mac(new String(reply.getData(),0,reply.getLength(),StandardCharsets.UTF_8));
                    for(int i=0;i<MACS.length;i++)if(MACS[i].equalsIgnoreCase(mac))found[i]=reply.getAddress().getHostAddress();
                }
            }catch(Exception ignored){}
            Wiz.State[] read=new Wiz.State[MACS.length];
            for(int i=0;i<MACS.length;i++)if(found[i]!=null)read[i]=Wiz.pilot(request(found[i],Wiz.getPilot()));
            post(()->{searching=false;
                for(int i=0;i<MACS.length;i++){
                    if(found[i]!=null&&!found[i].equals(ips[i])){ips[i]=found[i];host.prefs().edit().putString("wiz_ip_"+MACS[i],found[i]).apply();}
                    states[i]=read[i];
                }
                paint();});
        });
    }
    /** One request and its reply, or "" on silence. */
    private static String request(String ip,String message){
        try(DatagramSocket socket=new DatagramSocket()){
            socket.setSoTimeout(1000);byte[] out=message.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(out,out.length,InetAddress.getByName(ip),Wiz.PORT));
            DatagramPacket reply=new DatagramPacket(new byte[1024],1024);socket.receive(reply);
            return new String(reply.getData(),0,reply.getLength(),StandardCharsets.UTF_8);
        }catch(Exception e){return "";}
    }
    /** Sends setPilot to the bulbs, shows the change at once, then reads the bulbs back. */
    private void send(int[] which,boolean on,int dimming,int temp){
        final String[] targets=new String[which.length];
        for(int k=0;k<which.length;k++){int i=which[k];targets[k]=ips[i];
            Wiz.State s=states[i];if(s==null)continue;s.on=on;if(on&&dimming>=0)s.dimming=dimming;if(on&&temp>0){s.temp=temp;s.r=s.g=s.b=-1;}}
        paint();
        WidgetBoard.IO.execute(()->{
            String message=Wiz.setPilot(on,dimming,temp);
            Wiz.State[] read=new Wiz.State[which.length];
            for(int k=0;k<which.length;k++)if(targets[k]!=null){request(targets[k],message);read[k]=Wiz.pilot(request(targets[k],Wiz.getPilot()));}
            post(()->{for(int k=0;k<which.length;k++)states[which[k]]=read[k];paint();});
        });
    }
    private void toggle(int i){if(states[i]==null){scan();return;}send(new int[]{i},!states[i].on,-1,-1);}
    private void stepBrightness(int i){
        Wiz.State s=states[i];if(s==null){scan();return;}
        int next=STEPS[0];for(int k=0;k<STEPS.length;k++)if(s.on&&s.dimming>=STEPS[k]){next=STEPS[(k+1)%STEPS.length];break;}
        send(new int[]{i},true,next,-1);
    }
    private int[] found(){int n=0;for(Wiz.State s:states)if(s!=null)n++;int[] f=new int[n];n=0;for(int i=0;i<states.length;i++)if(states[i]!=null)f[n++]=i;return f;}
    private void toggleAll(){
        int[] f=found();if(f.length==0){scan();return;}
        boolean anyOn=false;for(int i:f)anyOn|=states[i].on;
        send(f,!anyOn,-1,-1);
    }
    private void cycleAmbience(){
        int[] f=found();if(f.length==0){scan();return;}
        int[] a=AMBIENCE[nextAmbience];nextAmbience=(nextAmbience+1)%AMBIENCE.length;
        send(f,true,a[0],a[1]);
    }
}
