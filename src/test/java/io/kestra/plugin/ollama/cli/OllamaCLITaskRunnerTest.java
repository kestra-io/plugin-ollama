package io.kestra.plugin.ollama.cli;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.TestsUtils;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class OllamaCLITaskRunnerTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldApplyCacheVolumesWhenRunWithModelCaching() throws Exception {
        OllamaCLI task = OllamaCLI.builder()
            .id("ollama")
            .type(OllamaCLI.class.getName())
            .commands(Property.ofValue(List.of("ollama list")))
            .enableModelCaching(Property.ofValue(true))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        assertThrows(Exception.class, () -> task.run(runContext));

        OllamaDocker runner = (OllamaDocker) task.lastConfiguredTaskRunner();
        assertThat(runContext.render(runner.getVolumeEnabled()).as(Boolean.class).orElse(null), is(true));
        assertThat(runner.getVolumes(), contains("kestra-ollama-cache:/root/.ollama"));
    }

    @Test
    void shouldPreferTaskVolumeEnabledWhenRun() throws Exception {
        OllamaCLI task = OllamaCLI.builder()
            .id("ollama")
            .type(OllamaCLI.class.getName())
            .commands(Property.ofValue(List.of("ollama list")))
            .enableModelCaching(Property.ofValue(true))
            .volumeEnabled(Property.ofValue(false))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        assertThrows(Exception.class, () -> task.run(runContext));

        OllamaDocker runner = (OllamaDocker) task.lastConfiguredTaskRunner();
        assertThat(runContext.render(runner.getVolumeEnabled()).as(Boolean.class).orElse(null), is(false));
    }

    @Test
    void shouldKeepTaskRunnerVolumeEnabledWhenRun() throws Exception {
        OllamaCLI task = OllamaCLI.builder()
            .id("ollama")
            .type(OllamaCLI.class.getName())
            .commands(Property.ofValue(List.of("ollama list")))
            .taskRunner(
                OllamaDocker.builder()
                    .type(OllamaDocker.class.getName())
                    .volumeEnabled(Property.ofValue(true))
                    .build()
            )
            .enableModelCaching(Property.ofValue(false))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        assertThrows(Exception.class, () -> task.run(runContext));

        OllamaDocker runner = (OllamaDocker) task.lastConfiguredTaskRunner();
        assertThat(runContext.render(runner.getVolumeEnabled()).as(Boolean.class).orElse(null), is(true));
    }

    @Test
    void shouldRejectBlankModelCachePathOnRun() {
        OllamaCLI task = OllamaCLI.builder()
            .id("ollama")
            .type(OllamaCLI.class.getName())
            .commands(Property.ofValue(List.of("ollama list")))
            .modelCachePath(Property.ofValue("   "))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(error.getMessage(), is("modelCachePath must not be blank"));
    }

    @Test
    void shouldNotMutateParentPluginConfigurationOnRun() {
        OllamaCLI task = OllamaCLI.builder()
            .id("ollama")
            .type(OllamaCLI.class.getName())
            .commands(Property.ofValue(List.of("ollama list")))
            .enableModelCaching(Property.ofValue(true))
            .build();

        RunContext parent = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        Optional<Boolean> volumeEnabledBefore = parent.pluginConfiguration("volume-enabled");
        int configurationMapIdentity = System.identityHashCode(parent.pluginConfigurations());

        assertThrows(Exception.class, () -> task.run(parent));

        assertThat(System.identityHashCode(parent.pluginConfigurations()), is(configurationMapIdentity));
        assertThat(parent.pluginConfiguration("volume-enabled"), is(volumeEnabledBefore));
    }
}
