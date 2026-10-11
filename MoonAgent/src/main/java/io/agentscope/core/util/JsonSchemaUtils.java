/*
 * Compatibility override for AgentScope 2.0.4.
 *
 * AgentScope was compiled against Victools 4, whose SchemaGenerator returns a Jackson 2
 * ObjectNode. Spring AI 2.0 requires Victools 5, whose SchemaGenerator returns a Jackson 3
 * node. This class keeps the AgentScope public contract while adapting the Victools 5 result
 * back to a regular Jackson 2 Map.
 */
package io.agentscope.core.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonOption;
import com.github.victools.jsonschema.module.jackson.JacksonSchemaModule;
import io.agentscope.core.tool.ToolSchemaModule;

import java.lang.reflect.Type;
import java.util.Map;

public class JsonSchemaUtils {

    private static final boolean PROPERTY_REQUIRED_BY_DEFAULT = false;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Object SCHEMA_LOCK = new Object();
    private static final SchemaGenerator SCHEMA_GENERATOR;

    static {
        JacksonSchemaModule jacksonModule =
                new JacksonSchemaModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED);
        ToolSchemaModule toolSchemaModule =
                PROPERTY_REQUIRED_BY_DEFAULT
                        ? new ToolSchemaModule()
                        : new ToolSchemaModule(
                                ToolSchemaModule.Option.PROPERTY_REQUIRED_FALSE_BY_DEFAULT);
        SchemaGeneratorConfigBuilder builder =
                new SchemaGeneratorConfigBuilder(
                        SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                        .with(jacksonModule)
                        .with(toolSchemaModule)
                        .with(Option.PLAIN_DEFINITION_KEYS)
                        .without(Option.SCHEMA_VERSION_INDICATOR);
        SchemaGeneratorConfig config = builder.build();
        SCHEMA_GENERATOR = new SchemaGenerator(config);
    }

    private JsonSchemaUtils() {
    }

    public static Map<String, Object> generateSchemaFromClass(Class<?> clazz) {
        try {
            synchronized (SCHEMA_LOCK) {
                return toMap(SCHEMA_GENERATOR.generateSchema(clazz));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate JSON schema for " + clazz.getName(), e);
        }
    }

    public static Map<String, Object> generateSchemaFromJsonNode(JsonNode schema) {
        try {
            return JSON.convertValue(schema, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate JSON schema for schema", e);
        }
    }

    public static Map<String, Object> generateSchemaFromType(Type type) {
        try {
            synchronized (SCHEMA_LOCK) {
                return toMap(SCHEMA_GENERATOR.generateSchema(type, new Type[0]));
            }
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to generate JSON schema for " + type.getTypeName(), e);
        }
    }

    public static <T> T convertToObject(Object data, Class<T> targetClass) {
        if (data == null) {
            throw new IllegalStateException("No structured data available in response");
        }
        try {
            return JsonUtils.getJsonCodec().convertValue(data, targetClass);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert metadata to " + targetClass.getName(), e);
        }
    }

    private static Map<String, Object> toMap(tools.jackson.databind.JsonNode schema) throws Exception {
        return JSON.readValue(
                schema.toString(), new TypeReference<Map<String, Object>>() {});
    }
}
