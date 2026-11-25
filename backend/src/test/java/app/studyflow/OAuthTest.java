package app.studyflow;
import org.junit.jupiter.api.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
class OAuthTest {
    OAuthSession.Credentials config(String expiry){return new OAuthSession.Credentials("https://lms.example.edu","1.49","1.82","client","secret","enrollment:own_enrollment:read","old-access","old-refresh",expiry);}
    String state(String url){return Arrays.stream(URI.create(url).getRawQuery().split("&")).filter(s->s.startsWith("state=")).findFirst().orElseThrow().substring(6);}
    @Test void stateIsRequiredAndCodeIsSingleUse() throws Exception {
        AtomicReference<OAuthSession.Credentials> saved=new AtomicReference<>();AtomicBoolean connected=new AtomicBoolean();
        try(var oauth=new OAuthSession(saved::set,source->{assertNotNull(saved.get());connected.set(true);},form->{assertEquals("authorization_code",form.get("grant_type"));return Json.MAPPER.readTree("{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");})) {
            String state=state(oauth.start(config(null)));
            assertThrows(IllegalArgumentException.class,()->oauth.complete("wrong","code"));assertFalse(connected.get());
            oauth.complete(state,"code");assertTrue(connected.get());assertEquals("new-refresh",saved.get().refreshToken());
            assertThrows(IllegalArgumentException.class,()->oauth.complete(state,"code"));
        }
    }
    @Test void refreshRotatesAndPersistsBeforeReturning() throws Exception {
        AtomicReference<OAuthSession.Credentials> saved=new AtomicReference<>();
        try(var oauth=new OAuthSession(saved::set,source->{},form->{assertEquals("refresh_token",form.get("grant_type"));assertEquals("old-refresh",form.get("refresh_token"));return Json.MAPPER.readTree("{\"access_token\":\"next\",\"refresh_token\":\"rotated\",\"expires_in\":3600}");})){
            oauth.restore(config(Instant.now().minusSeconds(1).toString()));assertEquals("next",oauth.accessToken());assertEquals("rotated",saved.get().refreshToken());assertEquals("next",oauth.accessToken());
        }
    }
    @Test void vaultFailurePreventsConnection() throws Exception {
        AtomicBoolean connected=new AtomicBoolean();
        try(var oauth=new OAuthSession(c->{throw new IllegalStateException("Keychain unavailable");},source->connected.set(true),form->Json.MAPPER.readTree("{\"access_token\":\"next\",\"expires_in\":3600}"))){String state=state(oauth.start(config(null)));assertThrows(IllegalStateException.class,()->oauth.complete(state,"code"));assertFalse(connected.get());}
    }
    @Test void unexpiredAccessDoesNotContactProvider() throws Exception {
        try(var oauth=new OAuthSession(c->{},source->{},form->{throw new AssertionError("No refresh expected");})){oauth.restore(config(Instant.now().plusSeconds(1000).toString()));assertEquals("old-access",oauth.accessToken());}
    }
}
