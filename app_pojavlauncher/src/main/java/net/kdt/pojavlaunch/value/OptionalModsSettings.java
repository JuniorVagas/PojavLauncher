package net.kdt.pojavlaunch.value;

import java.util.HashMap;

public class OptionalModsSettings {
    public HashMap<String, OptionalModInfo> optionalMods;
    public static class OptionalModInfo{
        public boolean selected, performance, optional;
        public String name, path;
    }
}
