package io.kestra.plugin.ollama;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.ollama.cli.OllamaCLI;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;


@KestraTest
class OllamaCLITest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void shouldExecuteCommandsAndReturnSuccessExitCode() throws Exception {
        OllamaCLI task = OllamaCLI.builder()
            .id(OllamaCLI.class.getSimpleName())
            .type(OllamaCLI.class.getName())
            .commands(Property.ofValue(List.of(
                "ollama pull smollm:360m",
                "ollama run smollm:360m \"What is your name?\" > output.txt"
            )))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());

        var output = task.run(runContext);

        assertThat(output, notNullValue());
        assertThat(output.getExitCode(), is(0));
    }

    @Test
    void shouldCaptureOutputFiles() throws Exception {
        OllamaCLI task = OllamaCLI.builder()
            .id(OllamaCLI.class.getSimpleName())
            .type(OllamaCLI.class.getName())
            .outputFiles(Property.ofValue(List.of("output.txt")))
            .enableModelCaching(Property.ofValue(true))
            .commands(Property.ofValue(List.of(
                "ollama run smollm:360m \"What is data orchestration?\" > output.txt"
            )))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        var output = task.run(runContext);

        assertThat(output, notNullValue());
        assertThat(output.getExitCode(), is(0));
        assertThat(output.getOutputFiles().size(), is(1));
        assertThat(output.getOutputFiles().containsKey("output.txt"), is(true));

        URI outputFileUri = output.getOutputFiles().get("output.txt");
        assertThat(runContext.storage().getFile(outputFileUri), notNullValue());
    }
}
