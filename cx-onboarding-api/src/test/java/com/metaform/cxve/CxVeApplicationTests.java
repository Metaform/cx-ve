package com.metaform.cxve;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

// Profile "test": in-memory OnboardingRepository, so the context loads without a database.
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "nats.enabled=false")
class CxVeApplicationTests {

	@Test
	void contextLoads() {
	}

}
