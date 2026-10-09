package io.kestra.plugin.ollama.cli;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.plugin.scripts.runner.docker.Docker;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "Docker task runner for Ollama CLI tasks",
    description = """
        Docker-based task runner used by default for `OllamaCLI`.
        The stock `io.kestra.plugin.scripts.runner.docker.Docker` class does not accept a task-level `volume-enabled` property.
        Model cache volume mounts are added by `OllamaCLI` only when Docker volume mounts are already allowed in Kestra plugin configuration (`volume-enabled: true` for this runner type).
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Ollama CLI with explicit Ollama Docker runner",
            code = """
                id: ollama_plugin
                namespace: company.team

                tasks:
                  - id: hello
                    type: io.kestra.plugin.ollama.cli.OllamaCLI
                    taskRunner:
                      type: io.kestra.plugin.ollama.cli.OllamaDocker
                    commands:
                      - ollama list
                """
        )
    }
)
public class OllamaDocker extends Docker {

    public static OllamaDocker instance() {
        return OllamaDocker.builder().type(OllamaDocker.class.getName()).build();
    }
}
