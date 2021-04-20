/*
 * Copyright (C) 2009-2018 by the geOrchestra PSC
 *
 * This file is part of geOrchestra.
 *
 * geOrchestra is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * geOrchestra is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * geOrchestra.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.georchestra.security;

import static java.util.Objects.requireNonNull;
import static org.georchestra.commons.security.SecurityHeaders.SEC_ORG;
import static org.georchestra.commons.security.SecurityHeaders.SEC_ORGNAME;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;
import javax.annotation.PostConstruct;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.apache.http.Header;
import org.apache.http.message.BasicHeader;
import org.georchestra.commons.configuration.GeorchestraConfiguration;
import org.georchestra.commons.security.SecurityHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

/**
 * Reads information from a user node in LDAP and adds the information as
 * headers to the request.
 * <p>
 * Adds the remaining standard request headers {@code sec-org} and
 * {@code sec-orgname} (while {@link SecurityRequestHeaderProvider} adds
 * {@code sec-username} and {@code sec-roles}), and then any extra request
 * header that can be extracted from the user's LDAP info and is configured in
 * the datadirectory's {@code security-proxy/headers-mapping.properties} file.
 * <p>
 * The final set of request headers sent to each specific proxified application
 * is the aggregation of default headers and service specific headers.
 * <p>
 * Header mappings that apply to a specific target service take precedence over
 * default headers.
 * <p>
 * Service name matches the ones assigned in {@code targets-mapping.properties}.
 * For example, for service {@code analytics},
 * {@code targets-mappings.properties} contains
 * {@code analytics=http://analytics:8080/analytics/}, and
 * {@code headers-mappings.properties} may contain
 * {@code analytics.sec-firstname=givenName}.
 * 
 * 
 * @author jeichar
 * @see SecurityRequestHeaderProvider
 */
public class LdapUserDetailsRequestHeaderProvider extends HeaderProvider {

    private static final String MEMBER_OF_PATTERN = "([^=,]+)=([^=,]+),%s.*";

    private static final String CACHED_USERNAME_KEY = "security-proxy-cached-username";

    private static final String CACHED_HEADERS_KEY = "security-proxy-cached-attrs";

    private final Supplier<FilterBasedLdapUserSearch> userSearchFactory;
    private final Pattern orgSearchMemberOfPattern;
    private final String orgSearchBaseDN;

    /**
     * Header mappings that apply to all services (i.e. have no service prefix in
     * header-mappings.properties), for example: ({@code sec-email=mail}
     */
    Map<String, String> defaultMappinsg = ImmutableMap.of();
    /**
     * Header mappings that apply to a specific target service, by service name,
     * where service name matches the ones assigned in
     * {@code targets-mapping.properties}. For example, for service
     * {@code analytics}, {@code targets-mappings.properties} contains
     * {@code analytics=http://analytics:8080/analytics/}, and
     * {@code headers-mappings.properties} may contain
     * {@code analytics.sec-firstname=givenName}.
     */
    Map<String, Map<String, String>> perServiceMappings = ImmutableMap.of();

    @Autowired
    @VisibleForTesting
    LdapTemplate ldapTemplate;

    @Autowired
    private GeorchestraConfiguration georchestraConfiguration;

    /**
     * @param contextSource    provider for the base LDAP path
     * @param ldapOrgsRdn      search base for user organizations (e.g.
     *                         {@code ou=orgs}
     * @param ldapUsersRdn     search base for users (e.g. {@code ou=users})
     * @param userSearchFilter single-user search filter (e.g. {@code uid={0}})
     */
    public LdapUserDetailsRequestHeaderProvider(BaseLdapPathContextSource contextSource, String ldapOrgsRdn,
            String ldapUsersRdn, String userSearchFilter) {

        requireNonNull(contextSource, "contextSource must not be null");
        requireNonNull(ldapOrgsRdn, "ldapOrgsRdn must not be null");
        requireNonNull(ldapUsersRdn, "ldapUsersRdn must not be null");
        requireNonNull(userSearchFilter, "userSearchFilter must not be null");

        this.orgSearchBaseDN = ldapOrgsRdn;
        this.userSearchFactory = () -> new FilterBasedLdapUserSearch(ldapUsersRdn, userSearchFilter, contextSource);
        this.orgSearchMemberOfPattern = Pattern.compile(String.format(MEMBER_OF_PATTERN, ldapOrgsRdn));
    }

