package com.eniglio.ragplatform.auth.exception;

public class DepartmentAlreadyExistsException extends RuntimeException {

    public DepartmentAlreadyExistsException(String name) {
        super("A department named \"" + name + "\" already exists in this tenant");
    }
}
