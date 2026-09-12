package com.enterprise.projectservice.service;

import com.enterprise.projectservice.dto.ProjectRequest;
import com.enterprise.projectservice.dto.ProjectResponse;
import com.enterprise.projectservice.exception.ResourceNotFoundException;
import com.enterprise.projectservice.model.Project;
import com.enterprise.projectservice.repository.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    private final ProjectRepository repository;

    public ProjectService(ProjectRepository repository) {
        this.repository = repository;
    }

    public List<ProjectResponse> findAll() {
        log.info("Fetching all projects");
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    public ProjectResponse findById(Long id) {
        log.info("Fetching project id={}", id);
        Project project = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + id));
        return toResponse(project);
    }

    public ProjectResponse create(ProjectRequest request) {
        Project project = new Project(null, request.getName(), request.getDescription(), request.getOwner());
        Project saved = repository.save(project);
        log.info("Created project id={}", saved.getId());
        return toResponse(saved);
    }

    public ProjectResponse update(Long id, ProjectRequest request) {
        Project project = new Project(id, request.getName(), request.getDescription(), request.getOwner());
        Project saved = repository.updateIfExists(id, project)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found with id: " + id));
        log.info("Updated project id={}", saved.getId());
        return toResponse(saved);
    }

    public void delete(Long id) {
        if (!repository.deleteIfExists(id)) {
            throw new ResourceNotFoundException("Project not found with id: " + id);
        }
        log.info("Deleted project id={}", id);
    }

    private ProjectResponse toResponse(Project project) {
        return new ProjectResponse(project.getId(), project.getName(), project.getDescription(), project.getOwner());
    }
}
