package net.kdt.pojavlaunch.value;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class OptionalModsSettings {
    public HashMap<String, OptionalModInfo> optionalMods;
    public static class OptionalModInfo{
        public boolean selected, performance, optional, library;
        public String name, path;
        public List<String> depends = new ArrayList<>();
    }
}
