package com.chestlogger.csv;

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

/** Use Loader itself: SemVer build suffixes cannot constrain the Minecraft target. */
public final class LoaderVersionTests {
    public static void main(String[] args) throws Exception {
        check(Version.parse("0.161.0+26.2").compareTo(Version.parse("0.161.0+26.3")) == 0,
                "Loader ignores the API build suffix");
        check(VersionPredicate.parse(">=0.164.0").test(Version.parse("0.164.0+26.3")), "26.3 API accepted");
        check(!VersionPredicate.parse(">=0.164.0").test(Version.parse("0.161.0+26.3")), "Older API rejected");
        check(VersionPredicate.parse(">=0.154.2").test(Version.parse("0.154.2+26.2")), "26.2 baseline accepted");
        for (String target : new String[]{"26.2", "26.3"}) {
            var predicate = VersionPredicate.parse(target);
            check(predicate.test(Version.parse(target)), "Exact game accepted");
            check(!predicate.test(Version.parse(target.equals("26.2") ? "26.3" : "26.2")), "Other game rejected");
        }
        System.out.println("Fabric Loader version predicates passed");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
