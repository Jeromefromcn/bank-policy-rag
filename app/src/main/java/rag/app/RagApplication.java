package rag.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RagApplication {

    public static void main(String[] args) {
        var app = new SpringApplication(RagApplication.class);
        if (args.length > 0 && args[0].equals("ingest")) {
            // Ingest is a CLI run: no web server, exit when done.
            app.setWebApplicationType(WebApplicationType.NONE);
            // Ingest never generates text, so it must not need a chat model or its API key.
            // A command-line property outranks application.yaml; default properties would not.
            String[] withChatOff = java.util.Arrays.copyOf(args, args.length + 1);
            withChatOff[args.length] = "--spring.ai.model.chat=none";
            System.exit(SpringApplication.exit(app.run(withChatOff)));
        }
        app.run(args);
    }
}
