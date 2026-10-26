package dev.luma.tv;

import java.util.*;

/** Which Watch Next programmes Up Next shows. Apps publish one entry per episode, so a series can fill the row and
 *  hide every other app: keep one card per series and at most perApp per app. Pure, for tools/WatchNextTest.java. */
final class WatchNext {
    /** Indices to keep, in order, from programmes sorted most recent first; a null title is never a duplicate. */
    static List<Integer> pick(List<String> pkgs,List<String> titles,int perApp,int max){
        List<Integer> out=new ArrayList<>();Set<String> shows=new HashSet<>();Map<String,Integer> perPkg=new HashMap<>();
        for(int i=0;i<pkgs.size()&&out.size()<max;i++){
            String pkg=pkgs.get(i),title=titles.get(i);
            if(perPkg.getOrDefault(pkg,0)>=perApp||title!=null&&!shows.add(pkg+"\n"+title))continue;
            perPkg.merge(pkg,1,Integer::sum);out.add(i);
        }
        return out;
    }
}
