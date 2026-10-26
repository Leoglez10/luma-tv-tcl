package dev.luma.tv;

import android.content.*;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.view.*;
import android.widget.FrameLayout;
import java.util.*;

/** Inputs: the TV's HDMI ports (and its antenna tuner) as tiles. OK switches the TV with Android TV's standard
 *  passthrough link, which the TV's own Live TV app opens; Back or Home returns to Luma. A device announced over
 *  HDMI-CEC is recognised (Devices) and shown under its own name, glyph and colour ("PlayStation 5" in PlayStation
 *  blue while it is on), with its port underneath; a port renamed in the TV's settings keeps its name.
 *  Only TVs list inputs: a TV stick has none (it is plugged into one), so there the widget is not offered. */
final class InputsWidget extends Widget {
    static final float PAD=18,GAP=12;
    static final class Input{String id,name,glyph,vendor,port="";boolean tuner,cec,made;int state,color=0xFF0A84FF;}
    /** QA: am start -n dev.luma.tv/.MainActivity --es luma.inputs demo|off shows made-up consoles on the first ports
     *  of a TV with inputs, to see the recognised look without the devices (not saved; switching still works). */
    static volatile boolean demo;
    /** How many inputs the last look found; WidgetBoard sizes the widget by it (two or three columns). */
    static volatile int count;

