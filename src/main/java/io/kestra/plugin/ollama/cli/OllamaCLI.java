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
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.runners.TaskRunner;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.scripts.exec.AbstractExecScript;
import io.kestra.plugin.scripts.exec.scripts.models.DockerOptions;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;
import io.kestra.plugin.scripts.runner.docker.Docker;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
// callSuper = false: the inherited env has no @ToString.Exclude, keeps rendered secrets out
@ToString(callSuper = false)
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Run Ollama CLI commands in flows",
    description = """
        Executes rendered Ollama CLI commands inside the configured task runner. Starts a transient `ollama serve` when no remote host is provided, before any `beforeCommands`, and can mount a Docker volume for model caching when the server allows Docker volume mounts.
        The inherited `warningOnStdErr` and `runner` properties are ignored, and so is `docker` on Kestra 1.3. Use `taskRunner`.
        """
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
public class OllamaCLI extends AbstractExecScript implements RunnableTask<ScriptOutput> {
    private static final String DEFAULT_IMAGE = "ollama/ollama";
    private static final String OLLAMA_CONTAINER_MODELS_PATH = "/root/.ollama";
    private static final String OLLAMA_CLOUD_HOST = "https://ollama.com";
    static final String VOLUME_ENABLED_CONFIG = "volume-enabled";

    @Schema(
        title = "Commands executed by Ollama CLI",
        description = """
            Rendered then executed through the `interpreter` (`/bin/sh -c` by default) in order.
            Include any `sync` calls before cleanup commands if output files need to be persisted.
            """
    )
    @NotNull
    @PluginProperty(group = "main")
    protected Property<List<String>> commands;

    @Schema(
        title = "Task runner used for execution",
        description = """
            Defaults to the stock `io.kestra.plugin.scripts.runner.docker.Docker` runner, which has no task-level `volume-enabled` property; enable Docker volume mounts in Kestra plugin configuration instead.
            Set `io.kestra.plugin.ollama.cli.OllamaDocker` to apply an Ollama-only plugin configuration.
            """
    )
    @PluginProperty(group = "execution")
    @Builder.Default
    @Valid
    private TaskRunner<?> taskRunner = Docker.instance();

