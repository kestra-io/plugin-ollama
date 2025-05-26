package io.kestra.plugin.ollama.cli;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.*;
import io.kestra.core.models.tasks.runners.AbstractLogConsumer;
import io.kestra.core.models.tasks.runners.TaskRunner;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;
import io.kestra.plugin.scripts.exec.scripts.runners.CommandsWrapper;
import io.kestra.plugin.scripts.runner.docker.Docker;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import lombok.*;
import lombok.experimental.SuperBuilder;

import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Execute Ollama commands to interact with LLM models."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Pull a model and generate text completion",
            code = """
                id: ollama_flow
                namespace: company.team

                tasks:
                  - id: ollama_cli
                    type: io.kestra.plugin.ollama.cli.OllamaCLI
                    commands:
                      - ollama pull llama2
                      - ollama run llama2 "Tell me a joke about AI" > completion.txt
                    outputFiles:
                      - completion.txt
                """
        ),
        @Example(
            full = true,
            title = "List available models and output as JSON",
            code = """
                id: ollama_list_models
                namespace: company.team

                tasks:
                  - id: list_models
                    type: io.kestra.plugin.ollama.cli.OllamaCLI
                    commands:
                      - ollama list --format json > models.json
                    outputFiles:
                      - models.json
                """
        )
    }
)
public class OllamaCLI extends Task implements RunnableTask<ScriptOutput>, NamespaceFilesInterface, InputFilesInterface, OutputFilesInterface {
    private static final String DEFAULT_IMAGE = "ollama/ollama";

    @Schema(
        title = "The commands to run."
    )
    @NotNull
    protected Property<List<String>> commands;

    @Schema(
        title = "Additional environment variables for the current process."
    )
    protected Property<Map<String, String>> env;

    @Schema(
        title = "The task runner to use.",
        description = "Task runners are provided by plugins, each have their own properties."
    )
    @PluginProperty
    @Builder.Default
    @Valid
    private TaskRunner<?> taskRunner = Docker.instance();

    @Schema(
        title = "The task runner container image.",
        description = "Defaults to 'ollama/ollama' for Ollama operations."
    )
    @Builder.Default
    private Property<String> containerImage = Property.of(DEFAULT_IMAGE);

    private NamespaceFiles namespaceFiles;
    private Object inputFiles;
    private Property<List<String>> outputFiles;

    @Override
    public ScriptOutput run(RunContext runContext) throws Exception {
        var renderedOutputFiles = runContext.render(this.outputFiles).asList(String.class);

        Map<String, String> envs = getEnv(runContext);

        CommandsWrapper commands = new CommandsWrapper(runContext)
            .withTaskRunner(this.taskRunner)
            .withContainerImage(DEFAULT_IMAGE)
            .withInterpreter(Property.ofValue(List.of("/bin/sh", "-c")))
            .withLogConsumer(suppressStderrLogConsumer(runContext))
            .withBeforeCommands(Property.ofValue(List.of("ollama serve & sleep 5")))
            .withCommands(this.commands)
            .withEnv(envs).withContainerImage(runContext.render(this.containerImage).as(String.class).orElseThrow())
            .withNamespaceFiles(namespaceFiles)
            .withInputFiles(inputFiles)
            .withOutputFiles(renderedOutputFiles.isEmpty() ? null : renderedOutputFiles);

        return commands.run();
    }

    private Map<String, String> getEnv(RunContext runContext) throws IOException, IllegalVariableEvaluationException {
        Map<String, String> envs = new HashMap<>();

        var renderedEnv = runContext.render(this.env).asMap(String.class, String.class);
        if (!renderedEnv.isEmpty()) {
            envs.putAll(renderedEnv);
        }

        return envs;
    }

    private static AbstractLogConsumer suppressStderrLogConsumer(RunContext runContext) {
        return new AbstractLogConsumer() {
            @Override
            public void accept(String line, Boolean isStdErr) {
                this.accept(line, isStdErr, Instant.now());
            }

            @Override
            public void accept(String line, Boolean isStdErr, Instant instant) {
                if (line == null || line.trim().isEmpty()) {
                    return;
                }

                if (!Boolean.TRUE.equals(isStdErr)) {
                    runContext.logger().info(line);
                }
            }
        };
    }
}