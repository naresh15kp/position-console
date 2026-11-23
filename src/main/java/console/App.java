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

@SpringBootApplication
public class App {
    public static void main(String[] args) { SpringApplication.run(App.class, args); }

    @Bean
    OncePerRequestFilter requestId() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws ServletException, IOException {
                String id = req.getHeader("X-Request-Id");
                if (id == null || !id.matches("[a-zA-Z0-9._:-]{1,128}")) id = UUID.randomUUID().toString();
                res.setHeader("X-Request-Id", id);
                try (var ignored = MDC.putCloseable("requestId", id)) { chain.doFilter(req, res); }
            }
        };
    }

    static String time(Instant value) {
        return new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(value);
    }
}

class Problem extends RuntimeException {
    final int status;
    final String code;

    Problem(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    static Problem validation() { return new Problem(400, "VALIDATION_FAILED"); }
    static Problem missing() { return new Problem(404, "NOT_FOUND"); }
}

@RestControllerAdvice
class Problems {
    private static final Logger LOG = LoggerFactory.getLogger(Problems.class);

    @ExceptionHandler(Problem.class)
    ResponseEntity<Map<String, Object>> problem(Problem p) {
        return ResponseEntity.status(p.status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(Map.of("type", "about:blank", "title", HttpStatus.valueOf(p.status).getReasonPhrase(),
                        "status", p.status, "code", p.code));
    }

    @ExceptionHandler({
        org.springframework.web.bind.ServletRequestBindingException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class
    })
    ResponseEntity<Map<String, Object>> badRequest(Exception ignored) { return problem(Problem.validation()); }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        LOG.error("Request failed ({})", e.getClass().getSimpleName());
        return problem(new Problem(500, "INTERNAL_ERROR"));
    }
}