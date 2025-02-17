package app.soundcheck;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * Soundcheck backend: one Spring Boot codebase split into Spring Modulith modules and
 * run as the roles listed in {@code soundcheck.roles}.
 */
@SpringBootApplication
@Modulithic(systemName = "Soundcheck", sharedModules = "shared")
@OpenAPIDefinition(
        info = @Info(title = "Soundcheck API", version = "v1", description = "Gig discovery and concert clips."))
public class SoundcheckApplication {

    public static void main(String[] args) {
        SpringApplication.run(SoundcheckApplication.class, args);
    }
}
