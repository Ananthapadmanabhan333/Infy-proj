package com.nexus360.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A small JSON Schema (draft 2020-12 subset) validator for agent outputs: {@code type} (one or a list), {@code properties},
 * {@code required}, {@code additionalProperties: false}, {@code items}, {@code enum}, {@code minimum}/{@code maximum},
 * {@code minLength}/{@code maxLength}, {@code minItems}/{@code maxItems}. Schemas are plain maps so they can be stored with the agent
 * version and shown to the model and the UI.
 */
public final class JsonSchemaLite {

    private JsonSchemaLite() {}

    public static List<String> validate(Map<String, Object> schema, JsonNode node) {
        List<String> errors = new ArrayList<>();
        check(schema, node, "$", errors);
        return errors;
    }

    @SuppressWarnings("unchecked")
    private static void check(Map<String, Object> schema, JsonNode node, String path, List<String> errors) {
        if (errors.size() > 20) {
            return;
        }
        Object type = schema.get("type");
        if (type != null) {
            List<String> types = type instanceof List<?> l ? (List<String>) l : List.of((String) type);
            if (types.stream().noneMatch(t -> is(t, node))) {
                errors.add(path + ": expected " + String.join("|", types));
                return;
            }
        }
        if (node == null || node.isNull()) {
            return;
        }
        if (schema.get("enum") instanceof List<?> values && values.stream().noneMatch(v -> v != null && v.toString().equals(node.asText()))) {
            errors.add(path + ": must be one of " + values);
        }
        if (node.isNumber()) {
            if (schema.get("minimum") instanceof Number min && node.asDouble() < min.doubleValue()) {
                errors.add(path + ": must be ≥ " + min);
            }
            if (schema.get("maximum") instanceof Number max && node.asDouble() > max.doubleValue()) {
                errors.add(path + ": must be ≤ " + max);
            }
        }
        if (node.isTextual()) {
            if (schema.get("minLength") instanceof Number min && node.asText().strip().length() < min.intValue()) {
                errors.add(path + ": shorter than " + min);
            }
            if (schema.get("maxLength") instanceof Number max && node.asText().length() > max.intValue()) {
                errors.add(path + ": longer than " + max);
            }
        }
        if (node.isArray()) {
            if (schema.get("minItems") instanceof Number min && node.size() < min.intValue()) {
                errors.add(path + ": fewer than " + min + " items");
            }
            if (schema.get("maxItems") instanceof Number max && node.size() > max.intValue()) {
                errors.add(path + ": more than " + max + " items");
            }
            if (schema.get("items") instanceof Map<?, ?> items) {
                for (int i = 0; i < node.size(); i++) {
                    check((Map<String, Object>) items, node.get(i), path + "[" + i + "]", errors);
                }
            }
        }
        if (node.isObject()) {
            Map<String, Object> props = schema.get("properties") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
            if (schema.get("required") instanceof List<?> required) {
                for (Object r : required) {
                    if (!node.has(r.toString()) || (node.get(r.toString()).isNull() && !nullable(props.get(r.toString())))) {
                        errors.add(path + "." + r + ": required");
                    }
                }
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                Object sub = props.get(e.getKey());
                if (sub instanceof Map<?, ?> m) {
                    check((Map<String, Object>) m, e.getValue(), path + "." + e.getKey(), errors);
                } else if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                    errors.add(path + "." + e.getKey() + ": not allowed");
                } else if (schema.get("additionalProperties") instanceof Map<?, ?> ap) {
                    check((Map<String, Object>) ap, e.getValue(), path + "." + e.getKey(), errors);
                }
            }
        }
    }

    private static boolean nullable(Object schema) {
        return schema instanceof Map<?, ?> m && m.get("type") instanceof List<?> l && l.contains("null");
    }

    private static boolean is(String type, JsonNode n) {
        return switch (type) {
            case "null" -> n == null || n.isNull();
            case "object" -> n != null && n.isObject();
            case "array" -> n != null && n.isArray();
            case "string" -> n != null && n.isTextual();
            case "integer" -> n != null && (n.isIntegralNumber() || (n.isNumber() && n.asDouble() == Math.rint(n.asDouble())));
            case "number" -> n != null && n.isNumber();
            case "boolean" -> n != null && n.isBoolean();
            default -> false;
        };
    }
}
