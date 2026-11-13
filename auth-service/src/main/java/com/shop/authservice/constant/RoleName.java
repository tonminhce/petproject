package com.shop.authservice.constant;

/**
 * The role catalogue of the platform.
 *
 * <p>F-056: the names are exactly the realm roles of the provisioned Keycloak
 * realm (docker/keycloak/import/ecommerce-realm.json): ADMIN, USER, MANAGER and
 * SERVICE. The catalogue used to carry a local-only {@code PM} that the realm
 * does not know - granting it changed nothing at runtime - and it lacked
 * MANAGER and SERVICE, so the admin role API answered 400 for the very roles
 * the platform relies on. {@code RoleCatalogueContractTest} fails when the
 * realm and this enum drift apart.</p>
 */
public enum RoleName {
    USER,
    ADMIN,
    MANAGER,
    SERVICE
}
