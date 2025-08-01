package io.kestra.plugin.ollama.cli;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.*;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

                inputs:
                  - id: prompt
                    type: STRING
                    defaults: Tell me a joke about AI

                tasks:
                  - id: ollama_cli
                    type: io.kestra.plugin.ollama.cli.OllamaCLI
                    outputFiles:
                      - completion.txt
                    commands:
                      - ollama pull llama2
                      - ollama run llama2 "{{ inputs.prompt }}" > completion.txt
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
                    outputFiles:
                      - models.txt
                    commands:
                      - ollama list > models.txt
                """
        ),
        @Example(
            full = true,
            title = "Enable model caching between executions",
            code = """
                id: ollama_caching
                namespace: company.team

                inputs:
                  - id: prompt
                    type: STRING
                    defaults: Tell me a joke about AI

                tasks:
                  - id: ask_ai
                    type: io.kestra.plugin.ollama.cli.OllamaCLI
                    enableModelCaching: true
                    modelCachePath: /Users/kestra/.ollama
                    outputFiles:
                      - completion.txt
                    commands:
                      - ollama run gemma3:1b "{{ inputs.prompt }}" > completion.txt
            """
        ),
        @Example(
            full = true,
            title = "List models from a remote Ollama server",
            code = """
                id: ollama_plugin
                namespace: company.team

                tasks:
                  - id: list_models
                    type: io.kestra.plugin.ollama.cli.OllamaCLI
                    host: host.docker.internal:11434
                    commands:
                      - ollama list

            """
        )
    }
)
public class OllamaCLI extends Task implements RunnableTask<ScriptOutput>, NamespaceFilesInterface, InputFilesInterface, OutputFilesInterface {
    private static final String DEFAULT_IMAGE = "ollama/ollama";
    private static final String OLLAMA_CONTAINER_MODELS_PATH = "/root/.ollama";

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
    private Property<String> containerImage = Property.ofValue(DEFAULT_IMAGE);

    private NamespaceFiles namespaceFiles;
    private Object inputFiles;
    private Property<List<String>> outputFiles;

    @Schema(
        title = "Enable model caching by persisting models between executions.",
        description = "If true, the plugin will automatically create a Docker volume mount to persist the ~/.ollama directory. "
    )
    @Builder.Default
    private Property<Boolean> enableModelCaching = Property.ofValue(true);

    @Schema(
        title = "Path for model caching.",
        description = "If not set, caching uses a persistent named Docker volume (`kestra-ollama-cache`). " +
            "If set, this task will mount the specified host path."
    )
    private Property<String> modelCachePath;

    @Schema(
        title = "The URL of a remote Ollama server.",
        description = "Connects to a persistent Ollama server instead of starting a new one."
    )
    private Property<String> host;

    @Override
    public ScriptOutput run(RunContext runContext) throws Exception {
        var renderedOutputFiles = runContext.render(this.outputFiles).asList(String.class);

        Map<String, String> envs = getEnv(runContext);

        TaskRunner<?> configuredTaskRunner = configureTaskRunner(runContext);

        var originalCommands = runContext.render(this.commands).asList(String.class);

        var needSync = !renderedOutputFiles.isEmpty();
        var hasCleanupCommand = originalCommands.stream().anyMatch(cmd -> cmd.contains("ollama rm"));

        if (needSync && hasCleanupCommand) {
            originalCommands = originalCommands.stream()
                .flatMap(cmd -> cmd.contains("ollama rm") ? Stream.of("sync", cmd) : Stream.of(cmd))
                .toList();

        }

        CommandsWrapper commands = new CommandsWrapper(runContext)
            .withTaskRunner(configuredTaskRunner)
            .withContainerImage(runContext.render(this.containerImage).as(String.class).orElse(DEFAULT_IMAGE))
            .withInterpreter(Property.ofValue(List.of("/bin/sh", "-c")))
            .withBeforeCommands(
                host == null
                    ? Property.ofValue(List.of("ollama serve & sleep 5"))
                    : null
            )
            .withCommands(Property.ofValue(originalCommands))
            .withEnv(envs)
            .withNamespaceFiles(namespaceFiles)
            .withInputFiles(inputFiles)
            .withOutputFiles(renderedOutputFiles.isEmpty() ? null : renderedOutputFiles);

        return commands.run();
    }

    private TaskRunner<?> configureTaskRunner(RunContext runContext) throws Exception {
        if (this.host != null) {
            return this.taskRunner;
        }

        if (runContext.render(this.enableModelCaching).as(Boolean.class).orElse(true)) {
            String volumeSpec;
            if (this.modelCachePath != null) {
                String hostPath = runContext.render(this.modelCachePath).as(String.class).orElseThrow();
                volumeSpec = hostPath + ":" + OLLAMA_CONTAINER_MODELS_PATH;
                runContext.logger().info("Using user host path for Ollama cache: {}", volumeSpec);
            } else {
                String volumeName = "kestra-ollama-cache";
                volumeSpec = volumeName + ":" + OLLAMA_CONTAINER_MODELS_PATH;
                runContext.logger().info("Using named Docker volume for Ollama cache: {}", volumeSpec);
            }

            if (this.taskRunner instanceof Docker dockerRunner) {
                Docker.DockerBuilder<?, ?> builder = dockerRunner.toBuilder();
                List<String> existingVolumes = dockerRunner.getVolumes() != null ?
                    dockerRunner.getVolumes() : new ArrayList<>();

                existingVolumes.add(volumeSpec);
                builder.volumes(existingVolumes);
                return builder.build();
            }
        }
        return this.taskRunner;
    }

    private Map<String, String> getEnv(RunContext runContext) throws IllegalVariableEvaluationException {
        Map<String, String> envs = new HashMap<>();

        var renderedEnv = runContext.render(this.env).asMap(String.class, String.class);
        if (!renderedEnv.isEmpty()) {
            envs.putAll(renderedEnv);
        }

        String renderedHost = this.host != null ? runContext.render(this.host).as(String.class).orElse(null) : null;
        if (renderedHost != null) {
            envs.put("OLLAMA_HOST", renderedHost);
        }

        return envs;
    }
}