package com.convenientclipboard;

import com.convenientclipboard.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
public class ClipboardApplication {
    public static void main(String[] args) { SpringApplication.run(ClipboardApplication.class, args); }
}
