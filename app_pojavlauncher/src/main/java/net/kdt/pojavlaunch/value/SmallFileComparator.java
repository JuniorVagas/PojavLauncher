
package net.kdt.pojavlaunch.value;

import java.util.Comparator;

public class SmallFileComparator implements Comparator<ServerFileInfo> {
    @Override
    public int compare(ServerFileInfo f1, ServerFileInfo f2) {
        return (int) (f1.size - f2.size);
    }
}