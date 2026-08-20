package org.synanton.controlplane.service;

import org.springframework.stereotype.Component;
import org.synanton.controlplane.app.ControlPlaneProperties;

import java.util.List;
import java.util.Optional;

@Component
public class ModelServingDirectory {

    private final List<ControlPlaneProperties.ModelEntry> models;

    public ModelServingDirectory(ControlPlaneProperties props) {
        this.models = props.models();
    }

    public List<ControlPlaneProperties.ModelEntry> getAll() {
        return models;
    }

    public Optional<ControlPlaneProperties.ModelEntry> getById(String modelId) {
        return models.stream()
                .filter(m -> m.modelId().equals(modelId))
                .findFirst();
    }

    public boolean isGpuBacked(String modelId) {
        return getById(modelId).map(ControlPlaneProperties.ModelEntry::isGpuBacked).orElse(false);
    }

    public List<ControlPlaneProperties.ModelEntry> getGpuModels() {
        return models.stream()
                .filter(ControlPlaneProperties.ModelEntry::isGpuBacked)
                .toList();
    }
}
