package com.aether.gateway.providers.gemini;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Set;

/**
 * Converts an OpenAI tool's JSON Schema {@code parameters} into the Gemini
 * API's {@code Schema} object, which is a subset of OpenAPI 3.0 rather than
 * JSON Schema and rejects keywords outside it. Applied recursively through
 * {@code properties}, {@code items} and {@code anyOf}:
 *
 * <ul>
 *   <li>keywords Gemini's Schema documents are kept as-is: {@code type},
 *       {@code format}, {@code title}, {@code description},
 *       {@code nullable}, {@code enum}, {@code items}, {@code minItems},
 *       {@code maxItems}, {@code properties}, {@code required},
 *       {@code minProperties}, {@code maxProperties}, {@code minimum},
 *       {@code maximum}, {@code minLength}, {@code maxLength},
 *       {@code pattern}, {@code example}, {@code anyOf},
 *       {@code propertyOrdering}, {@code default};</li>
 *   <li>a type list such as {@code ["integer","null"]} becomes the single
 *       non-null type plus {@code nullable: true};</li>
 *   <li>{@code oneOf} becomes {@code anyOf} and {@code const} becomes a
 *       one-value {@code enum};</li>
 *   <li>everything else is dropped - notably {@code $schema},
 *       {@code additionalProperties}, {@code $ref}/{@code $defs} (so a
 *       referenced sub-schema loses its structure), {@code examples},
 *       {@code allOf} and {@code not};</li>
 *   <li>an empty {@code properties} map is dropped, and a top-level schema
 *       left with no properties is omitted altogether, because Gemini
 *       rejects an OBJECT schema whose properties are empty and a function
 *       without {@code parameters} is how it spells "no arguments".</li>
 * </ul>
 *
 * <p>Gemini's alternative {@code parametersJsonSchema} field takes JSON
 * Schema verbatim, but is newer and not accepted by every model the
 * Gemini API serves, so this adapter uses the long-standing
 * {@code parameters} field and converts.
 */
final class GeminiSchemaConversion {

    private static final JsonMapper RAW_JSON = JsonMapper.builder().build();

    private static final Set<String> SUPPORTED_AS_IS = Set.of(
            "format", "title", "description", "nullable", "enum", "minItems", "maxItems", "required",
            "minProperties", "maxProperties", "minimum", "maximum", "minLength", "maxLength", "pattern",
            "example", "propertyOrdering", "default");

    private GeminiSchemaConversion() {
    }

    /** Null when the function takes no arguments, so the declaration omits {@code parameters}. */
    static JsonNode parameters(JsonNode jsonSchema) {
        if (jsonSchema == null || !jsonSchema.isObject()) {
            return null;
        }
        ObjectNode converted = convert(jsonSchema);
        return converted.has("properties") ? converted : null;
    }

    private static ObjectNode convert(JsonNode schema) {
        ObjectNode converted = RAW_JSON.createObjectNode();
        if (!schema.isObject()) {
            return converted;
        }
        for (Map.Entry<String, JsonNode> keyword : schema.properties()) {
            String name = keyword.getKey();
            JsonNode value = keyword.getValue();
            switch (name) {
                case "type" -> convertType(value, converted);
                case "properties" -> convertProperties(value, converted);
                case "items" -> {
                    if (value.isObject()) {
                        converted.set("items", convert(value));
                    }
                }
                case "anyOf", "oneOf" -> convertAlternatives(value, converted);
                case "const" -> {
                    if (!schema.has("enum")) {
                        converted.putArray("enum").add(value.asString());
                    }
                }
                default -> {
                    if (SUPPORTED_AS_IS.contains(name)) {
                        converted.set(name, value);
                    }
                }
            }
        }
        return converted;
    }

    private static void convertType(JsonNode type, ObjectNode converted) {
        if (!type.isArray()) {
            converted.set("type", type);
            return;
        }
        boolean nullable = false;
        String single = null;
        for (JsonNode member : type) {
            if ("null".equals(member.asString())) {
                nullable = true;
            } else if (single == null) {
                single = member.asString();
            }
        }
        if (single != null) {
            converted.put("type", single);
        }
        if (nullable) {
            converted.put("nullable", true);
        }
    }

    private static void convertProperties(JsonNode properties, ObjectNode converted) {
        if (!properties.isObject() || properties.isEmpty()) {
            return;
        }
        ObjectNode convertedProperties = converted.putObject("properties");
        for (Map.Entry<String, JsonNode> property : properties.properties()) {
            convertedProperties.set(property.getKey(), convert(property.getValue()));
        }
    }

    private static void convertAlternatives(JsonNode alternatives, ObjectNode converted) {
        if (!alternatives.isArray()) {
            return;
        }
        ArrayNode anyOf = converted.has("anyOf") ? (ArrayNode) converted.get("anyOf") : converted.putArray("anyOf");
        for (JsonNode alternative : alternatives) {
            anyOf.add(convert(alternative));
        }
    }
}
