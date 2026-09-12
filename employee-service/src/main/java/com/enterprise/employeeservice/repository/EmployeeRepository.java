package com.enterprise.employeeservice.repository;

import com.enterprise.employeeservice.model.Employee;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
public class EmployeeRepository {

    private final Map<Long, Employee> store = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(0);

    public EmployeeRepository() {
        save(new Employee(null, "Alice Johnson", "alice.johnson@example.com", "Engineering", 95000.0));
        save(new Employee(null, "Bob Smith", "bob.smith@example.com", "Marketing", 72000.0));
        save(new Employee(null, "Carol Davis", "carol.davis@example.com", "Finance", 88000.0));
    }

    public Collection<Employee> findAll() {
        return store.values();
    }

    public Optional<Employee> findById(Long id) {
        return Optional.ofNullable(store.get(id));
    }

    public Employee save(Employee employee) {
        if (employee.getId() == null) {
            employee.setId(idSequence.incrementAndGet());
        }
        store.put(employee.getId(), employee);
        return employee;
    }

    public Optional<Employee> updateIfExists(Long id, Employee employee) {
        return Optional.ofNullable(store.computeIfPresent(id, (key, existing) -> employee));
    }

    public boolean deleteIfExists(Long id) {
        return store.remove(id) != null;
    }
}
