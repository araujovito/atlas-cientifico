package dev.atlas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import dev.atlas.documentos.UploadProperties;

@SpringBootApplication
@EnableConfigurationProperties(UploadProperties.class)
public class AtlasApplication {

	public static void main(String[] args) {
		SpringApplication.run(AtlasApplication.class, args);
	}

}
