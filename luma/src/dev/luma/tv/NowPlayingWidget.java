package dev.luma.tv;

import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.*;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

/** Now Playing: the media session of the app playing on this TV (Spotify, YouTube Music, SoundCloud…) with its
 *  artwork, progress and previous / play-pause / next. Android hands media sessions only to a notification
 *  listener, so Luma declares one (MediaListener) that reads no notifications; the user allows it once. */
final class NowPlayingWidget extends Widget {
    static final String[] MUSIC={"com.spotify.tv.android","com.google.android.youtube.tvmusic","com.soundcloud.android","com.apple.android.music","deezer.android.tv","com.amazon.music.tv","com.aspiro.tidal","ru.yandex.music","com.google.android.youtube.tv"};
    static final float PAD=18,ART=104,TX=136,CTRL_Y=130;
    final Handler handler=new Handler(Looper.getMainLooper());
    final MediaSessionManager sessions;
    final ComponentName listener;
    final FrameLayout art;
    final ImageView artImage;
    final Glyph artGlyph,playGlyph;
    final TextView source,title,artist,elapsed,remaining;
    final View track,fill;
    final FrameLayout prev,play,next,expand;
    /** The full-screen player while it is open (openFull), else null. */
    Full full;
    final TextView openPill;
    final LinearLayout shortcuts;
    MediaController controller;
    boolean access=true;
    String artKey="";
    final MediaSessionManager.OnActiveSessionsChangedListener changed=list->pick(list);
    final MediaController.Callback callback=new MediaController.Callback(){
        @Override public void onPlaybackStateChanged(PlaybackState state){render();}
        @Override public void onMetadataChanged(MediaMetadata metadata){render();}
        @Override public void onSessionDestroyed(){if(controller!=null)controller.unregisterCallback(this);controller=null;repick();}
    };
    final Runnable tick=new Runnable(){public void run(){progress();if(active&&playing())handler.postDelayed(this,1000);}};

