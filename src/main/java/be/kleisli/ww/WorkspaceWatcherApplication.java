package be.kleisli.ww;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import be.kleisli.ww.core.WatcherProperties;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(WatcherProperties.class)
public class WorkspaceWatcherApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkspaceWatcherApplication.class, args);
    }
}
