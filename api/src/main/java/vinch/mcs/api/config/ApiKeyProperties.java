package vinch.mcs.api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "mcs.api")
@Data
public class ApiKeyProperties {

    private String lobbyKey;
}