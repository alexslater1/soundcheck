package app.soundcheck;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * Soundcheck backend: one Spring Boot codebase split into Spring Modulith modules and
 * run as the roles listed in {@code soundcheck.roles}.
 */
@SpringBootApplication
@Modulithic(systemName = "Soundcheck", sharedModules = "shared")
public class SoundcheckApplication {

    public static void main(String[] args) {
        SpringApplication.run(SoundcheckApplication.class, args);
    }
}