    @VisibleForTesting
    LdapUserDetailsRequestHeaderProvider(Supplier<FilterBasedLdapUserSearch> userSearchFactory, String ldapOrgsRdn) {
        this.orgSearchBaseDN = ldapOrgsRdn;
        this.userSearchFactory = userSearchFactory;
        this.orgSearchMemberOfPattern = Pattern.compile(String.format(MEMBER_OF_PATTERN, ldapOrgsRdn));
    }

    @PostConstruct
    public void init() throws IOException {
        logger.info(String.format("Will contribute standard header %s", SEC_ORG));
        logger.info(String.format("Will contribute standard header %s", SEC_ORGNAME));
        final boolean loadExternalConfig = (georchestraConfiguration != null) && (georchestraConfiguration.activated());
        if (loadExternalConfig) {
            Properties pHmap = georchestraConfiguration.loadCustomPropertiesFile("headers-mapping");
            logger.info("Loading header mappings from "
                    + new File(georchestraConfiguration.getContextDataDir(), "headers-mapping.properties"));
            loadConfig(pHmap);

            this.defaultMappinsg.forEach((header, property) -> logger
                    .info(String.format("Loaded default header mapping %s=%s", header, property)));
            this.perServiceMappings.forEach((service, serviceMappings) -> {
                serviceMappings.forEach((header, property) -> logger
                        .info(String.format("Loaded header mapping for service %s: %s=%s", service, header, property)));
            });
        }
    }

    void loadConfig(Properties pHmap) {
        ImmutableMap<String, String> allMappings = Maps.fromProperties(pHmap);
        this.defaultMappinsg = loadDefaultMappings(allMappings);
        this.perServiceMappings = loadPerServiceMappings(allMappings);
    }

    private Map<String, String> loadDefaultMappings(ImmutableMap<String, String> mappings) {
        return mappings.entrySet().stream().filter(e -> e.getKey().indexOf('.') == -1)
                .collect(Collectors.toMap(Entry::getKey, Entry::getValue));
    }

    private Map<String, Map<String, String>> loadPerServiceMappings(ImmutableMap<String, String> mappings) {
        Map<String, Map<String, String>> serviceMappings = new HashMap<>();
        mappings.entrySet().stream().filter(e -> e.getKey().indexOf('.') > 0).forEach(e -> {
            int index = e.getKey().indexOf('.');
            String serviceName = e.getKey().substring(0, index);
            String headerName = e.getKey().substring(index + 1);
            String headerMapping = e.getValue();
            Map<String, String> serviceHeaders = serviceMappings.computeIfAbsent(serviceName, s -> new HashMap<>());
            serviceHeaders.put(headerName, headerMapping);
        });
        return serviceMappings;
    }

    @Override
    public Collection<Header> getCustomRequestHeaders(HttpSession session, HttpServletRequest originalRequest,
            String targetServiceName) {

        // Don't use this provider for trusted request
        if (isPreAuthorized(originalRequest) || isAnnonymous()) {
            return Collections.emptyList();
        }

        synchronized (session) {
            Optional<Collection<Header>> cached = getCachedHeaders(session, targetServiceName);
            return cached.orElseGet(() -> {
                Collection<Header> headers = collectHeaders(session, targetServiceName);
                setCachedHeaders(session, headers, targetServiceName);
                return headers;
            });
        }
    }

    /**
     * Actually performs the building of the request headers list
     */
    private Collection<Header> collectHeaders(HttpSession session, String serviceName) {
        final String username = getCurrentUserName();

        List<Header> headers = buildStandardOrganizationHeaders(username);

        Map<String, String> mappings = getServiceMappings(serviceName);
        List<Header> userDefinedHeaders = collectHeaderMappings(username, mappings);

        headers.addAll(userDefinedHeaders);

        return headers;
    }

    Map<String, String> getServiceMappings(String serviceName) {
        Map<String, String> mappings = getDefaultMappings();
        if (serviceName != null) {
            Map<String, String> serviceMappings = this.perServiceMappings.get(serviceName);
            if (serviceMappings != null) {
                mappings = new HashMap<>(this.defaultMappinsg);
                mappings.putAll(serviceMappings);
            }
        }
        return mappings;
    }

    Map<String, String> getDefaultMappings() {
        Map<String, String> mappings = this.defaultMappinsg;
        return mappings;
    }

