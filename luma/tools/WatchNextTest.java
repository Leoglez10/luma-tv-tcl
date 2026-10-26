package dev.luma.tv;

import java.util.*;

/** Checks which Watch Next programmes Up Next keeps. Run from the repository root:
 *    javac -d build/test src/dev/luma/tv/WatchNext.java tools/WatchNextTest.java && java -cp build/test dev.luma.tv.WatchNextTest */
public final class WatchNextTest {
    static int failures;
    static void expect(String what,Object actual,Object expected){
        boolean ok=expected.equals(actual);if(!ok)failures++;
        System.out.println((ok?"ok    ":"FAIL  ")+what+" → "+actual+(ok?"":"   expected "+expected));
    }
    public static void main(String[] args){
        // Most recent first, as Up Next sorts them: ViX filled the list before.
        List<String> pkgs=Arrays.asList("vix","vix","vix","netflix","netflix","netflix","prime"),
            titles=Arrays.asList("Presencias","Presencias","El Señor de los Cielos","Collateral","La era de hielo 3","Wednesday","The Boys");
        expect("one per show, two per app",WatchNext.pick(pkgs,titles,2,6),Arrays.asList(0,2,3,4,6));
        expect("limit",WatchNext.pick(pkgs,titles,2,3),Arrays.asList(0,2,3));
        expect("same title in two apps is two shows",WatchNext.pick(Arrays.asList("a","b"),Arrays.asList("X","X"),2,6),Arrays.asList(0,1));
        expect("missing title counts as its own show",WatchNext.pick(Arrays.asList("a","a"),Arrays.asList(null,null),2,6),Arrays.asList(0,1));
        System.out.println(failures==0?"all passed":failures+" failed");
        if(failures>0)System.exit(1);
    }
}
