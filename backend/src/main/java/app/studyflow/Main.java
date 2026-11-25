package app.studyflow;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
public final class Main {
    public static void main(String[] args) throws Exception {
        String token=System.getenv("STUDYFLOW_LOCAL_TOKEN");
        Path directory=Path.of(System.getenv().getOrDefault("STUDYFLOW_DATA_DIR",System.getProperty("user.home")+"/Library/Application Support/StudyFlow"));
        Files.createDirectories(directory);
        Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));
        Store store=new Store(directory.resolve("studyflow.db"));
        Api api=new Api(store,token,Integer.parseInt(System.getenv().getOrDefault("STUDYFLOW_PORT","18743")));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {api.close();try{store.close();}catch(Exception ignored){}}));
        String parent=System.getenv("STUDYFLOW_PARENT_PID");
        if(parent!=null) {
            ProcessHandle owner=ProcessHandle.of(Long.parseLong(parent)).orElseThrow();
            owner.onExit().thenRun(() -> System.exit(0));
        }
        api.start();System.out.println("StudyFlow backend listening on loopback port "+api.port());
    }
}
