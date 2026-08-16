package com.eniglio.ragplatform.auth.exception;

/** docs/adr/0060-multi-department-membership-and-approval.md: approve/reject targeting a request that isn't pending. */
public class DepartmentRequestNotFoundException extends RuntimeException {

    public DepartmentRequestNotFoundException(String userId, String departmentId) {
        super("No pending department request for user " + userId + " and department " + departmentId);
    }
}
