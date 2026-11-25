package app.studyflow;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Registered personal-app authorization-code flow. Tokens are committed to Keychain before use. */
public final class OAuthSession implements AutoCloseable {
    public static final String REDIRECT="http://127.0.0.1:18744/oauth/callback";
    public record Credentials(String host,String lpVersion,String leVersion,String clientId,String clientSecret,String scopes,String accessToken,String refreshToken,String expiresAt) {
        public Credentials {
            new Brightspace.Config(host,lpVersion,leVersion);
            Domain.required(clientId,"Registered client ID");Domain.required(clientSecret,"Registered client secret");Domain.required(scopes,"Registered scopes");
        }
    }
    @FunctionalInterface public interface Vault { void save(Credentials credentials) throws Exception; }
    @FunctionalInterface public interface Exchange { JsonNode exchange(Map<String,String> form) throws Exception; }
    private final Vault vault;private final Exchange exchange;private final Consumer<SyncService.Source> onConnected;
    private HttpServer callback;private ScheduledExecutorService timer;
    private Credentials credentials;private String state;private Instant expiration;private volatile String status="Not connected";
    public OAuthSession(Vault vault,Consumer<SyncService.Source> onConnected) {this(vault,onConnected,OAuthSession::request);}
    public OAuthSession(Vault vault,Consumer<SyncService.Source> onConnected,Exchange exchange) {this.vault=vault;this.onConnected=onConnected;this.exchange=exchange;}
    public synchronized String start(Credentials config) throws Exception {
        closeCallback();credentials=config;
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);state=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);expiration=Instant.now().plusSeconds(600);
        callback=HttpServer.create(new InetSocketAddress("127.0.0.1",18744),4);
        callback.createContext("/oauth/callback",ex -> {
            int code=200;String response="Authorization completed. Return to StudyFlow.";
            try {
                if(!ex.getRequestMethod().equals("GET") || ex.getRequestURI().toString().length()>16000)throw new IllegalArgumentException("Invalid callback");
                Map<String,String> query=new HashMap<>();String raw=ex.getRequestURI().getRawQuery();
                if(raw!=null)for(String pair:raw.split("&")){String[] fields=pair.split("=",2);if(fields.length==2 && query.put(URLDecoder.decode(fields[0],StandardCharsets.UTF_8),URLDecoder.decode(fields[1],StandardCharsets.UTF_8))!=null)throw new IllegalArgumentException("Duplicate callback field");}
                complete(query.get("state"),query.get("code"));
            } catch(Exception e) {code=400;response="Authorization could not be completed. Return to StudyFlow and start sign-in again.";}
            byte[] body=response.getBytes(StandardCharsets.UTF_8);ex.getResponseHeaders().set("Content-Type","text/plain; charset=utf-8");ex.getResponseHeaders().set("Cache-Control","no-store");ex.getResponseHeaders().set("Referrer-Policy","no-referrer");ex.sendResponseHeaders(code,body.length);ex.getResponseBody().write(body);ex.close();
        });
        callback.start();timer=Executors.newSingleThreadScheduledExecutor();timer.schedule(this::closeCallback,10,TimeUnit.MINUTES);status="Waiting for browser authorization";
        return "https://auth.brightspace.com/oauth2/auth?"+form(Map.of("response_type","code","client_id",config.clientId(),"redirect_uri",REDIRECT,"scope",config.scopes(),"state",state));
    }
    synchronized void complete(String received,String code) throws Exception {
        if(state==null || received==null || !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),received.getBytes(StandardCharsets.UTF_8)) || Instant.now().isAfter(expiration))throw new IllegalArgumentException("Invalid or expired OAuth state");
        state=null;
        if(code==null || code.isBlank()){status="Authorization was declined";throw new IllegalArgumentException("No authorization code");}
        try {accept(exchange.exchange(Map.of("grant_type","authorization_code","client_id",credentials.clientId(),"client_secret",credentials.clientSecret(),"redirect_uri",REDIRECT,"code",code)));onConnected.accept(this::fetch);status="Authorized; automatic refresh available when granted";}
        catch(Exception e){status="Authorization failed. Check registered credentials, redirect and scopes.";throw e;}
    }
    public synchronized void restore(Credentials credentials) {this.credentials=credentials;status="Restored from Keychain";onConnected.accept(this::fetch);}
    private SyncService.Snapshot fetch() throws Exception {
        String token;Brightspace.Config config;
        synchronized(this){token=accessToken();config=new Brightspace.Config(credentials.host(),credentials.lpVersion(),credentials.leVersion());}
        return new Brightspace(config,token).fetch();
    }
    synchronized String accessToken() throws Exception {
        if(credentials==null)throw new Brightspace.RemoteFailure(401,60);
        if(credentials.expiresAt()==null || Instant.parse(credentials.expiresAt()).isBefore(Instant.now().plusSeconds(90))) {
            if(credentials.refreshToken()==null || credentials.refreshToken().isBlank())throw new Brightspace.RemoteFailure(401,60);
            accept(exchange.exchange(Map.of("grant_type","refresh_token","client_id",credentials.clientId(),"client_secret",credentials.clientSecret(),"refresh_token",credentials.refreshToken())));
        }
        return credentials.accessToken();
    }
    synchronized void accept(JsonNode json) throws Exception {
        String access=json.path("access_token").asText();String refresh=json.path("refresh_token").asText("");long seconds=json.path("expires_in").asLong();
        if(access.isBlank() || seconds<=0 || !json.path("token_type").asText("Bearer").equalsIgnoreCase("Bearer"))throw new IllegalArgumentException("Invalid OAuth token response");
        var updated=new Credentials(credentials.host(),credentials.lpVersion(),credentials.leVersion(),credentials.clientId(),credentials.clientSecret(),credentials.scopes(),access,refresh,Instant.now().plusSeconds(seconds).toString());
        vault.save(updated);credentials=updated;
    }
    public String status(){return status;}
    private synchronized void closeCallback(){if(callback!=null){callback.stop(0);callback=null;}if(timer!=null){timer.shutdownNow();timer=null;}state=null;}
    @Override public synchronized void close(){closeCallback();credentials=null;status="Disconnected";}
    static String form(Map<String,String> values){return values.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));}
    private static JsonNode request(Map<String,String> form) throws Exception {
        var req=HttpRequest.newBuilder(URI.create("https://auth.brightspace.com/core/connect/token")).timeout(Duration.ofSeconds(30)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(form))).build();
        var response=HttpTransport.send(req,100000,35);
        if(response.statusCode()!=200)throw new Brightspace.RemoteFailure(response.statusCode()==400?401:response.statusCode(),HttpTransport.retryAfter(response.headers().firstValue("Retry-After").orElse("60"),Instant.now()));
        return Json.MAPPER.readTree(response.body());
    }
    public static void saveKeychain(Credentials credentials) throws Exception {
        String helper=System.getenv("STUDYFLOW_KEYCHAIN_HELPER");if(helper==null)throw new IllegalStateException("Secure Keychain helper unavailable");
        Process process=new ProcessBuilder(helper).redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try(var input=process.getOutputStream()){input.write(Json.encode(credentials).getBytes(StandardCharsets.UTF_8));}
        if(!process.waitFor(15,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("Keychain write timed out");}
        if(process.exitValue()!=0)throw new IllegalStateException("Could not store rotated tokens in Keychain");
    }
}
