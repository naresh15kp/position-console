package console;

import com.fasterxml.jackson.databind.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;

/** Starts the Spring Boot position console application. */
@SpringBootApplication
public class App {
    /** Launches the application with the supplied command-line arguments. */
    public static void main(String[] args) { SpringApplication.run(App.class, args); }

    /** Adds a validated request ID to responses and the logging context. */
    @Bean
    OncePerRequestFilter requestId() {
        return new OncePerRequestFilter() {
            /** Applies request-ID handling around the remainder of the filter chain. */
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws ServletException, IOException {
                String id = req.getHeader("X-Request-Id");
                // Only trusted characters enter response headers and the logging context.
                if (id == null || !id.matches("[a-zA-Z0-9._:-]{1,128}")) id = UUID.randomUUID().toString();
                res.setHeader("X-Request-Id", id);
                try (var ignored = MDC.putCloseable("requestId", id)) { chain.doFilter(req, res); }
            }
        };
    }

    /** Formats an instant with the API's millisecond UTC representation. */
    static String time(Instant value) {
        return new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(value);
    }
}

/** Represents an expected API failure with an HTTP status and stable code. */
class Problem extends RuntimeException {
    final int status;
    final String code;

    /** Creates an API problem for the supplied status and code. */
    Problem(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    /** Creates the standard request-validation problem. */
    static Problem validation() { return new Problem(400, "VALIDATION_FAILED"); }
    /** Creates the standard missing-resource problem. */
    static Problem missing() { return new Problem(404, "NOT_FOUND"); }
}

/** Converts application and framework exceptions into problem responses. */
@RestControllerAdvice
class Problems {
    private static final Logger LOG = LoggerFactory.getLogger(Problems.class);

    /** Serializes an expected application problem. */
    @ExceptionHandler(Problem.class)
    ResponseEntity<Map<String, Object>> problem(Problem p) {
        return ResponseEntity.status(p.status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(Map.of("type", "about:blank", "title", HttpStatus.valueOf(p.status).getReasonPhrase(),
                        "status", p.status, "code", p.code));
    }

    /** Maps malformed request input to the validation problem contract. */
    @ExceptionHandler({
        org.springframework.web.bind.ServletRequestBindingException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class
    })
    ResponseEntity<Map<String, Object>> badRequest(Exception ignored) { return problem(Problem.validation()); }

    /** Logs unexpected failures and hides their implementation details. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        LOG.error("Request failed ({})", e.getClass().getSimpleName());
        return problem(new Problem(500, "INTERNAL_ERROR"));
    }
}