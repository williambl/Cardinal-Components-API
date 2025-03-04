/*
 * Cardinal-Components-API
 * Copyright (C) 2019-2024 Ladysnake
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.ladysnake.cca.internal.base;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.internal.base.asm.StaticComponentLoadingException;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.Reader;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Properties;

public final class ComponentsInternals {
    public static final Logger LOGGER = LogManager.getLogger("Cardinal Components API");
    private static final int DEFAULT_MAX_WARNINGS_PER_COMPONENT = 5;
    private static final boolean DEFAULT_LOG_DESERIALIZATION_WARNINGS = true;
    private static final int configVersion = 2;
    private static boolean logDeserializationWarnings = DEFAULT_LOG_DESERIALIZATION_WARNINGS;
    private static int maxWarningsPerComponent = DEFAULT_MAX_WARNINGS_PER_COMPONENT;
    private static final Object2IntMap<String> warningCounts = new Object2IntOpenHashMap<>();

    public static void init() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("cardinal-components-api.properties");
        try(Reader reader = Files.newBufferedReader(path)) {
            Properties cfg = new Properties();
            cfg.load(reader);
            if (Integer.parseInt(cfg.getProperty("config-version")) < configVersion) {
                writeConfigFile(path);
                cfg.clear();
            }
            logDeserializationWarnings = Boolean.parseBoolean(cfg.getProperty("log-deserialization-warnings", String.valueOf(DEFAULT_LOG_DESERIALIZATION_WARNINGS)));
            maxWarningsPerComponent = Integer.parseInt(cfg.getProperty("max-deserialization-warnings", String.valueOf(DEFAULT_MAX_WARNINGS_PER_COMPONENT)));
        } catch (IOException e) {
            writeConfigFile(path);
        }
    }

    private static void writeConfigFile(Path path) {
        try {
            Files.writeString(path, """
                # If set to false, warnings will not get logged when a component fails to be resolved (typically due to mods being removed)
                # Default value: %1$s
                log-deserialization-warnings = %1$s
                # If log-deserialization-warnings is enabled, warnings will be printed at most *this number of times* for every component type
                # Default value: %2$d
                max-deserialization-warnings = %2$d

                # Internal value, do not edit or your changes may be arbitrarily reset
                config-version = %3$d
                """.formatted(DEFAULT_LOG_DESERIALIZATION_WARNINGS, DEFAULT_MAX_WARNINGS_PER_COMPONENT, configVersion));
        } catch (IOException ex) {
            LOGGER.error("Failed to write config file at {}", path);
        }
    }

    @SuppressWarnings("unchecked") @Nonnull
    public static <R> R createFactory(Class<R> factoryClass) {
        try {
            return (R) MethodHandles.lookup().findConstructor(factoryClass, MethodType.methodType(void.class)).invoke();
        } catch (Throwable e) {
            throw new StaticComponentLoadingException("Failed to instantiate generated component factory", e);
        }
    }

    public static void logDeserializationWarnings(Collection<String> missedKeyIds) {
        if (logDeserializationWarnings) {
            for (String missedKeyId : missedKeyIds) {
                int warningCount = warningCounts.getInt(missedKeyId);
                if (warningCount < maxWarningsPerComponent) {
                    Identifier id = Identifier.tryParse(missedKeyId);
                    String cause;
                    if (id == null) cause = "invalid identifier";
                    else if (ComponentRegistry.get(id) == null) cause = "unregistered key";
                    else cause = "provider does not have ";
                    LOGGER.warn("Failed to deserialize component: {} {}{}", cause, missedKeyId, warningCount + 1 >= maxWarningsPerComponent ? " (last warning for this component)" : "");
                    warningCounts.put(missedKeyId, warningCount + 1);
                }
            }
        }
    }

    public static @NotNull String getClientOptionalModAdvice() {
        return FabricLoader.getInstance().isDevelopmentEnvironment() ? "\n§eDEV ADVICE: If your mod is supposed to be client-optional, try overriding isRequiredOnClient() in your component." : "";
    }
}