    NowPlayingWidget(Context context,WidgetBoard.Host host){
        super(context,host,WidgetBoard.width("music"));
        sessions=(MediaSessionManager)context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        listener=new ComponentName(context,MediaListener.class);
        art=new FrameLayout(context);art.setClipToOutline(true);art.setBackground(round(light()?0x14000000:0x1AFFFFFF,14));art.setElevation(px(3));addView(art,pos(ART,ART,PAD,PAD));
        artGlyph=new Glyph(context).kind("note").color(ink(0x80));art.addView(artGlyph,pos(40,40,(ART-40)/2,(ART-40)/2));
        artImage=new ImageView(context);artImage.setScaleType(ImageView.ScaleType.CENTER_CROP);art.addView(artImage,new LayoutParams(-1,-1));
        float tw=w-TX-PAD;
        source=text("",10.5f,ink(0x8C),"sans-serif-medium");source.setLetterSpacing(.08f);addView(source,pos(tw,14,TX,22));
        title=text("",17,ink(0xFF),"sans-serif-medium");addView(title,pos(tw,22,TX,40));
        artist=text("",13,ink(0xB3),"sans-serif");addView(artist,pos(tw,17,TX,66));
        track=new View(context);track.setBackground(round(ink(0x26),1.5f));addView(track,pos(tw,3,TX,96));
        fill=new View(context);fill.setBackground(round(ink(0xD9),1.5f));fill.setPivotX(0);addView(fill,pos(tw,3,TX,96));
        elapsed=text("",10.5f,ink(0x80),"sans-serif");addView(elapsed,pos(60,13,TX,104));
        remaining=text("",10.5f,ink(0x80),"sans-serif");remaining.setGravity(Gravity.RIGHT);addView(remaining,pos(60,13,w-PAD-60,104));
        prev=button("previous",34,TX-4,CTRL_Y+4,0,()->{if(controller!=null)controller.getTransportControls().skipToPrevious();});
        play=button("play",42,TX+38,CTRL_Y,1,()->{if(Promo.on){Promo.toggle();render();return;}if(controller==null)return;if(playing())controller.getTransportControls().pause();else controller.getTransportControls().play();});
        next=button("next",34,TX+88,CTRL_Y+4,2,()->{if(controller!=null)controller.getTransportControls().skipToNext();});
        expand=button("expand",34,TX+130,CTRL_Y+4,3,this::openFull);
        playGlyph=(Glyph)play.getChildAt(1);
        openPill=pill("Abrir",3,()->{if(controller!=null)host.launch(controller.getPackageName());});
        // Right-aligned by layout, not by translation: focus search goes by layout position.
        LayoutParams op=new LayoutParams(-2,px(32));op.gravity=Gravity.TOP|Gravity.RIGHT;op.rightMargin=px(PAD);op.topMargin=px(CTRL_Y+5);addView(openPill,op);
        shortcuts=new LinearLayout(context);shortcuts.setClipChildren(false);shortcuts.setClipToPadding(false);addView(shortcuts,pos(tw+10,34,TX-4,CTRL_Y+4));
        render();
    }
    private FrameLayout button(String kind,float size,float x,float y,int index,Runnable action){
        FrameLayout b=new FrameLayout(getContext());View disc=new View(getContext());b.addView(disc,new LayoutParams(-1,-1));
        Glyph g=new Glyph(getContext()).kind(kind);float gs=size*.46f;b.addView(g,pos(gs,gs,(size-gs)/2,(size-gs)/2));
        b.setPivotX(px(size/2));b.setPivotY(px(size/2));
        control(b,index,1.14f,f->{disc.setBackground(oval(f?focusFill():restFill()));g.color(f?focusInk():ink(0xF2));},()->hero(),action);
        addView(b,pos(size,size,x,y));return b;
    }
    private TextView pill(String label,int index,Runnable action){return pill(label,index,null,action);}
    /** A rounded text button; hero overrides the Home title while it is focused (app shortcuts). */
    private TextView pill(String label,int index,String[] hero,Runnable action){
        TextView p=text(label,12.5f,ink(0xF2),"sans-serif-medium");p.setGravity(Gravity.CENTER);p.setPadding(px(16),0,px(16),0);
        control(p,index,1.08f,f->{p.setBackground(round(f?focusFill():restFill(),16));p.setTextColor(f?focusInk():ink(0xF2));},()->hero!=null?hero:hero(),action);
        return p;
    }
    @Override void setActive(boolean value){
        if(value==active)return;super.setActive(value);
        if(value){
            try{sessions.addOnActiveSessionsChangedListener(changed,listener,handler);access=true;pick(sessions.getActiveSessions(listener));}
            catch(SecurityException e){access=false;use(null);}
        } else {
            try{sessions.removeOnActiveSessionsChangedListener(changed);}catch(RuntimeException ignored){}
            use(null);handler.removeCallbacks(tick);
        }
    }
    @Override void refresh(){if(active)repick();}
    private void repick(){try{pick(sessions.getActiveSessions(listener));}catch(SecurityException e){access=false;use(null);}}
    /** The session to show: one that is playing, else the most recent one with a title. */
    private void pick(List<MediaController> list){
        MediaController best=null;
        if(list!=null){for(MediaController c:list){if(c.getPackageName().equals(getContext().getPackageName()))continue;if(c.getPlaybackState()!=null&&c.getPlaybackState().getState()==PlaybackState.STATE_PLAYING){best=c;break;}}
            if(best==null)for(MediaController c:list)if(!c.getPackageName().equals(getContext().getPackageName())&&!titleOf(c).isEmpty()){best=c;break;}}
        use(best);
    }
    private void use(MediaController c){
        if(controller!=null&&c!=null&&controller.getSessionToken().equals(c.getSessionToken())){render();return;}
        if(controller!=null)controller.unregisterCallback(callback);
        controller=c;if(c!=null)c.registerCallback(callback,handler);
        render();
    }
    private static String titleOf(MediaController c){MediaMetadata m=c.getMetadata();if(m==null)return "";CharSequence t=m.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);if(t==null)t=m.getText(MediaMetadata.METADATA_KEY_TITLE);return t==null?"":t.toString();}
    private boolean playing(){if(Promo.on)return Promo.playing;PlaybackState s=controller==null?null:controller.getPlaybackState();return s!=null&&(s.getState()==PlaybackState.STATE_PLAYING||s.getState()==PlaybackState.STATE_BUFFERING);}
    private String appName(String pkg){MainActivity.App a=host.app(pkg);return a==null?"":a.label;}

    /** Updates the widget in place; focus moves to a visible control if its control disappears. */
    private void render(){
        if(Promo.on){promo();return;}
        View focused=findFocus();
        boolean has=controller!=null&&!titleOf(controller).isEmpty();
        int media=has?VISIBLE:GONE;
        for(View v:new View[]{prev,play,next,expand,track,fill,elapsed,remaining,openPill})v.setVisibility(media);
        shortcuts.setVisibility(has?GONE:VISIBLE);
        if(has){
            MediaMetadata m=controller.getMetadata();String pkg=controller.getPackageName();
            source.setText(appName(pkg).toUpperCase(Locale.ENGLISH));title.setText(titleOf(controller));
            CharSequence a=m.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);if(a==null)a=m.getText(MediaMetadata.METADATA_KEY_ARTIST);if(a==null)a=m.getText(MediaMetadata.METADATA_KEY_ALBUM);
            artist.setText(a==null?"":a);
            long actions=controller.getPlaybackState()==null?~0L:controller.getPlaybackState().getActions();
            prev.setAlpha((actions&PlaybackState.ACTION_SKIP_TO_PREVIOUS)!=0?1:.4f);next.setAlpha((actions&PlaybackState.ACTION_SKIP_TO_NEXT)!=0?1:.4f);
            playGlyph.kind(playing()?"pause":"play");
            openPill.setText("Abrir "+appName(pkg));
            artwork(m);progress();
            if(full!=null)full.update();
            handler.removeCallbacks(tick);if(active&&playing())handler.postDelayed(tick,1000);
        } else {
            if(full!=null&&full.open()){host.closeOverlay();full=null;}
            artKey="";artImage.setImageDrawable(null);source.setText("REPRODUCIENDO");
            title.setText(access?"No se reproduce nada":"Ve lo que se reproduce");
            artist.setText(access?"La música que pongas en esta TV aparece aquí.":"Permite a Luma acceder a las notificaciones.");
            fillShortcuts();
        }
        if(focused!=null&&(focused.getVisibility()!=VISIBLE||focused.getParent()==null||!focused.isShown())){View first=firstControl();if(first!=null)first.requestFocus();}
    }
    /** The promo demo's song (Promo): the same layout as a real session, on a made-up clock. */
    private void promo(){
        for(View v:new View[]{prev,play,next,expand,track,fill,elapsed,remaining,openPill})v.setVisibility(VISIBLE);
        shortcuts.setVisibility(GONE);prev.setAlpha(1);next.setAlpha(1);
        source.setText(Promo.SOURCE.toUpperCase(Locale.ENGLISH));title.setText(Promo.SONG);artist.setText(Promo.ARTIST);
        playGlyph.kind(Promo.playing?"pause":"play");
        openPill.setText("Abrir "+Promo.SOURCE);
        if(!artKey.equals("promo")){artKey="promo";fade(Promo.artwork(px(ART)*2));}
        progress();
        handler.removeCallbacks(tick);if(active&&Promo.playing)handler.postDelayed(tick,1000);
    }
    private View firstControl(){if(play.getVisibility()==VISIBLE)return play;return shortcuts.getChildCount()>0?shortcuts.getChildAt(0):null;}
    private String shortcutKey="";
    private void fillShortcuts(){
        ArrayList<String> apps=new ArrayList<>();
        if(!access)apps.add("@allow");
        else for(String pkg:MUSIC)if(host.app(pkg)!=null&&apps.size()<2)apps.add(pkg);
        String key=String.join(",",apps);if(key.equals(shortcutKey))return;shortcutKey=key;
        boolean hadFocus=shortcuts.hasFocus();shortcuts.removeAllViews();
        for(int i=0;i<apps.size();i++){final String pkg=apps.get(i);
            String[] hero=pkg.equals("@allow")?null:new String[]{"music:open:"+pkg,appName(pkg),"Abre "+appName(pkg)+" y pon algo. Aparecerá aquí, con reproducir, pausar y saltar."};
            TextView p=pill(pkg.equals("@allow")?"Permitir acceso":appName(pkg),i,hero,()->{
                if(pkg.equals("@allow"))host.start(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));else host.launch(pkg);});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,px(32));lp.rightMargin=px(8);lp.topMargin=px(1);shortcuts.addView(p,lp);}
        if(hadFocus&&shortcuts.getChildCount()>0)shortcuts.getChildAt(0).requestFocus();
    }
    private void progress(){
        if(Promo.on){long d=Promo.SONG_LENGTH,p=Promo.position();track.setVisibility(VISIBLE);fill.setVisibility(VISIBLE);fill.setScaleX(p/(float)d);elapsed.setText(time(p));remaining.setText("−"+time(d-p));return;}
        if(controller==null)return;PlaybackState s=controller.getPlaybackState();MediaMetadata m=controller.getMetadata();
        long duration=m==null?0:m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if(s==null||duration<=0){fill.setScaleX(0);track.setVisibility(INVISIBLE);fill.setVisibility(INVISIBLE);elapsed.setText("");remaining.setText("");return;}
        long position=s.getPosition();if(s.getState()==PlaybackState.STATE_PLAYING&&s.getLastPositionUpdateTime()>0)position+=(long)((SystemClock.elapsedRealtime()-s.getLastPositionUpdateTime())*s.getPlaybackSpeed());
        position=Math.max(0,Math.min(duration,position));
        track.setVisibility(VISIBLE);fill.setVisibility(VISIBLE);fill.setScaleX(position/(float)duration);
        elapsed.setText(time(position));remaining.setText("−"+time(duration-position));
        if(full!=null)full.progress(position/(float)duration,time(position),"−"+time(duration-position));
    }
    private static String time(long ms){long s=ms/1000;return s>=3600?String.format(Locale.US,"%d:%02d:%02d",s/3600,s/60%60,s%60):String.format(Locale.US,"%d:%02d",s/60,s%60);}
    private void artwork(MediaMetadata m){
        Bitmap b=m.getBitmap(MediaMetadata.METADATA_KEY_ART);if(b==null)b=m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);if(b==null)b=m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        String link=null;for(String k:new String[]{MediaMetadata.METADATA_KEY_ART_URI,MediaMetadata.METADATA_KEY_ALBUM_ART_URI,MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI})if(link==null)link=m.getString(k);
        String key=b!=null?"bitmap:"+b.getGenerationId()+":"+b.getWidth()+titleOf(controller):link==null?"":link;
        if(key.equals(artKey))return;artKey=key;
        if(b!=null){fade(b);return;}
        artImage.setImageDrawable(null);if(link==null)return;
        final String url=link;final Context context=getContext().getApplicationContext();
        WidgetBoard.IO.execute(()->{Bitmap loaded=null;
            try{InputStream in;if(url.startsWith("http")){HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(6000);c.setReadTimeout(8000);in=c.getInputStream();}else in=context.getContentResolver().openInputStream(Uri.parse(url));
                try(InputStream s=in){BitmapFactory.Options o=new BitmapFactory.Options();o.inPreferredConfig=Bitmap.Config.RGB_565;loaded=BitmapFactory.decodeStream(s,null,o);}}catch(Exception|OutOfMemoryError ignored){}
            final Bitmap result=loaded;if(result!=null)post(()->{if(url.equals(artKey))fade(result);});});
    }
    private void fade(Bitmap b){artImage.setImageBitmap(b);if(motion()){artImage.setAlpha(0f);artImage.animate().alpha(1).setDuration(300).start();}if(full!=null)full.art(b);}

    // ---- Full-screen player ----

    private void openFull(){if(controller==null&&!Promo.on)return;full=new Full();full.update();}
    /** The song over its own blurred artwork: big cover, title, progress and large controls. Back closes it; so does
     *  a pause that lasts a minute. Cover and background drift slowly, which also keeps the panel from marking. */
    final class Full {
        final FrameLayout root=new FrameLayout(getContext());
        final ImageView backdrop=new ImageView(getContext()),cover=new ImageView(getContext());
        final TextView app,song,by,from,to,clock;
        final View bar,barFill;
        final Glyph playIcon;
        final Runnable idle=()->{if(!playing()&&open()){host.closeOverlay();full=null;}};
        /** False once Back closed it; Home then forgets it, so nothing here closes another panel. */
        boolean open(){if(root.isAttachedToWindow())return true;if(full==this)full=null;handler.removeCallbacks(idle);return false;}
        Full(){
            Context c=getContext();FrameLayout o=host.overlay();o.addView(root,new LayoutParams(-1,-1));root.setBackgroundColor(0xFF0B0B0F);
            backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);backdrop.setAlpha(.55f);root.addView(backdrop,new LayoutParams(-1,-1));
            if(android.os.Build.VERSION.SDK_INT>=31)backdrop.setRenderEffect(RenderEffect.createBlurEffect(px(40),px(40),Shader.TileMode.CLAMP));
            View shade=new View(c);shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0x99000000,0x66000000,0xB3000000}));root.addView(shade,new LayoutParams(-1,-1));
            FrameLayout frame=new FrameLayout(c);frame.setClipToOutline(true);frame.setBackground(round(0x33FFFFFF,22));frame.setElevation(px(30));
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);frame.addView(cover,new LayoutParams(-1,-1));root.addView(frame,pos(300,300,90,120));
            float tx=440,tw=440;
            app=text("",12,0xB3FFFFFF,"sans-serif-medium");app.setLetterSpacing(.12f);root.addView(app,pos(tw,16,tx,150));
            song=text("",38,Color.WHITE,"sans-serif-medium");song.setLetterSpacing(-.02f);root.addView(song,pos(tw,48,tx,172));
            by=text("",20,0xCCFFFFFF,"sans-serif");root.addView(by,pos(tw,26,tx,226));
            bar=new View(c);bar.setBackground(round(0x40FFFFFF,2));root.addView(bar,pos(tw,4,tx,286));
            barFill=new View(c);barFill.setBackground(round(Color.WHITE,2));barFill.setPivotX(0);root.addView(barFill,pos(tw,4,tx,286));
            from=text("",12,0x99FFFFFF,"sans-serif");root.addView(from,pos(80,16,tx,298));
            to=text("",12,0x99FFFFFF,"sans-serif");to.setGravity(Gravity.RIGHT);root.addView(to,pos(80,16,tx+tw-80,298));
            clock=text("",18,0xCCFFFFFF,"sans-serif-medium");clock.setGravity(Gravity.RIGHT);root.addView(clock,pos(120,24,960-48-120,36));
            View p=big("previous",56,tx,340,()->{if(controller!=null)controller.getTransportControls().skipToPrevious();});
            View pl=big("play",72,tx+72,332,()->play.performClick());playIcon=(Glyph)((FrameLayout)pl).getChildAt(1);
            big("next",56,tx+160,340,()->{if(controller!=null)controller.getTransportControls().skipToNext();});
            if(motion()){ // a slow drift, both ways forever
                frame.animate().translationY(-px(8)).scaleX(1.02f).scaleY(1.02f).setDuration(9000).setInterpolator(ease).withEndAction(new Runnable(){boolean back;public void run(){if(!root.isAttachedToWindow())return;back=!back;frame.animate().translationY(back?0:-px(8)).scaleX(back?1:1.02f).scaleY(back?1:1.02f).setDuration(9000).withEndAction(this).start();}}).start();
                backdrop.setScaleX(1.15f);backdrop.setScaleY(1.15f);backdrop.animate().translationX(px(20)).setDuration(20000).withEndAction(new Runnable(){boolean back;public void run(){if(!root.isAttachedToWindow())return;back=!back;backdrop.animate().translationX(back?-px(20):px(20)).setDuration(20000).withEndAction(this).start();}}).start();
            }
            if(artImage.getDrawable() instanceof android.graphics.drawable.BitmapDrawable)art(((android.graphics.drawable.BitmapDrawable)artImage.getDrawable()).getBitmap());
            post(pl::requestFocus);
        }
        private View big(String kind,float size,float x,float y,Runnable action){
            FrameLayout b=new FrameLayout(getContext());View disc=new View(getContext());b.addView(disc,new LayoutParams(-1,-1));
            Glyph g=new Glyph(getContext()).kind(kind);float gs=size*.42f;b.addView(g,pos(gs,gs,(size-gs)/2,(size-gs)/2));
            b.setPivotX(px(size/2));b.setPivotY(px(size/2));
            control(b,0,1.12f,f->{disc.setBackground(oval(f?0xF7FFFFFF:0x26FFFFFF));g.color(f?0xFF111111:Color.WHITE);},()->new String[]{"music:full",song.getText().toString(),""},action);
            root.addView(b,pos(size,size,x,y));return b;
        }
        void art(Bitmap b){cover.setImageBitmap(b);backdrop.setImageBitmap(b);}
        void update(){
            if(!open())return;
            song.setText(title.getText());by.setText(artist.getText());app.setText(source.getText());
            playIcon.kind(playing()?"pause":"play");
            clock.setText(new java.text.SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date()));
            handler.removeCallbacks(idle);if(!playing())handler.postDelayed(idle,60000);
        }
        void progress(float f,String a,String b){if(!open())return;barFill.setScaleX(f);from.setText(a);to.setText(b);clock.setText(new java.text.SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date()));}
    }
    private String[] hero(){
        if(Promo.on)return new String[]{"music:promo",Promo.SONG,Promo.ARTIST+" · "+Promo.SOURCE};
        if(controller!=null&&!titleOf(controller).isEmpty())return new String[]{"music:"+titleOf(controller),titleOf(controller),UpNextWidget.join(artist.getText().toString(),appName(controller.getPackageName()))};
        return access?new String[]{"music:none","Reproduciendo","Pon música en una app y contrólala desde Inicio."}:new String[]{"music:allow","Reproduciendo","Permite el acceso a las notificaciones para que Luma muestre y controle la música que suena en esta TV. Luma no lee tus notificaciones."};
    }
    @Override protected void onDetachedFromWindow(){setActive(false);handler.removeCallbacksAndMessages(null);super.onDetachedFromWindow();}
}
