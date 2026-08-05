package com.example.ainewsdigest;

import com.example.ainewsdigest.support.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfig.class)
class AinewsdigestApplicationTests {

	@Test
	void contextLoads() {
	}

}
