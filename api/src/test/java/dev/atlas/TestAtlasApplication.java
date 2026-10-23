package dev.atlas;

import org.springframework.boot.SpringApplication;

public class TestAtlasApplication {

	public static void main(String[] args) {
		SpringApplication.from(AtlasApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
