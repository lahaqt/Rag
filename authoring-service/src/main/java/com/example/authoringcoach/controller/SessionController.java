package com.example.authoringcoach.controller;

import com.example.authoringcoach.security.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/session")
public class SessionController {
    @GetMapping
    public SessionResponse current(HttpServletRequest request) {
        boolean admin = RequestIdentity.isAdmin(request);
        return new SessionResponse(RequestIdentity.requiredUserId(request), admin ? List.of("ADMIN") : List.of("STUDENT"));
    }

    public record SessionResponse(String userId, List<String> roles) {
    }
}
