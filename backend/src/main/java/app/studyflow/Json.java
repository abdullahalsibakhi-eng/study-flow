package app.studyflow;
import com.fasterxml.jackson.databind.*;
public final class Json {
    public static final ObjectMapper MAPPER=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private Json() {}
    public static String encode(Object value) { try { return MAPPER.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException("Could not encode data",e); } }
    public static <T> T decode(String value,Class<T> type) { try { return MAPPER.readValue(value,type); } catch(Exception e) { throw new IllegalArgumentException("Invalid " + type.getSimpleName() + " data",e); } }
}