    @Schema(
        title = "Container image for the runner",
        description = "Image used when running inside Docker. Defaults to `ollama/ollama`."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<String> containerImage = Property.ofValue(DEFAULT_IMAGE);

    @Schema(
        title = "Fail fast on the first failing command",
        description = """
            If true, `set -e` is prepended to `beforeCommands`, so the first failing line stops the run.
            Defaults to false, so a failing line does not stop the run.
            """
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    protected Property<Boolean> failFast = Property.ofValue(false);

    @Schema(
        title = "Persist Ollama model cache",
        description = """
            Defaults to true for local runs when unset. When enabled, adds a Docker volume mount for the Ollama models directory only if the server already allows Docker volume mounts (`volume-enabled` in plugin configuration).
            Ignored when `host` is set.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<Boolean> enableModelCaching;

    @Schema(
        title = "Host path for cached models",
        description = """
            Optional host path to mount for the Ollama cache when caching is enabled and Docker volume mounts are allowed server-side.
            If unset, uses named Docker volume `kestra-ollama-cache`. Ignored when `host` is set.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<String> modelCachePath;

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
        var rOutputFiles = runContext.render(this.getOutputFiles()).asList(String.class);

        TaskRunner<?> configuredTaskRunner = configureTaskRunner(runContext);

        var originalCommands = runContext.render(this.commands).asList(String.class);

        var needSync = !rOutputFiles.isEmpty();
        var hasCleanupCommand = originalCommands.stream().anyMatch(cmd -> cmd.contains("ollama rm"));

        if (needSync && hasCleanupCommand) {
            originalCommands = originalCommands.stream()
                .flatMap(cmd -> cmd.contains("ollama rm") ? Stream.of("sync", cmd) : Stream.of(cmd))
                .toList();

        }

        var wrapper = this.commands(runContext).withTaskRunner(configuredTaskRunner);

        var beforeCommands = new ArrayList<String>();
        if (host == null) {
            beforeCommands.add("ollama serve & sleep 5");
        }
        // the wrapper renders with the runner variables (workingDir, checkpoints), a plain render would not
        beforeCommands.addAll(wrapper.renderCommands(runContext, this.getBeforeCommands()));

        // getEnv adds OLLAMA_HOST and the API key to the user env, replaces the map commands() set (immutable for Property.ofValue)
        return wrapper
            .withInterpreter(this.getInterpreter())
            .withBeforeCommands(beforeCommands.isEmpty() ? null : Property.ofValue(beforeCommands))
            .withBeforeCommandsWithOptions(true)
            .withCommands(Property.ofValue(originalCommands))
            .withEnv(getEnv(runContext))
            .run();
    }

    // The task never had a docker property: drop the 1.3 parent one so taskRunner and the cache volume always apply
    @Override
    protected DockerOptions injectDefaults(RunContext runContext, DockerOptions original) {
        return null;
    }

    protected TaskRunner<?> configureTaskRunner(RunContext runContext) throws Exception {
        if (this.host != null) {
            if (this.modelCachePath != null || runContext.render(this.enableModelCaching).as(Boolean.class).orElse(false)) {
                runContext.logger().warn("Model caching options are ignored when `host` is set.");
            }
            return this.taskRunner;
        }

        if (!(this.taskRunner instanceof Docker dockerRunner)) {
            return this.taskRunner;
        }

        var cachingEnabled = runContext.render(this.enableModelCaching).as(Boolean.class).orElse(true);
        if (!cachingEnabled) {
            return dockerRunner;
        }

        if (!dockerVolumesAllowed(runContext, dockerRunner)) {
            runContext.logger().warn(
                "Model caching is enabled but Docker volume mounts are disabled in plugin configuration ({}). "
                    + "Pulled models will not persist between runs.",
                VOLUME_ENABLED_CONFIG
            );
            return dockerRunner;
        }

        var volumeSpec = resolveModelCacheVolume(runContext);
        var existingVolumes = dockerRunner.getVolumes() != null ? new ArrayList<>(dockerRunner.getVolumes()) : new ArrayList<String>();
        existingVolumes.add(volumeSpec);
        return dockerRunner.toBuilder().volumes(existingVolumes).build();
    }

    static boolean dockerVolumesAllowed(RunContext runContext, Docker dockerRunner) {
        // the runner reads `volume-enabled` from its own plugin configuration, so gate on the same one
        return runContext.cloneForPlugin(dockerRunner).<Boolean> pluginConfiguration(VOLUME_ENABLED_CONFIG).orElse(false);
    }

    private String resolveModelCacheVolume(RunContext runContext) throws IllegalVariableEvaluationException {
        if (this.modelCachePath != null) {
            var hostPath = runContext.render(this.modelCachePath).as(String.class)
                .filter(path -> !path.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("modelCachePath must not be blank"));
            var volumeSpec = hostPath + ":" + OLLAMA_CONTAINER_MODELS_PATH;
            runContext.logger().info("Using user host path for Ollama cache: {}", volumeSpec);
            return volumeSpec;
        }
        var volumeName = "kestra-ollama-cache";
        var volumeSpec = volumeName + ":" + OLLAMA_CONTAINER_MODELS_PATH;
        runContext.logger().info("Using named Docker volume for Ollama cache: {}", volumeSpec);
        return volumeSpec;
    }

    public Map<String, String> getEnv(RunContext runContext) throws IllegalVariableEvaluationException {
        Map<String, String> envs = new HashMap<>();

        var rEnv = runContext.render(this.getEnv()).asMap(String.class, String.class);
        if (!rEnv.isEmpty()) {
            envs.putAll(rEnv);
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
