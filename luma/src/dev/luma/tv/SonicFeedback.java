package dev.luma.tv;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

/** One tiny, preloaded earcon for opening an app: no audio focus, network, music interruption or idle work. */
final class SonicFeedback {
    private final SoundPool pool;
    private int open;
    private boolean openReady, released;
    SonicFeedback(Context context) {
        pool=new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        pool.setOnLoadCompleteListener((p,id,status)-> {
            if(status==0 && id==open)openReady=true;
        });
        open=pool.load(context,R.raw.soft_open,1);
    }
    void open(){if(!released && openReady)pool.play(open,.67f,.67f,2,0,1);}
    void release(){released=true;pool.release();}
}
