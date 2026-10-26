package dev.luma.tv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Lights: the household's bulbs over the local network, with no app, account or cloud. WiZ bulbs speak Wiz,
 *  CozyLife bulbs speak Cozy. One tile per room (OK switches every bulb in it, holding OK steps their brightness),
 *  one for every bulb at once, and one that opens the colour panel: whites, colours, brightness and profiles.
 *  Bulbs are found by broadcast each time the page shows, so a new IP address from the router is picked up; the
 *  last known address is kept for the next start. */
final class LightsWidget extends Widget {
    static final float PAD=18,GAP=12;
    // ponytail: this household's bulbs by MAC (WiZ names from smart-home-local); a picker if bulbs ever change.
    static final String[] ROOMS={"Cuarto","Sala"};
    static final Bulb[] BULBS={new Bulb(true,"6c2990d14743",0),new Bulb(true,"6c2990d12892",1),
        new Bulb(false,"0050c2184b74",1),new Bulb(false,"0050c2d7aa8a",1)};
    static final int[] STEPS={100,50,20};
    static final String[] TARGETS={"Cuarto","Sala","Todas"};
    static final String[] WHITES={"Cálido","Neutro","Frío","Luz de día"};
    static final int[] KELVIN={2700,4000,5500,6500};
    static final String[] COLORS={"Rojo","Naranja","Amarillo","Verde","Cian","Azul","Morado","Rosa"};
    static final int[] HUES={0,30,55,120,180,225,275,320};
    static final int[] LEVELS={10,25,50,75,100};
    /** Profiles: name, then dimming, kelvin and hue (-1 unused; hue -2 gives every bulb its own colour). */
    static final String[] PROFILES={"Película","Lectura","Cena","Relajado","Fiesta","Apagar"};
    static final int[][] PROFILE={{20,2200,-1},{100,5000,-1},{50,2700,-1},{40,-1,30},{100,-1,-2},{-1,-1,-1}};

    /** One bulb: its protocol, MAC, room, last known address and state (null when it did not answer). */
    static final class Bulb {
        final boolean wiz;final String mac;final int room;String ip;Wiz.State state;
        Bulb(boolean wiz,String mac,int room){this.wiz=wiz;this.mac=mac;this.room=room;}
    }

    final float tileW=(w-PAD*2-GAP)/2,tileH=(WidgetBoard.ROW_H-PAD*2-GAP)/2;
    final Tile[] rooms=new Tile[ROOMS.length];
    final Tile all,colors;
    boolean searching;
    int target=2;TextView panelStatus;final List<Runnable> panelPaint=new ArrayList<>();
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
        colors=tile("sun","Colores",3,1,1,this::openPanel);
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
        colors.set("Blancos y colores","OK abre los colores: blancos, colores, brillo y perfiles",false);
        // The hero line is set on focus; a scan or a change that lands later updates it too.
        for(Tile t:new Tile[]{rooms[0],rooms[1],all,colors})if(t.hasFocus())host.hero("tile:"+t.key+":"+t.detail,t.name.getText().toString(),t.detail); // the hero skips a repeated key
        for(Runnable r:panelPaint)r.run();
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
    /** hue >= 0 sets a colour (with dimming, or full brightness when it is -1); otherwise on/off, dimming and kelvin. */
    private static void write(boolean wiz,String ip,boolean on,int dimming,int kelvin,int hue){
        if(hue>=0){int d=dimming>=0?dimming:100;if(wiz)udp(ip,Wiz.setColor(d,hue));else tcp(ip,Cozy.color(sn(),d,hue));return;}
        if(wiz)udp(ip,Wiz.setPilot(on,dimming,kelvin));else tcp(ip,Cozy.set(sn(),on,dimming,kelvin));
    }
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

