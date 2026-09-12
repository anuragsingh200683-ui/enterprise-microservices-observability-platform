package com.enterprise.projectservice.repository;

import com.enterprise.projectservice.model.Project;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class ProjectRepository {

    private final Map<Long, Project> store = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(0);

    public ProjectRepository() {
        save(new Project(null, "Observability Platform", "Enterprise metrics and tracing rollout", "Alice Johnson"));
        save(new Project(null, "Payments Migration", "Migrate legacy payments to microservices", "Bob Smith"));
        save(new Project(null, "Customer Portal Revamp", "Redesign the self-service customer portal", "Carol Davis"));
    }

    public Collection<Project> findAll() {
        return store.values();
    }

    public Optional<Project> findById(Long id) {
        return Optional.ofNullable(store.get(id));
    }

    public Project save(Project project) {
        if (project.getId() == null) {
            project.setId(idSequence.incrementAndGet());
        }
        store.put(project.getId(), project);
        return project;
    }

    public boolean existsById(Long id) {
        return store.containsKey(id);
    }

    public void deleteById(Long id) {
        store.remove(id);
    }
}