    private List<Header> buildStandardOrganizationHeaders(String username) {
        // Add user organization
        final String orgCn = loadOrgCn(username);

        List<Header> headers = new ArrayList<>();
        // add sec-orgname
        if (orgCn != null) {
            headers.add(new BasicHeader(SEC_ORG, orgCn));
            try {
                String dn = "cn=" + orgCn + "," + this.orgSearchBaseDN;
                DirContextOperations ctx = this.ldapTemplate.lookupContext(dn);
                headers.add(new BasicHeader(SEC_ORGNAME, ctx.getStringAttribute("o")));
            } catch (RuntimeException ex) {
                logger.warn("Cannot find associated org with cn " + orgCn);
            }
        }
        return headers;
    }

    private String loadOrgCn(String username) {
        try {
            // Retreive memberOf attributes
            FilterBasedLdapUserSearch userSearch = userSearchFactory.get();
            userSearch.setReturningAttributes(new String[] { "memberOf" });
            DirContextOperations orgData = userSearch.searchForUser(username);
            Attribute attributes = orgData.getAttributes().get("memberOf");
            if (attributes != null) {
                NamingEnumeration<?> all = attributes.getAll();
                while (all.hasMore()) {
                    String memberOf = all.next().toString();
                    Matcher m = this.orgSearchMemberOfPattern.matcher(memberOf);
                    if (m.matches()) {
                        String orgCn = m.group(2);
                        return orgCn;
                    }
                }
            }
        } catch (javax.naming.NamingException e) {
            logger.error("problem adding headers for request: organization", e);
        }
        return null;
    }

    List<Header> collectHeaderMappings(String username, Map<String, String> mappings) {
        final List<Header> headers = new ArrayList<>();
        DirContextOperations userData;
        try {
            FilterBasedLdapUserSearch userSearch = userSearchFactory.get();
            userData = userSearch.searchForUser(username);
        } catch (Exception e) {
            logger.warn("Unable to lookup user:" + username, e);
            return Collections.emptyList();
        }
        final Attributes ldapUserAttributes = userData.getAttributes();
        mappings.forEach((headerName, ldapPropertyName) -> {
            try {
                final @Nullable String headerValue = buildValue(ldapUserAttributes, ldapPropertyName);
                headers.add(new BasicHeader(headerName, headerValue));
            } catch (javax.naming.NamingException e) {
                logger.error("problem adding headers for request:" + headerName, e);
            }
        });
        return headers;
    }

    private String buildValue(Attributes ldapAttributes, String ldapPropertyName) throws NamingException {
        boolean base64 = false;
        if (ldapPropertyName.startsWith("base64:")) {
            ldapPropertyName = ldapPropertyName.substring("base64:".length());
            base64 = true;
        }
        Attribute attribute = ldapAttributes.get(ldapPropertyName);
        if (attribute != null) {
            NamingEnumeration<?> all = attribute.getAll();
            Stream<String> values = Collections.list(all).stream().filter(Predicates.notNull()).map(Object::toString);
            if (base64) {
                values = values.map(SecurityHeaders::encodeBase64);
            }
            return values.collect(Collectors.joining(","));
        }
        return null;
    }

    private String getCurrentUserName() {
        final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new IllegalStateException("Request is not authenticated");
        }
        return authentication.getName();
    }

    @VisibleForTesting
    Optional<Collection<Header>> getCachedHeaders(HttpSession session, String service) {
        final String username = getCurrentUserName();

        final String cacheKey = serviceCacheKey(service);
        final boolean cached = session.getAttribute(cacheKey) != null;
        if (cached) {
            try {
                @SuppressWarnings("unchecked")
                Collection<Header> headers = (Collection<Header>) session.getAttribute(cacheKey);
                String expectedUsername = (String) session.getAttribute(CACHED_USERNAME_KEY);

                if (username.equals(expectedUsername)) {
                    return Optional.of(headers);
                }
            } catch (Exception e) {
                logger.info("Unable to lookup cached user's attributes for user :" + username + ", service: " + service,
                        e);
            }
        }
        return Optional.empty();
    }

    @VisibleForTesting
    void setCachedHeaders(HttpSession session, Collection<Header> headers, String service) {
        final String username = getCurrentUserName();
        final String cacheKey = serviceCacheKey(service);
        logger.debug("Storing attributes into session for user :" + username + ", service: " + service);
        session.setAttribute(CACHED_USERNAME_KEY, username);
        session.setAttribute(cacheKey, new ArrayList<>(headers));
    }

    private String serviceCacheKey(String service) {
        return service == null ? CACHED_HEADERS_KEY : CACHED_HEADERS_KEY + "@" + service;
    }

    private boolean isAnnonymous() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof AnonymousAuthenticationToken;
    }
}