    /** Sends the change to the bulbs, shows it at once, then reads the bulbs back. hue -2 spreads the colour wheel
     *  over the bulbs. */
    private void send(List<Bulb> bulbs,boolean on,int dimming,int kelvin,int hue){
        if(bulbs.isEmpty()){scan();return;}
        final Bulb[] targets=bulbs.toArray(new Bulb[0]);final String[] ips=new String[targets.length];final int[] hues=new int[targets.length];
        for(int i=0;i<targets.length;i++){
            ips[i]=targets[i].ip;hues[i]=hue==-2?i*360/targets.length:hue;
            Wiz.State s=targets[i].state;s.on=on;if(on&&dimming>=0)s.dimming=dimming;
            if(on&&kelvin>0){s.temp=kelvin;s.r=s.g=s.b=-1;}
            if(on&&hues[i]>=0){int[] c=Wiz.rgb(hues[i]);s.r=c[0];s.g=c[1];s.b=c[2];if(dimming<0&&s.dimming<0)s.dimming=100;}
        }
        paint();
        WidgetBoard.IO.execute(()->{
            Wiz.State[] read=new Wiz.State[targets.length];
            for(int i=0;i<targets.length;i++){write(targets[i].wiz,ips[i],on,dimming,kelvin,hues[i]);read[i]=read(targets[i].wiz,ips[i]);}
            post(()->{for(int i=0;i<targets.length;i++)if(read[i]!=null)targets[i].state=read[i];paint();});
        });
    }
    private void toggle(List<Bulb> bulbs){boolean anyOn=false;for(Bulb b:bulbs)anyOn|=b.state.on;send(bulbs,!anyOn,-1,-1,-1);}
    private void stepBrightness(List<Bulb> bulbs){
        Wiz.State s=null;for(Bulb b:bulbs)if(b.state.on){s=b.state;break;}
        int next=STEPS[0];if(s!=null)for(int k=0;k<STEPS.length;k++)if(s.dimming>=STEPS[k]){next=STEPS[(k+1)%STEPS.length];break;}
        send(bulbs,true,next,-1,-1);
    }

    // ---- Colour panel ----

