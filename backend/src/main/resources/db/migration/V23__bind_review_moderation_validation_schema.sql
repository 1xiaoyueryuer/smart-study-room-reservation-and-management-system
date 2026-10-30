-- V22 was already applied to the dedicated test database. Preserve its checksum;
-- freeze each trigger's owning migration schema rather than trusting caller search_path.
ALTER FUNCTION validate_review_operation_actor() SET search_path FROM CURRENT;
ALTER FUNCTION validate_review_notification_action() SET search_path FROM CURRENT;
