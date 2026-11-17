import jadx.api.*;
import java.io.File;
public class ExportApk {
  public static void main(String[] argv) throws Exception {
    JadxArgs args = new JadxArgs();
    args.setInputFile(new File(argv[0]));
    args.setOutDir(new File(argv[1]));
    args.setThreadsCount(3);
    args.setShowInconsistentCode(true);
    try (JadxDecompiler decompiler = new JadxDecompiler(args)) {
      decompiler.load();
      decompiler.save();
      System.out.println("Export completed; top-level classes=" + decompiler.getClasses().size());
    }
  }
}
