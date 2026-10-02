package io.kestra.plugin.ollama.cli;

import java.util.List;
import java.util.Map;

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
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class OllamaCLITaskRunnerTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldEnableVolumesOnRunnerWhenModelCachingIsEnabled() throws Exception {
        OllamaCLI task = OllamaCLI.builder()
            .id("ollama")
            .type(OllamaCLI.class.getName())
            .commands(Property.ofValue(List.of("ollama list")))
            .enableModelCaching(Property.ofValue(true))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        OllamaDocker runner = (OllamaDocker) task.configureTaskRunner(runContext);

        assertThat(runContext.render(runner.getVolumeEnabled()).as(Boolean.class).orElseThrow(), is(true));
        assertThat(
            runner.getVolumes(),
            contains("kestra-ollama-cache:/root/.ollama")
        );
    }

    @Test
    void shouldPreferTaskVolumeEnabledOverModelCachingDefault() throws Exception {
        OllamaCLI task = OllamaCLI.builder()
            .id("ollama")
            .type(OllamaCLI.class.getName())
            .commands(Property.ofValue(List.of("ollama list")))
            .enableModelCaching(Property.ofValue(true))
            .volumeEnabled(Property.ofValue(false))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        OllamaDocker runner = (OllamaDocker) task.configureTaskRunner(runContext);

        assertThat(runContext.render(runner.getVolumeEnabled()).as(Boolean.class).orElseThrow(), is(false));
    }

    @Test
    void shouldKeepTaskRunnerVolumeEnabledWhenSetOnOllamaDocker() throws Exception {
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
        OllamaDocker runner = (OllamaDocker) task.configureTaskRunner(runContext);

        assertThat(runContext.render(runner.getVolumeEnabled()).as(Boolean.class).orElseThrow(), is(true));
    }

    @Test
    void shouldRejectBlankModelCachePath() {
        OllamaCLI task = OllamaCLI.builder()
            .id("ollama")
            .type(OllamaCLI.class.getName())
            .commands(Property.ofValue(List.of("ollama list")))
            .modelCachePath(Property.ofValue("   "))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> task.configureTaskRunner(runContext));
        assertThat(error.getMessage(), is("modelCachePath must not be blank"));
    }

    @Test
    void shouldMergeVolumeEnabledIntoRunnerPluginConfiguration() throws Exception {
        OllamaCLI task = OllamaCLI.builder()
            .id("ollama")
            .type(OllamaCLI.class.getName())
            .commands(Property.ofValue(List.of("ollama list")))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        OllamaDocker runner = OllamaDocker.builder()
            .type(OllamaDocker.class.getName())
            .volumeEnabled(Property.ofValue(true))
            .build();

        RunContext runnerContext = runner.withVolumeEnabled(runContext.cloneForPlugin(runner));

        assertThat(runnerContext.pluginConfiguration("volume-enabled").orElseThrow(), is(true));
        assertThat(runnerContext, notNullValue());
    }
}
