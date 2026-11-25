package app.studyflow;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
class SyncTest {
    @TempDir Path temp;
    @Test void unauthorizedStopsAutomaticRetriesAndRetainsData() throws Exception {
        try(var store=new Store(temp.resolve("db"));var sync=new SyncService(store,()->{})) {
            var c=new Domain.Course("c","CS","Computing","","manual","");store.saveCourse(c);
            var count=new AtomicInteger();sync.connect(()->{count.incrementAndGet();throw new Brightspace.RemoteFailure(401,60);});sync.tick();sync.tick();
            assertEquals(1,count.get());assertEquals("Needs attention",sync.state().connection());assertEquals("",sync.state().nextSync());assertEquals(1,store.courses().size());assertEquals("",sync.state().lastSuccess());
        }
    }
    @Test void rateLimitHonorsRetryAfter() throws Exception {
        try(var store=new Store(temp.resolve("db"));var sync=new SyncService(store,()->{})) {
            sync.connect(()->{throw new Brightspace.RemoteFailure(429,3600);});Instant before=Instant.now();sync.tick();
            assertFalse(Instant.parse(sync.state().nextSync()).isBefore(before.plusSeconds(3600)));
        }
    }
    @Test void disconnectedInFlightSnapshotCannotCommit() throws Exception {
        try(var store=new Store(temp.resolve("db"));var sync=new SyncService(store,()->{})) {
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            sync.connect(()->{entered.countDown();if(!release.await(2,TimeUnit.SECONDS))throw new TimeoutException();return new SyncService.Snapshot(List.of(new Domain.Course("c","CS","Computing","","remote","")),List.of(),"remote");});
            var executor=Executors.newSingleThreadExecutor();try {var future=executor.submit(sync::tick);assertTrue(entered.await(2,TimeUnit.SECONDS));sync.disconnect();release.countDown();future.get(2,TimeUnit.SECONDS);assertTrue(store.courses().isEmpty());}finally{release.countDown();executor.shutdownNow();}
        }
    }
}