    /** The inputs to show: hardware passthrough inputs (a CEC device replaces its port), then the tuner of the same
     *  vendor. Streaming apps' own inputs (IPTV and the like) are left out. */
    static ArrayList<Input> list(Context context){
        ArrayList<Input> out=new ArrayList<>();
        if(Promo.on){promo(out);count=out.size();return out;}
        TvInputManager manager=(TvInputManager)context.getSystemService(Context.TV_INPUT_SERVICE);
        List<TvInputInfo> all;
        try{all=manager==null?Collections.emptyList():manager.getTvInputList();}catch(RuntimeException e){all=Collections.emptyList();}
        HashMap<String,TvInputInfo> child=new HashMap<>();HashSet<String> vendors=new HashSet<>();
        for(TvInputInfo info:all)if(info.getParentId()!=null)child.put(info.getParentId(),info);
        ArrayList<Input> tuners=new ArrayList<>();
        for(TvInputInfo info:all){
            if(info.getParentId()!=null||safeHidden(info,context))continue;
            boolean passthrough=info.isPassthroughInput(),tuner=info.getType()==TvInputInfo.TYPE_TUNER&&!passthrough;
            if(!passthrough&&!tuner)continue;
            TvInputInfo shown=passthrough&&child.containsKey(info.getId())?child.get(info.getId()):info;
            Input in=new Input();in.id=shown.getId();in.tuner=tuner;in.cec=shown!=info;in.vendor=info.getServiceInfo().packageName;
            in.name=label(shown,context);if(tuner&&(in.name.isEmpty()||in.name.matches("(?i)tv|tuner|antenna|atv|dtv")))in.name="TV en vivo";
            try{in.state=manager.getInputState(shown.getId());}catch(RuntimeException e){in.state=TvInputManager.INPUT_STATE_CONNECTED;}
            if(tuner)in.glyph="antenna";
            else if(in.cec){int[] cec=cec(shown);Devices.Device d=Devices.identify(in.name,cec[0],cec[1]);in.port=label(info,context);in.name=d.name;in.glyph=d.glyph;in.color=d.color;}
            else{Devices.Device d=Devices.identify(in.name,-1,-1);in.glyph=d.glyph;in.color=d.color;} // a port renamed "PlayStation" keeps its name
            if(tuner)tuners.add(in);else{out.add(in);vendors.add(in.vendor);}
        }
        Collections.sort(out,(a,b)->natural(a.cec?a.port:a.name,b.cec?b.port:b.name));
        if(demo)pretend(out);
        for(Input t:tuners)if(vendors.contains(t.vendor))out.add(t);
        while(out.size()>6)out.remove(out.size()-1);
        count=out.size();return out;
    }
    /** {vendor ID, CEC device type} of a CEC device input, or -1s. HdmiDeviceInfo is a system API, read by reflection;
     *  the device's name is public (the input's label), so recognition still works when this is refused. */
    private static int[] cec(TvInputInfo info){
        try{Object d=TvInputInfo.class.getMethod("getHdmiDeviceInfo").invoke(info);if(d!=null)return new int[]{(int)d.getClass().getMethod("getVendorId").invoke(d),(int)d.getClass().getMethod("getDeviceType").invoke(d)};}
        catch(ReflectiveOperationException|RuntimeException ignored){}
        return new int[]{-1,-1};
    }
    /** The promo demo's ports: a PlayStation that is on, an Xbox and an Apple TV asleep, and the tuner. */
    private static void promo(ArrayList<Input> out){
        String[][] made={{"PS5","1","0"},{"Xbox Series X","2","1"},{"Apple TV","3","1"}};
        for(String[] m:made){Devices.Device d=Devices.identify(m[0],-1,Devices.PLAYBACK);Input in=new Input();in.id="promo:"+m[1];in.port="HDMI "+m[1];in.cec=true;in.made=true;
            in.name=d.name;in.glyph=d.glyph;in.color=d.color;in.state=Integer.parseInt(m[2]);out.add(in);}
        Input tv=new Input();tv.id="promo:tv";tv.tuner=true;tv.made=true;tv.name="TV en vivo";tv.glyph="antenna";out.add(tv);
    }
    private static void pretend(ArrayList<Input> out){
        String[][] made={{"PS5","0"},{"Xbox Series X","1"},{"NintendoSwitch","0"}};int i=0;
        for(Input in:out){if(in.tuner||i>=made.length)continue;Devices.Device d=Devices.identify(made[i][0],-1,Devices.PLAYBACK);
            in.port=in.cec?in.port:in.name;in.cec=true;in.made=true;in.name=d.name;in.glyph=d.glyph;in.color=d.color;in.state=Integer.parseInt(made[i][1]);i++;}
    }
    private static boolean safeHidden(TvInputInfo info,Context c){try{return info.isHidden(c);}catch(RuntimeException e){return false;}}
    private static String label(TvInputInfo info,Context c){
        CharSequence custom=null;try{custom=info.loadCustomLabel(c);}catch(RuntimeException ignored){}
        if(custom!=null&&custom.toString().trim().length()>0)return custom.toString().trim();
        CharSequence l=info.loadLabel(c);return l==null?"":l.toString().trim();
    }
    /** "HDMI 2" before "HDMI 10". */
    private static int natural(String a,String b){
        String x=a.replaceAll("\\d+",""),y=b.replaceAll("\\d+","");int c=x.compareToIgnoreCase(y);if(c!=0)return c;
        String p=a.replaceAll("\\D+",""),q=b.replaceAll("\\D+","");
        try{return Long.compare(p.isEmpty()?0:Long.parseLong(p),q.isEmpty()?0:Long.parseLong(q));}catch(NumberFormatException e){return a.compareToIgnoreCase(b);}
    }
    /** Always two columns, so Inputs fits next to Control; more than four inputs put the rest behind a + tile. */
    static float widthFor(int n){return 424;}

    final ArrayList<Input> inputs;
    final ArrayList<Tile> tiles=new ArrayList<>();
    final float tileW=(WidgetBoard.width("inputs")-PAD*2-GAP)/2,tileH=(WidgetBoard.ROW_H-PAD*2-GAP)/2;
    final Runnable tick=new Runnable(){public void run(){if(!active)return;refresh();postDelayed(this,10000);}};

