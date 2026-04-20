package org.example.lab3.storage;

import org.example.lab3.model.NodeInfo;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

@Profile("naming-server")
@Component
public class NodeRegistryStorage {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path file = Paths.get("nodes.json");

    public List<NodeInfo> load() {
        try {
            if (!Files.exists(file)) {
                return List.of();
            }
            return Arrays.asList(mapper.readValue(file.toFile(), NodeInfo[].class));
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        }
    }

    public void save(Collection<NodeInfo> nodes) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), nodes);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
