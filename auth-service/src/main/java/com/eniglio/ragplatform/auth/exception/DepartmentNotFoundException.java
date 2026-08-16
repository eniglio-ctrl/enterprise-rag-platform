package com.eniglio.ragplatform.auth.exception;

public class DepartmentNotFoundException extends RuntimeException {

    public DepartmentNotFoundException(String name) {
        super("No department named \"" + name + "\" exists in this tenant");
    }
}
