package org.ladysnake.cca.api.v3.util;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that the annotated method must only be called in a specific environment.
 *
 * <p>Unlike {@link Environment}, this annotation is purely informational and does not strip bytecode.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface CheckEnvironment {
    /**
     * Returns the environment type that the annotated element requires.
     */
    EnvType value();
}
