package dev.langchain4j.agent.tool;

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * If a {@link Tool} method parameter is annotated with this annotation,
 * memory id (parameter annotated with @MemoryId in AI Service) will be injected automatically.
 */
@Retention(RUNTIME)
@Target(PARAMETER)
public @interface ToolMemoryId {
}
