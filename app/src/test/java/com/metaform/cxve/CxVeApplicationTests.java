package com.metaform.cxve;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "nats.enabled=false")
class CxVeApplicationTests {

	@Test
	void contextLoads() {
	}

}
