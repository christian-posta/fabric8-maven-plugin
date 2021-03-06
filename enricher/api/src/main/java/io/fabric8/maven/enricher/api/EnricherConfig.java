/*
 * Copyright 2016 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package io.fabric8.maven.enricher.api;

import java.util.Properties;

import io.fabric8.maven.core.config.ProcessorConfig;
import io.fabric8.maven.core.util.Configs;
import org.apache.maven.shared.utils.StringUtils;

/**
 * @author roland
 * @since 24/05/16
 */
public class EnricherConfig {

    private static final String ENRICHER_PROP_PREFIX = "fabri8.enricher";

    private final String prefix;
    private final ProcessorConfig config;
    private final Properties projectProperties;

    public EnricherConfig(Properties projectProperties, String prefix, ProcessorConfig config) {
        this.config = config != null ? config : ProcessorConfig.EMPTY;
        this.prefix = prefix;
        this.projectProperties = projectProperties;
    }

    /**
     * Get a configuration value
     *
     * @param key key to lookup. If it implements also {@link DefaultValueProvider} then use this for a default value
     * @return the defa
     */
    public String get(Configs.Key key) {
        return get(key, key.def());
    }

    /**
     * Get a config value with a default. If no value is given, as a last resort, project properties are looked up.
     *
     * @param key key part to lookup. The whole key is build up from <code>prefix + "." + key</code>. If key is null,
     *            then only the prefix is used for the lookup (this is suitable for enrichers having only one config option)
     * @param defaultVal the default value to use when the no config is set
     * @return the value looked up or the default value.
     */
    public String get(Configs.Key key, String defaultVal) {
        String keyVal = key != null ? key.name() : "";
        String fullKey = prefix + (StringUtils.isNotEmpty(keyVal) ? "." + key : "");
        String val = config.getConfig(fullKey);
        if (val == null) {
            val = projectProperties.getProperty(ENRICHER_PROP_PREFIX + "." + fullKey);
        } if (val == null) {
            val = System.getProperty(ENRICHER_PROP_PREFIX + "." + fullKey);
        }
        return val != null ? val : defaultVal;
    }

}
