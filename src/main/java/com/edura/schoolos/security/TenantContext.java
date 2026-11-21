package com.edura.schoolos.security;

import java.util.UUID;

public final class TenantContext {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();
    private TenantContext() {}
    public static void set(UUID tenantId) { CURRENT.set(tenantId); }
    public static UUID require() { UUID id = CURRENT.get(); if (id == null) throw new IllegalStateException("Tenant context is missing"); return id; }
    public static void clear() { CURRENT.remove(); }
}
