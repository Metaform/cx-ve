package com.metaform.cxve.hub;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// Profile "test": in-memory MembershipRepository, so the context loads without a database.
// This is also the guard against bean-definition clashes (e.g. a @Bean method sharing its name
// with a @Service class) — those only surface when the full context is assembled.
@SpringBootTest
@ActiveProfiles("test")
class MembershipHubApplicationTests {

    @Test
    void contextLoads() {
    }
}
