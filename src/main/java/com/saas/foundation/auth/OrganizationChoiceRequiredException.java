package com.saas.foundation.auth;

import java.util.List;

/**
 * The password matched an account that belongs to more than one active organization.
 * No session is issued until the caller chooses one.
 */
public class OrganizationChoiceRequiredException extends RuntimeException {

    private final List<LoginOrganization> organizations;

    public OrganizationChoiceRequiredException(List<LoginOrganization> organizations) {
        super("Choose an organization");
        this.organizations = List.copyOf(organizations);
    }

    public List<LoginOrganization> getOrganizations() {
        return organizations;
    }
}