    InputsWidget(Context context,WidgetBoard.Host host){
        super(context,host,WidgetBoard.width("inputs"));
        inputs=list(context);
        int columns=2,rows=inputs.size()>columns?2:1,shown=inputs.size()>4?3:inputs.size();
        float top=rows==1?(WidgetBoard.ROW_H-tileH)/2:PAD;
        for(int i=0;i<shown;i++){
            final Input in=inputs.get(i);
            Tile t=new Tile(in.glyph,in.name,tileW,tileH,i,()->open(in));t.key="input:"+in.id;t.tint=in.color;
            addView(t,pos(tileW,tileH,PAD+(i%columns)*(tileW+GAP),top+(i/columns)*(tileH+GAP)));tiles.add(t);
        }
        if(shown<inputs.size()){
            Tile more=new Tile("hdmi","Más",tileW,tileH,3,this::openAll);more.key="input:more";
            more.set((inputs.size()-shown)+" entradas más","Todas tus entradas · OK las muestra",false);
            addView(more,pos(tileW,tileH,PAD+tileW+GAP,top+tileH+GAP));
        }
        paintStates();
    }
    @Override void setActive(boolean value){boolean was=active;super.setActive(value);removeCallbacks(tick);if(value&&!was)post(tick);}
    /** Connection states change as devices are plugged in or wake; names and the list itself rebuild with Home. */
    @Override void refresh(){
        TvInputManager manager=(TvInputManager)getContext().getSystemService(Context.TV_INPUT_SERVICE);if(manager==null)return;
        for(Input in:inputs)if(!in.made)try{in.state=manager.getInputState(in.id);}catch(RuntimeException ignored){}
        paintStates();
    }
    private void paintStates(){
        for(int i=0;i<tiles.size();i++)paint(tiles.get(i),inputs.get(i));
    }
    private void paint(Tile t,Input in){
        String state=in.tuner?"Canales de antena y cable":in.state==TvInputManager.INPUT_STATE_CONNECTED?(in.cec?"Encendido":"Conectado"):in.state==TvInputManager.INPUT_STATE_CONNECTED_STANDBY?"En espera":"Nada conectado";
        if(in.cec)state=in.port+" · "+state;
        String hero=state+" · OK cambia a "+(in.cec?"este dispositivo":in.name);
        t.set(state,hero,!in.tuner&&in.state==TvInputManager.INPUT_STATE_CONNECTED);
    }
    /** Every input, two to a row, over a dimmed Home; Back closes it. */
    private void openAll(){
        FrameLayout o=host.overlay();int rows=(inputs.size()+1)/2;float pw=PAD*2+tileW*2+GAP,ph=64+rows*(tileH+GAP)+PAD;
        FrameLayout panel=new FrameLayout(getContext());panel.setBackground(round(light()?0xF5F2F2F7:0xF51C1C1E,24));panel.setElevation(px(24));panel.setClipChildren(false);
        LayoutParams lp=new LayoutParams(px(pw),px(ph));lp.gravity=Gravity.CENTER;o.addView(panel,lp);
        panel.addView(text("Entradas",20,ink(0xFF),"sans-serif-medium"),pos(pw-48,26,24,22));
        View first=null;
        for(int i=0;i<inputs.size();i++){final Input in=inputs.get(i);
            Tile t=new Tile(in.glyph,in.name,tileW,tileH,i,()->{host.closeOverlay();open(in);});t.key="input:"+in.id;t.tint=in.color;paint(t,in);
            panel.addView(t,pos(tileW,tileH,PAD+(i%2)*(tileW+GAP),64+(i/2)*(tileH+GAP)));if(first==null)first=t;}
        final View focus=first;post(()->{if(focus!=null)focus.requestFocus();});
    }
    private void open(Input in){
        if(in.id.startsWith("promo:"))return;
        Intent view=new Intent(Intent.ACTION_VIEW,TvContract.buildChannelUriForPassthroughInput(in.id));
        try{host.activity().startActivity(view);host.activity().overridePendingTransition(0,0);}
        catch(ActivityNotFoundException e){host.start(new Intent(android.provider.Settings.ACTION_SETTINGS));}
    }
}
