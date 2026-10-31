-- One login role per service (R3). Table grants are in later migrations, next to the tables.
-- Roles are cluster-wide, so creation is guarded to keep the migration re-runnable per database.
DO $$
DECLARE
    r record;
BEGIN
    FOR r IN SELECT * FROM (VALUES
            ('invoice_handler',  '${invoice_handler_password}'),
            ('management',       '${management_password}'),
            ('validator',        '${validator_password}'),
            ('integration',      '${integration_password}'),
            ('status_mapper',    '${status_mapper_password}'),
            ('webhook_handler',  '${webhook_handler_password}'),
            ('scheduler',        '${scheduler_password}'),
            ('validation_agent', '${validation_agent_password}')) AS t(name, pw)
    LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r.name) THEN
            EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L', r.name, r.pw);
        END IF;
    END LOOP;
END
$$;
