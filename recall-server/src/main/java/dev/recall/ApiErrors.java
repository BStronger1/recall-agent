package dev.recall;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.ResponseEntity;
import java.io.IOException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class}) public ResponseEntity<?> bad(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", e instanceof MethodArgumentNotValidException ? "请检查必填项和输入长度。" : e.getMessage()));
    }
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<?> upstream(IllegalStateException e) { return ResponseEntity.status(502).body(Map.of("error", e.getMessage())); }
    @ExceptionHandler(IOException.class) public ResponseEntity<?> storage(IOException e) { return ResponseEntity.internalServerError().body(Map.of("error", "数据读写失败，请联系维护者。")); }
}
