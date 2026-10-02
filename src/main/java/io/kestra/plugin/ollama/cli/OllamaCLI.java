package io.kestra.plugin.ollama.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Run Ollama CLI commands in flows",
    description = "Executes rendered Ollama CLI commands inside the configured task runner. Starts a transient `ollama serve` when no remote host is provided and defaults to Docker-based model caching so pulls persist across runs."
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
                      - models.json
                    commands:
                      - ollama list --json > models.json
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
                        modelCachePath: /tmp/.ollama
                        outputFiles:
                          - completion.txt
                        commands:
                          - ollama run gemma3:1b "{{ inputs.prompt }}" > completion.txt
                """
        ),
        @Example(
            full = true,
            title = "Enable Docker volumes on the task runner without global plugin config",
            code = """
                id: ollama_plugin
                namespace: company.team

                inputs:
                  - id: prompt
                    type: STRING
                    defaults: Tell me a joke about AI

                tasks:
                  - id: hello
                    type: io.kestra.plugin.ollama.cli.OllamaCLI
                    taskRunner:
                      type: io.kestra.plugin.ollama.cli.OllamaDocker
                      volumeEnabled: true
                    enableModelCaching: true
                    commands:
                      - ollama run gemma3:1b "{{ inputs.prompt }}" > completion.txt
                    outputFiles:
                      - completion.txt
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
    private static final String OLLAMA_CLOUD_HOST = "https://ollama.com";

    @Schema(
        title = "Commands executed by Ollama CLI",
        description = "Rendered then executed with `/bin/sh -c` in order. Include any `sync` calls before cleanup commands if output files need to be persisted."
    )
    @NotNull
    @PluginProperty(group = "main")
    protected Property<List<String>> commands;

    @Schema(
        title = "Additional environment variables",
        description = "Optional key/value pairs merged into the process environment. When `host` is set, `OLLAMA_HOST` is injected automatically."
    )
    @PluginProperty(group = "execution")
    protected Property<Map<String, String>> env;

    @Schema(
        title = "Task runner used for execution",
        description = "Defaults to the Docker runner. Configure plugin-specific runner options (mounts, resources) as needed."
    )
    @PluginProperty(group = "execution")
    @Builder.Default
    @Valid
    private TaskRunner<?> taskRunner = OllamaDocker.instance();

    @Schema(
        title = "Container image for the runner",
        description = "Image used when running inside Docker. Defaults to `ollama/ollama`."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<String> containerImage = Property.ofValue(DEFAULT_IMAGE);

    @PluginProperty(group = "source")
    private NamespaceFiles namespaceFiles;

    @PluginProperty(group = "source")
    private Object inputFiles;

    @PluginProperty(group = "destination")
    private Property<List<String>> outputFiles;

    @Schema(
        title = "Persist Ollama model cache",
        description = "Defaults to true. When enabled with the Docker runner and no remote host, mounts the Ollama models directory so pulled models are reused. Disable to avoid reusing cached models."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private Property<Boolean> enableModelCaching = Property.ofValue(true);

    @Schema(
        title = "Host path for cached models",
        description = "Optional host path to mount for the Ollama cache when caching is enabled. If unset, uses named Docker volume `kestra-ollama-cache`."
    )
    @PluginProperty(group = "advanced")
    private Property<String> modelCachePath;

    @Schema(
        title = "Enable Docker volume mounts for this task",
        description = """
            When set, overrides the server `volume-enabled` Docker plugin configuration for this task only.
            When unset, model caching automatically enables volume mounts for the task runner; otherwise the server plugin configuration applies.
            Prefer `taskRunner.volumeEnabled` on `io.kestra.plugin.ollama.cli.OllamaDocker` when customizing the runner directly.
            """
    )
    @PluginProperty(group = "execution")
    private Property<Boolean> volumeEnabled;

    @Schema(
        title = "Remote Ollama host",
        description = "Connects to an existing Ollama server and skips starting a local `ollama serve`. Sets `OLLAMA_HOST` for commands; ensure the address is reachable from the runner."
    )
    @PluginProperty(group = "connection")
    private Property<String> host;

    @Schema(
        title = "Authentication for Ollama Cloud (Turbo)",
        description = """
            When set, the task authenticates with ollama.com.
            If no `host` is specified, it defaults to https://ollama.com.
            """
    )
    @PluginProperty(dynamic = true, group = "connection")
    private Auth auth;

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

    protected TaskRunner<?> configureTaskRunner(RunContext runContext) throws Exception {
        if (this.host != null) {
            return this.taskRunner;
        }

        if (!(this.taskRunner instanceof Docker dockerRunner)) {
            return this.taskRunner;
        }

        OllamaDocker.OllamaDockerBuilder<?, ?> builder = OllamaDocker.builderFrom(dockerRunner);
        applyVolumeEnabled(runContext, builder);

        if (runContext.render(this.enableModelCaching).as(Boolean.class).orElse(true)) {
            String volumeSpec;
            if (this.modelCachePath != null) {
                String hostPath = runContext.render(this.modelCachePath).as(String.class).orElseThrow();
                if (hostPath.isBlank()) {
                    throw new IllegalArgumentException("modelCachePath must not be blank");
                }
                volumeSpec = hostPath + ":" + OLLAMA_CONTAINER_MODELS_PATH;
                runContext.logger().info("Using user host path for Ollama cache: {}", volumeSpec);
            } else {
                String volumeName = "kestra-ollama-cache";
                volumeSpec = volumeName + ":" + OLLAMA_CONTAINER_MODELS_PATH;
                runContext.logger().info("Using named Docker volume for Ollama cache: {}", volumeSpec);
            }

            List<String> existingVolumes = dockerRunner.getVolumes() != null ? new ArrayList<>(dockerRunner.getVolumes()) : new ArrayList<>();
            existingVolumes.add(volumeSpec);
            builder.volumes(existingVolumes);
        }

        return builder.build();
    }

    private void applyVolumeEnabled(RunContext runContext, OllamaDocker.OllamaDockerBuilder<?, ?> builder) throws Exception {
        if (this.volumeEnabled != null) {
            builder.volumeEnabled(this.volumeEnabled);
            return;
        }
        if (this.taskRunner instanceof OllamaDocker ollamaDocker && ollamaDocker.getVolumeEnabled() != null) {
            return;
        }
        if (runContext.render(this.enableModelCaching).as(Boolean.class).orElse(true)) {
            builder.volumeEnabled(Property.ofValue(true));
        }
    }

    public Map<String, String> getEnv(RunContext runContext) throws IllegalVariableEvaluationException {
        Map<String, String> envs = new HashMap<>();

        var renderedEnv = runContext.render(this.env).asMap(String.class, String.class);
        if (!renderedEnv.isEmpty()) {
            envs.putAll(renderedEnv);
        }

        String renderedHost = this.host != null ? runContext.render(this.host).as(String.class).orElse(null) : null;
        if (renderedHost != null) {
            envs.put("OLLAMA_HOST", renderedHost);
        }

        if (this.auth != null && this.auth.getApiKey() != null) {
            envs.put("OLLAMA_API_KEY", runContext.render(this.auth.getApiKey()).as(String.class).orElseThrow());
            envs.putIfAbsent("OLLAMA_HOST", OLLAMA_CLOUD_HOST);
        }

        return envs;
    }

    @Builder
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(
        title = "Ollama Cloud authentication configuration"
    )
    public static class Auth {
        @Schema(
            title = "API Key",
            description = "API key used to access Ollama cloud models."
        )
        @PluginProperty(secret = true)
        private Property<String> apiKey;
    }
}
