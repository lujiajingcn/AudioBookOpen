import java.io.File;
import java.io.RandomAccessFile;

public class LockProbe {
    static String base = "C:\\Users\\audiobook\\.gradle-alt\\native\\"
            + "c067742578af261105cb4f569cf0c3c89f3d7b1fecec35dd04571415982c5e48\\windows-amd64\\";

    static void tryOpen(String label, File f, String mode) {
        try {
            RandomAccessFile raf = new RandomAccessFile(f, mode);
            System.out.println("OK    " + label + " mode=" + mode);
            raf.close();
        } catch (Throwable t) {
            System.out.println("FAIL  " + label + " mode=" + mode + " -> " + t.getMessage());
        }
    }

    static void tryCreateFresh(String label, String name, String mode) {
        File f = new File(base + name);
        try {
            RandomAccessFile raf = new RandomAccessFile(f, mode);
            raf.writeByte(1);
            raf.close();
            System.out.println("OK    " + label + " (fresh) mode=" + mode);
        } catch (Throwable t) {
            System.out.println("FAIL  " + label + " (fresh) mode=" + mode + " -> " + t.getMessage());
        }
    }

    public static void main(String[] args) {
        File lock = new File(base + "native-platform.dll.lock");
        File probe = new File(base + "probe.txt");
        tryOpen("existing .lock", lock, "r");
        tryOpen("existing .lock", lock, "rw");
        tryOpen("probe.txt", probe, "rw");
        tryCreateFresh("fresh1.lock", "fresh1.lock", "rw");
        tryCreateFresh("fresh2.tmp", "fresh2.tmp", "rw");
        tryCreateFresh("fresh3.dll.lock", "fresh3.dll.lock", "rw");
        try {
            System.out.println("delete existing .lock -> " + lock.delete() + " existsAfter=" + lock.exists());
        } catch (Throwable t) {
            System.out.println("delete FAILED: " + t.getMessage());
        }
        if (!lock.exists()) {
            tryCreateFresh("recreated .lock", "native-platform.dll.lock", "rw");
        }
    }
}
