-- docs/adr/0059-department-based-sharing.md: nullable, not a foreign key to
-- departments (V4) - the department name is validated against that registry in
-- UserManagementService.updateDepartment, not enforced at the database level, the
-- same "validate in the service layer" convention the visibility model already
-- follows for its own JSON-stored sharing metadata.
ALTER TABLE users ADD COLUMN department TEXT;
