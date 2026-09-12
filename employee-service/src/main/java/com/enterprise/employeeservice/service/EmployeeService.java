package com.enterprise.employeeservice.service;

import com.enterprise.employeeservice.dto.EmployeeRequest;
import com.enterprise.employeeservice.dto.EmployeeResponse;
import com.enterprise.employeeservice.exception.ResourceNotFoundException;
import com.enterprise.employeeservice.model.Employee;
import com.enterprise.employeeservice.repository.EmployeeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EmployeeService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeService.class);

    private final EmployeeRepository repository;

    public EmployeeService(EmployeeRepository repository) {
        this.repository = repository;
    }

    public List<EmployeeResponse> findAll() {
        log.info("Fetching all employees");
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    public EmployeeResponse findById(Long id) {
        log.info("Fetching employee id={}", id);
        Employee employee = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + id));
        return toResponse(employee);
    }

    public EmployeeResponse create(EmployeeRequest request) {
        Employee employee = new Employee(null, request.getName(), request.getEmail(),
                request.getDepartment(), request.getSalary());
        Employee saved = repository.save(employee);
        log.info("Created employee id={}", saved.getId());
        return toResponse(saved);
    }

    public EmployeeResponse update(Long id, EmployeeRequest request) {
        Employee employee = new Employee(id, request.getName(), request.getEmail(),
                request.getDepartment(), request.getSalary());
        Employee saved = repository.updateIfExists(id, employee)
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + id));
        log.info("Updated employee id={}", saved.getId());
        return toResponse(saved);
    }

    public void delete(Long id) {
        if (!repository.deleteIfExists(id)) {
            throw new ResourceNotFoundException("Employee not found with id: " + id);
        }
        log.info("Deleted employee id={}", id);
    }

    private EmployeeResponse toResponse(Employee employee) {
        return new EmployeeResponse(employee.getId(), employee.getName(), employee.getEmail(),
                employee.getDepartment(), employee.getSalary());
    }
}