    private List<Bulb> targetBulbs(){return inRoom(target<ROOMS.length?target:-1);}
    /** Applies a change to the chosen room, or says why it cannot. */
    private void apply(boolean on,int dimming,int kelvin,int hue,String what){
        List<Bulb> bulbs=targetBulbs();
        if(bulbs.isEmpty()){panelStatus.setText(TARGETS[target]+": ningún foco responde · revisa el apagador");scan();return;}
        send(bulbs,on,dimming,kelvin,hue);panelStatus.setText(TARGETS[target]+" · "+what);
    }
    private void openPanel(){
        FrameLayout o=host.overlay();panelPaint.clear();
        FrameLayout panel=new FrameLayout(getContext());panel.setBackground(round(0xF51C1C1E,24));panel.setElevation(px(24));panel.setClipChildren(false);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(px(640),px(440));lp.gravity=Gravity.CENTER;o.addView(panel,lp);
        panel.addView(label("Luces",20,Color.WHITE,"sans-serif-medium"),pos(300,26,28,24));
        panelStatus=label("OK aplica al instante · Atrás para salir",12,0x99FFFFFF,"sans-serif");panelStatus.setGravity(Gravity.RIGHT);panel.addView(panelStatus,pos(330,16,282,30));
        View first=null;
        float y=68;
        panel.addView(label("Para",11.5f,0x80FFFFFF,"sans-serif-medium"),pos(200,14,28,y));
        for(int i=0;i<TARGETS.length;i++){final int t=i;
            TextView pill=pill(TARGETS[i],()->{target=t;panelStatus.setText("Cambios para "+TARGETS[t].toLowerCase(Locale.forLanguageTag("es-MX")));for(Runnable r:panelPaint)r.run();},()->target==t);
            panel.addView(pill,pos(110,36,28+i*118,y+18));if(i==target)first=pill;}
        y+=68;
        panel.addView(label("Blancos",11.5f,0x80FFFFFF,"sans-serif-medium"),pos(200,14,28,y));
        for(int i=0;i<WHITES.length;i++){final int k=i;Wiz.State w=new Wiz.State();w.temp=KELVIN[i];
            panel.addView(pill(WHITES[i],w.color(),()->apply(true,-1,KELVIN[k],-1,WHITES[k].toLowerCase(Locale.forLanguageTag("es-MX")))),pos(136,36,28+i*146,y+18));}
        y+=68;
        panel.addView(label("Colores",11.5f,0x80FFFFFF,"sans-serif-medium"),pos(200,14,28,y));
        for(int i=0;i<COLORS.length;i++){final int k=i;int[] c=Wiz.rgb(HUES[i]);
            View dot=swatch(0xFF000000|c[0]<<16|c[1]<<8|c[2],COLORS[i],()->apply(true,-1,-1,HUES[k],COLORS[k].toLowerCase(Locale.forLanguageTag("es-MX"))));
            panel.addView(dot,pos(44,44,28+i*72,y+16));}
        y+=72;
        panel.addView(label("Brillo",11.5f,0x80FFFFFF,"sans-serif-medium"),pos(200,14,28,y));
        for(int i=0;i<LEVELS.length;i++){final int k=i;
            panel.addView(pill(LEVELS[i]+"%",()->apply(true,LEVELS[k],-1,-1,"brillo "+LEVELS[k]+"%"),null),pos(106,36,28+i*116,y+18));}
        y+=68;
        panel.addView(label("Perfiles",11.5f,0x80FFFFFF,"sans-serif-medium"),pos(200,14,28,y));
        for(int i=0;i<PROFILES.length;i++){final int k=i;final int[] p=PROFILE[i];
            panel.addView(pill(PROFILES[i],()->apply(p[0]!=-1||p[1]!=-1||p[2]!=-1,p[0],p[1],p[2],PROFILES[k].toLowerCase(Locale.forLanguageTag("es-MX"))),null),pos(90,36,28+i*98,y+18));}
        if(motion()){panel.setScaleX(.96f);panel.setScaleY(.96f);panel.animate().scaleX(1).scaleY(1).setDuration(360).setInterpolator(spring).start();}
        final View focus=first;post(()->{if(focus!=null)focus.requestFocus();});
    }
    private TextView label(String value,float size,int color,String family){return text(value,size,color,family);}
    /** A text pill; selected (may be null) keeps it marked while another control has focus. */
    private TextView pill(String value,Runnable action,java.util.function.BooleanSupplier selected){return pill(value,0,action,selected);}
    private TextView pill(String value,int dot,Runnable action){return pill(value,dot,action,null);}
    private TextView pill(String value,int dot,Runnable action,java.util.function.BooleanSupplier selected){
        TextView p=text(value,13.5f,Color.WHITE,"sans-serif-medium");p.setGravity(Gravity.CENTER);p.setPivotX(px(55));p.setPivotY(px(18));
        if(dot!=0){GradientDrawable d=oval(dot);int s=px(12);d.setBounds(0,0,s,s);p.setCompoundDrawables(d,null,null,null);p.setCompoundDrawablePadding(px(8));p.setPadding(px(14),0,px(10),0);}
        Runnable paint=()->{boolean f=p.hasFocus(),on=selected!=null&&selected.getAsBoolean();
            p.setBackground(round(f?0xF7FFFFFF:on?0x40FFFFFF:0x1AFFFFFF,18));p.setTextColor(f?0xFF111827:Color.WHITE);};
        panelPaint.add(paint);
        control(p,0,1.06f,f->paint.run(),()->new String[]{"lights-panel",value,""},action);
        p.setOnFocusChangeListener((v,f)->{paint.run();v.animate().cancel();float s=f?1.06f:1;if(motion())v.animate().scaleX(s).scaleY(s).setDuration(f?460:300).setInterpolator(f?spring:ease).start();else{v.setScaleX(s);v.setScaleY(s);}});
        return p;
    }
    /** A round colour swatch; its name shows in the status line while it has focus. */
    private View swatch(int color,String name,Runnable action){
        View v=new View(getContext());v.setPivotX(px(22));v.setPivotY(px(22));
        Runnable paint=()->{GradientDrawable d=oval(color);d.setStroke(px(v.hasFocus()?3:1),v.hasFocus()?Color.WHITE:0x33FFFFFF);v.setBackground(d);};
        control(v,0,1.18f,f->paint.run(),()->new String[]{"lights-panel",name,""},action);
        v.setOnFocusChangeListener((x,f)->{paint.run();if(f)panelStatus.setText(name);v.animate().cancel();float s=f?1.18f:1;if(motion())v.animate().scaleX(s).scaleY(s).setDuration(f?460:300).setInterpolator(f?spring:ease).start();else{v.setScaleX(s);v.setScaleY(s);}});
        return v;
    }
}
