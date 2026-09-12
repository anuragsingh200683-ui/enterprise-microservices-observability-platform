package com.enterprise.projectservice.dto;

import jakarta.validation.constraints.NotBlank;

public class ProjectRequest {

    @NotBlank(message = "name must not be blank")
    private String name;

    @NotBlank(message = "description must not be blank")
    private String description;

    @NotBlank(message = "owner must not be blank")
    private String owner;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }
}
