package console;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies streaming request parsing and line-level ingest validation. */
class IngestReadTest {
    private final Ingest ingest;

    /** Creates an ingest reader without a database dependency. */
    IngestReadTest() throws Exception {
        ingest = new Ingest(null, new ObjectMapper(), new Zones(new ObjectMapper()));
    }

    /** Verifies invalid non-empty lines are isolated with stable line numbers. */
    @Test
    void isolatesInvalidNonEmptyLines() throws Exception {
        String valid = """
                {"reportId":"r-1","assetId":"AST-1","observedAt":"2026-11-02T06:00:05.120Z","lat":1,"lon":2}
                """.strip();
        MockHttpServletRequest request = request("   \n\n" + valid + "\n{\"reportId\":\"missing\"}\n");

        Ingest.Batch batch = ingest.read(request);

        assertThat(batch.reports()).hasSize(1);
        assertThat(batch.invalid()).extracting(item -> item.rejection().line()).containsExactly(1, 3);
        assertThat(batch.invalid()).extracting(item -> item.rejection().code())
                .containsExactly("MALFORMED_JSON", "MISSING_FIELD");
    }

    /** Verifies optional fields reject values of the wrong type. */
    @Test
    void rejectsInvalidOptionalFieldTypes() throws Exception {
        MockHttpServletRequest request = request("""
                {"reportId":"r-1","assetId":"AST-1","observedAt":"2026-11-02T06:00:05.120Z","lat":1,"lon":2,"speedKn":"fast"}
                """);

        Ingest.Batch batch = ingest.read(request);

        assertThat(batch.reports()).isEmpty();
        assertThat(batch.invalid().getFirst().rejection().code()).isEqualTo("INVALID_FIELD");
    }

    /** Verifies line and byte budgets are enforced while streaming. */
    @Test
    void enforcesNonEmptyLineAndByteLimitsWhileStreaming() {
        String tooMany = IntStream.range(0, 1001).mapToObj(index -> "{}").collect(Collectors.joining("\n"));
        assertThatThrownBy(() -> ingest.read(request(tooMany)))
                .isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.status).isEqualTo(413));

        byte[] tooLarge = new byte[5_000_001];
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/x-ndjson");
        request.setContent(tooLarge);
        assertThatThrownBy(() -> ingest.read(request))
                .isInstanceOfSatisfying(Problem.class, problem -> assertThat(problem.status).isEqualTo(413));
    }

    /** Creates an NDJSON mock request from a text body. */
    private static MockHttpServletRequest request(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/x-ndjson; charset=utf-8");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
