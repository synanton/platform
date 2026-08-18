package org.synanton.synt.api.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.synanton.synt.domain.model.EntityType;
import org.synanton.synt.domain.model.RelationType;
import org.synanton.synt.domain.service.OntologyService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("${syntology.mcp.server.path:/mcp}")
public class McpController {

    private final OntologyService ontologyService;
    private final ObjectMapper objectMapper;

    public McpController(OntologyService ontologyService, ObjectMapper objectMapper) {
        this.ontologyService = ontologyService;
        this.objectMapper = objectMapper;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> info() {
        return Map.of(
                "name", "syntology",
                "protocol", "streamable-http",
                "tools", List.of("syntology.list_ontology", "syntology.get_entity", "syntology.create_entity")
        );
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonNode handle(@RequestBody JsonNode request) {
        String method = request.path("method").asText();
        JsonNode params = request.path("params");
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (request.has("id")) {
            response.set("id", request.get("id"));
        }
        try {
            ObjectNode result = switch (method) {
                case "tools/list" -> listTools();
                case "tools/call" -> callTool(params);
                default -> throw new IllegalArgumentException("Unknown method: " + method);
            };
            response.set("result", result);
        } catch (Exception ex) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("code", -32000);
            error.put("message", ex.getMessage());
            response.set("error", error);
        }
        return response;
    }

    private ObjectNode listTools() {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        tools.add(toolDescriptor("syntology.list_ontology", "List entities and relations for a version"));
        tools.add(toolDescriptor("syntology.get_entity", "Get entity details by label"));
        tools.add(toolDescriptor("syntology.create_entity", "Create a new entity in the active version"));
        return result;
    }

    private ObjectNode toolDescriptor(String name, String description) {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        return tool;
    }

    private ObjectNode callTool(JsonNode params) {
        String name = params.path("name").asText();
        JsonNode arguments = params.path("arguments");
        String version = arguments.path("version").asText("active");
        ObjectNode result = objectMapper.createObjectNode();
        ObjectNode content = objectMapper.createObjectNode();
        switch (name) {
            case "syntology.list_ontology" -> {
                List<EntityType> entities = ontologyService.listEntities(version);
                List<RelationType> relations = ontologyService.listRelations(version);
                content.set("entities", objectMapper.valueToTree(entities));
                content.set("relations", objectMapper.valueToTree(relations));
            }
            case "syntology.get_entity" -> {
                EntityType entity = ontologyService.resolveEntity(arguments.path("label").asText(), version);
                content.set("entity", objectMapper.valueToTree(entity));
            }
            case "syntology.create_entity" -> {
                EntityType entity = ontologyService.createEntity(
                        arguments.path("label").asText(),
                        arguments.path("superType").asText(null)
                );
                content.put("status", "created");
                content.set("entity", objectMapper.valueToTree(entity));
            }
            default -> throw new IllegalArgumentException("Unknown tool: " + name);
        }
        result.set("content", objectMapper.createArrayNode().add(content));
        return result;
    }
}
