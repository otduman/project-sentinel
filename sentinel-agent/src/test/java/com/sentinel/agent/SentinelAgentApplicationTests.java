package com.sentinel.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {"gemini.api.key=test"})
class SentinelAgentApplicationTests {

	@Test
	void contextLoads() {
	}

}
