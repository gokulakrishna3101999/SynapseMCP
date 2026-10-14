-- ============================================================================
-- V2: audit_logs RLS + RLS-safe API key authentication
-- ============================================================================

-- audit_logs follows the same tenant isolation rule as the other tenant tables.
ALTER TABLE audit_logs ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_audit_logs ON audit_logs
    FOR ALL
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

-- The MCP filter must resolve an API key hash to a tenant BEFORE any tenant is known,
-- which RLS on api_keys would otherwise block. This SECURITY DEFINER function (owned by the
-- migration role, which bypasses RLS as table owner) is the single, narrow exemption:
-- it matches on the SHA-256 hash only, rejects revoked/expired keys and non-ACTIVE tenants,
-- and stamps last_used_at atomically.
CREATE FUNCTION authenticate_api_key(p_key_hash VARCHAR)
RETURNS TABLE (out_key_id UUID, out_tenant_id UUID, out_user_id UUID)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    UPDATE api_keys k
       SET last_used_at = NOW()
     WHERE k.key_hash = p_key_hash
       AND NOT k.revoked
       AND (k.expires_at IS NULL OR k.expires_at > NOW())
       AND EXISTS (SELECT 1 FROM tenants t WHERE t.id = k.tenant_id AND t.status = 'ACTIVE')
    RETURNING k.id, k.tenant_id, k.user_id;
$$;
