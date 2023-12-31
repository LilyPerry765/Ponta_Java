package org.cloudfoundry.identity.uaa.zone;

import org.cloudfoundry.identity.uaa.authentication.UaaAuthentication;
import org.cloudfoundry.identity.uaa.authentication.UaaAuthenticationDetails;
import org.cloudfoundry.identity.uaa.util.UaaStringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.OAuth2Request;
import org.springframework.security.oauth2.provider.expression.OAuth2ExpressionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * If the X-Identity-Zone-Id header is set and the user has a scope
 * of zones.&lt;id&gt;.admin, this filter switches the IdentityZone in the IdentityZoneHolder
 * to the one in the header.
 *
 * @author wtran@pivotal.io
 *
 */
public class IdentityZoneSwitchingFilter extends OncePerRequestFilter {

    @Autowired
    public IdentityZoneSwitchingFilter(IdentityZoneProvisioning dao) {
        super();
        this.dao = dao;
    }

    private final IdentityZoneProvisioning dao;
    public static final String HEADER = "X-Identity-Zone-Id";

    public static final String ZONE_ID_MATCH = "{zone_id}";
    public static final String ZONES_ZONE_ID_PREFIX = "zones." ;
    public static final String ZONES_ZONE_ID_ADMIN = ZONES_ZONE_ID_PREFIX + ZONE_ID_MATCH + "."+ "admin";
    public static final List<String> zoneSwitchScopes = Collections.unmodifiableList(
        Arrays.asList(
            ZONES_ZONE_ID_ADMIN,
            ZONES_ZONE_ID_PREFIX + ZONE_ID_MATCH + ".read",
            ZONES_ZONE_ID_PREFIX + ZONE_ID_MATCH + ".clients.admin",
            ZONES_ZONE_ID_PREFIX + ZONE_ID_MATCH + ".clients.read",
            ZONES_ZONE_ID_PREFIX + ZONE_ID_MATCH + ".clients.write",
            ZONES_ZONE_ID_PREFIX + ZONE_ID_MATCH + ".idps.read")
    );
    public static final List<String> zoneScopestoNotStripPrefix = Collections.unmodifiableList(
         Arrays.asList(
            "admin",
            "read")
            );

    protected boolean isAuthorizedToSwitchToIdentityZone(String identityZoneId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean hasScope = OAuth2ExpressionUtils.hasAnyScope(authentication, getZoneSwitchingScopes(identityZoneId));
        boolean isUaa = IdentityZoneHolder.isUaa();
        boolean isTokenAuth = (authentication instanceof OAuth2Authentication);
        return isTokenAuth && isUaa && hasScope;
    }

    protected void stripScopesFromAuthentication(String identityZoneId, HttpServletRequest servletRequest) {
        OAuth2Authentication oa = (OAuth2Authentication)SecurityContextHolder.getContext().getAuthentication();

        Object oaDetails = oa.getDetails();

        //strip client scopes
        OAuth2Request request = oa.getOAuth2Request();
        Collection<String> requestAuthorities = UaaStringUtils.getStringsFromAuthorities(request.getAuthorities());
        Set<String> clientScopes = new HashSet<>();
        Set<String> clientAuthorities = new HashSet<>();
        for (String s : getZoneSwitchingScopes(identityZoneId)) {
            String scope = stripPrefix(s, identityZoneId);
            if (request.getScope().contains(s)) {
                clientScopes.add(scope);
            }
            if (requestAuthorities.contains(s)) {
                clientAuthorities.add(scope);
            }
        }
        request = new OAuth2Request(
            request.getRequestParameters(),
            request.getClientId(),
            UaaStringUtils.getAuthoritiesFromStrings(clientAuthorities),
            request.isApproved(),
            clientScopes,
            request.getResourceIds(),
            request.getRedirectUri(),
            request.getResponseTypes(),
            request.getExtensions()
            );


        UaaAuthentication userAuthentication = (UaaAuthentication)oa.getUserAuthentication();
        if (userAuthentication!=null) {
            userAuthentication = new UaaAuthentication(
                userAuthentication.getPrincipal(),
                null,
                UaaStringUtils.getAuthoritiesFromStrings(clientScopes),
                new UaaAuthenticationDetails(servletRequest),
                true);
        }
        oa = new OAuth2Authentication(request, userAuthentication);
        oa.setDetails(oaDetails);
        SecurityContextHolder.getContext().setAuthentication(oa);
    }

    protected String stripPrefix(String s, String identityZoneId) {
        if (!StringUtils.hasText(s)) {
            return s;
        }
        //dont touch the zones.{zone.id}.admin scope
        String replace = ZONES_ZONE_ID_PREFIX+identityZoneId+".";
        for (String scope : zoneScopestoNotStripPrefix) {
            if (s.equals(replace + scope)) {
                return s;
            }
        }

        //replace zones.<id>.

        if (s.startsWith(replace)) {
            return s.substring(replace.length());
        }
        return s;
    }

    protected String[] getZoneSwitchingScopes(String identityZoneId) {
        String[] result = new String[zoneSwitchScopes.size()];
        for (int i=0; i<result.length; i++) {
            result[i] = zoneSwitchScopes.get(i).replace(ZONE_ID_MATCH, identityZoneId);
        }
        return result;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String identityZoneId = request.getHeader(HEADER);
        if (StringUtils.hasText(identityZoneId)) {
            if (!isAuthorizedToSwitchToIdentityZone(identityZoneId)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "User is not authorized to switch to IdentityZone with id "+identityZoneId);
                return;
            }
            IdentityZone originalIdentityZone = IdentityZoneHolder.get();
            try {

                IdentityZone identityZone = null;
                try {
                    identityZone = dao.retrieve(identityZoneId);
                } catch (ZoneDoesNotExistsException ex) {
                } catch (EmptyResultDataAccessException ex) {
                } catch (Exception ex) {
                    throw ex;
                }
                if (identityZone == null) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND, "Identity zone with id "+identityZoneId+" does not exist");
                    return;
                }
                stripScopesFromAuthentication(identityZoneId, request);
                IdentityZoneHolder.set(identityZone);
                filterChain.doFilter(request, response);
            } finally {
                IdentityZoneHolder.set(originalIdentityZone);
            }
        } else {
            filterChain.doFilter(request, response);
        }
    }
}
