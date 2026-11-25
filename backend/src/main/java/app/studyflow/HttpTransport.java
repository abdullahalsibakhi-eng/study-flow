package app.studyflow;

import java.io.ByteArrayOutputStream;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Bounds both the complete response lifetime and body size, including slow streaming bodies. */
final class HttpTransport {
    private static final HttpClient CLIENT=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    static HttpResponse<byte[]> send(HttpRequest request,int maximumBytes,int timeoutSeconds) throws Exception {
        var future=CLIENT.sendAsync(request,ignored -> new LimitedBody(maximumBytes));
        try { return future.get(timeoutSeconds,TimeUnit.SECONDS); }
        catch(TimeoutException|InterruptedException e) {future.cancel(true);if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw e;}
    }
    static long retryAfter(String value,Instant now) {
        try {return Math.max(1,Math.min(2_592_000,Long.parseLong(value)));}
        catch(NumberFormatException ignored) {
            try {return Math.max(1,Math.min(2_592_000,Duration.between(now,ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toSeconds()));}
            catch(Exception invalid) {return 60;}
        }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximum;private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();private Flow.Subscription subscription;
        LimitedBody(int maximum){this.maximum=maximum;}
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        public void onNext(List<ByteBuffer> chunks){
            for(var chunk:chunks){
                if(chunk.remaining()>maximum-bytes.size()){subscription.cancel();result.completeExceptionally(new IllegalArgumentException("Remote response exceeds size limit"));return;}
                byte[] data=new byte[chunk.remaining()];chunk.get(data);bytes.writeBytes(data);
            }
            subscription.request(1);
        }
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
