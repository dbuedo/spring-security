/* Copyright 2004, 2005, 2006 Acegi Technology Pty Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.security.web.authentication.www;

import java.io.IOException;
import java.util.Map;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceAware;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.security.authentication.AuthenticationDetailsSource;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.SpringSecurityMessageSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserCache;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.userdetails.cache.NullUserCache;
import org.springframework.security.web.FilterChainOrder;
import org.springframework.security.web.SpringSecurityFilter;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;


/**
 * Processes a HTTP request's Digest authorization headers, putting the result into the
 * <code>SecurityContextHolder</code>.
 * <p>
 * For a detailed background on what this filter is designed to process, refer to
 * <a href="http://www.ietf.org/rfc/rfc2617.txt">RFC 2617</a> (which superseded RFC 2069, although this
 * filter support clients that implement either RFC 2617 or RFC 2069).
 * <p>
 * This filter can be used to provide Digest authentication services to both remoting protocol clients (such as
 * Hessian and SOAP) as well as standard user agents (such as Internet Explorer and FireFox).
 * <p>
 * This Digest implementation has been designed to avoid needing to store session state between invocations.
 * All session management information is stored in the "nonce" that is sent to the client by the {@link
 * DigestProcessingFilterEntryPoint}.
 * <p>
 * If authentication is successful, the resulting {@link org.springframework.security.core.Authentication Authentication}
 * object will be placed into the <code>SecurityContextHolder</code>.
 * <p>
 * If authentication fails, an {@link org.springframework.security.web.AuthenticationEntryPoint AuthenticationEntryPoint}
 * implementation is called. This must always be {@link DigestProcessingFilterEntryPoint}, which will prompt the user
 * to authenticate again via Digest authentication.
 * <p>
 * Note there are limitations to Digest authentication, although it is a more comprehensive and secure solution
 * than Basic authentication. Please see RFC 2617 section 4 for a full discussion on the advantages of Digest
 * authentication over Basic authentication, including commentary on the limitations that it still imposes.
 */
public class DigestProcessingFilter extends SpringSecurityFilter implements Filter, InitializingBean, MessageSourceAware {
    //~ Static fields/initializers =====================================================================================


    private static final Log logger = LogFactory.getLog(DigestProcessingFilter.class);

    //~ Instance fields ================================================================================================

    private AuthenticationDetailsSource authenticationDetailsSource = new WebAuthenticationDetailsSource();
    private DigestProcessingFilterEntryPoint authenticationEntryPoint;
    protected MessageSourceAccessor messages = SpringSecurityMessageSource.getAccessor();
    private UserCache userCache = new NullUserCache();
    private UserDetailsService userDetailsService;
    private boolean passwordAlreadyEncoded = false;

    //~ Methods ========================================================================================================

    public void afterPropertiesSet() throws Exception {
        Assert.notNull(userDetailsService, "A UserDetailsService is required");
        Assert.notNull(authenticationEntryPoint, "A DigestProcessingFilterEntryPoint is required");
    }

    public void doFilterHttp(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String header = request.getHeader("Authorization");

        if (logger.isDebugEnabled()) {
            logger.debug("Authorization header received from user agent: " + header);
        }

        if ((header != null) && header.startsWith("Digest ")) {
            String section212response = header.substring(7);

            String[] headerEntries = DigestAuthUtils.splitIgnoringQuotes(section212response, ',');
            Map<String,String> headerMap = DigestAuthUtils.splitEachArrayElementAndCreateMap(headerEntries, "=", "\"");

            String username = headerMap.get("username");
            String realm = headerMap.get("realm");
            String nonce = headerMap.get("nonce");
            String uri = headerMap.get("uri");
            String responseDigest = headerMap.get("response");
            String qop = headerMap.get("qop"); // RFC 2617 extension
            String nc = headerMap.get("nc"); // RFC 2617 extension
            String cnonce = headerMap.get("cnonce"); // RFC 2617 extension

            // Check all required parameters were supplied (ie RFC 2069)
            if ((username == null) || (realm == null) || (nonce == null) || (uri == null) || (response == null)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("extracted username: '" + username + "'; realm: '" + username + "'; nonce: '"
                            + username + "'; uri: '" + username + "'; response: '" + username + "'");
                }

                fail(request, response,
                        new BadCredentialsException(messages.getMessage("DigestProcessingFilter.missingMandatory",
                                new Object[]{section212response}, "Missing mandatory digest value; received header {0}")));

                return;
            }

            // Check all required parameters for an "auth" qop were supplied (ie RFC 2617)
            if ("auth".equals(qop)) {
                if ((nc == null) || (cnonce == null)) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("extracted nc: '" + nc + "'; cnonce: '" + cnonce + "'");
                    }

                    fail(request, response,
                            new BadCredentialsException(messages.getMessage("DigestProcessingFilter.missingAuth",
                                    new Object[]{section212response}, "Missing mandatory digest value; received header {0}")));

                    return;
                }
            }

            // Check realm name equals what we expected
            if (!this.getAuthenticationEntryPoint().getRealmName().equals(realm)) {
                fail(request, response,
                        new BadCredentialsException(messages.getMessage("DigestProcessingFilter.incorrectRealm",
                                new Object[]{realm, this.getAuthenticationEntryPoint().getRealmName()},
                                "Response realm name '{0}' does not match system realm name of '{1}'")));

                return;
            }

            // Check nonce was a Base64 encoded (as sent by DigestProcessingFilterEntryPoint)
            if (!Base64.isArrayByteBase64(nonce.getBytes())) {
                fail(request, response,
                        new BadCredentialsException(messages.getMessage("DigestProcessingFilter.nonceEncoding",
                                new Object[]{nonce}, "Nonce is not encoded in Base64; received nonce {0}")));

                return;
            }

            // Decode nonce from Base64
            // format of nonce is:
            //   base64(expirationTime + ":" + md5Hex(expirationTime + ":" + key))
            String nonceAsPlainText = new String(Base64.decodeBase64(nonce.getBytes()));
            String[] nonceTokens = StringUtils.delimitedListToStringArray(nonceAsPlainText, ":");

            if (nonceTokens.length != 2) {
                fail(request, response,
                        new BadCredentialsException(messages.getMessage("DigestProcessingFilter.nonceNotTwoTokens",
                                new Object[]{nonceAsPlainText}, "Nonce should have yielded two tokens but was {0}")));

                return;
            }

            // Extract expiry time from nonce
            long nonceExpiryTime;

            try {
                nonceExpiryTime = new Long(nonceTokens[0]).longValue();
            } catch (NumberFormatException nfe) {
                fail(request, response,
                        new BadCredentialsException(messages.getMessage("DigestProcessingFilter.nonceNotNumeric",
                                new Object[]{nonceAsPlainText},
                                "Nonce token should have yielded a numeric first token, but was {0}")));

                return;
            }

            // Check signature of nonce matches this expiry time
            String expectedNonceSignature = DigestUtils.md5Hex(nonceExpiryTime + ":"
                    + this.getAuthenticationEntryPoint().getKey());

            if (!expectedNonceSignature.equals(nonceTokens[1])) {
                fail(request, response,
                        new BadCredentialsException(messages.getMessage("DigestProcessingFilter.nonceCompromised",
                                new Object[]{nonceAsPlainText}, "Nonce token compromised {0}")));

                return;
            }

            // Lookup password for presented username
            // NB: DAO-provided password MUST be clear text - not encoded/salted
            // (unless this instance's passwordAlreadyEncoded property is 'false')
            boolean loadedFromDao = false;
            UserDetails user = userCache.getUserFromCache(username);

            if (user == null) {
                loadedFromDao = true;

                try {
                    user = userDetailsService.loadUserByUsername(username);
                } catch (UsernameNotFoundException notFound) {
                    fail(request, response,
                            new BadCredentialsException(messages.getMessage("DigestProcessingFilter.usernameNotFound",
                                    new Object[]{username}, "Username {0} not found")));

                    return;
                }

                if (user == null) {
                    throw new AuthenticationServiceException(
                            "AuthenticationDao returned null, which is an interface contract violation");
                }

                userCache.putUserInCache(user);
            }

            // Compute the expected response-digest (will be in hex form)
            String serverDigestMd5;

            // Don't catch IllegalArgumentException (already checked validity)
            serverDigestMd5 = DigestAuthUtils.generateDigest(passwordAlreadyEncoded, username, realm, user.getPassword(),
                    request.getMethod(), uri, qop, nonce, nc, cnonce);

            // If digest is incorrect, try refreshing from backend and recomputing
            if (!serverDigestMd5.equals(responseDigest) && !loadedFromDao) {
                if (logger.isDebugEnabled()) {
                    logger.debug(
                            "Digest comparison failure; trying to refresh user from DAO in case password had changed");
                }

                try {
                    user = userDetailsService.loadUserByUsername(username);
                } catch (UsernameNotFoundException notFound) {
                    // Would very rarely happen, as user existed earlier
                    fail(request, response,
                            new BadCredentialsException(messages.getMessage("DigestProcessingFilter.usernameNotFound",
                                    new Object[]{username}, "Username {0} not found")));
                }

                userCache.putUserInCache(user);

                // Don't catch IllegalArgumentException (already checked validity)
                serverDigestMd5 = DigestAuthUtils.generateDigest(passwordAlreadyEncoded, username, realm, user.getPassword(),
                        request.getMethod(), uri, qop, nonce, nc, cnonce);
            }

            // If digest is still incorrect, definitely reject authentication attempt
            if (!serverDigestMd5.equals(responseDigest)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Expected response: '" + serverDigestMd5 + "' but received: '" + responseDigest
                            + "'; is AuthenticationDao returning clear text passwords?");
                }

                fail(request, response,
                        new BadCredentialsException(messages.getMessage("DigestProcessingFilter.incorrectResponse",
                                "Incorrect response")));
                return;
            }

            // To get this far, the digest must have been valid
            // Check the nonce has not expired
            // We do this last so we can direct the user agent its nonce is stale
            // but the request was otherwise appearing to be valid
            if (nonceExpiryTime < System.currentTimeMillis()) {
                fail(request, response,
                        new NonceExpiredException(messages.getMessage("DigestProcessingFilter.nonceExpired",
                                "Nonce has expired/timed out")));

                return;
            }

            if (logger.isDebugEnabled()) {
                logger.debug("Authentication success for user: '" + username + "' with response: '" + responseDigest
                        + "'");
            }

            UsernamePasswordAuthenticationToken authRequest = new UsernamePasswordAuthenticationToken(user,
                    user.getPassword());

            authRequest.setDetails(authenticationDetailsSource.buildDetails((HttpServletRequest) request));

            SecurityContextHolder.getContext().setAuthentication(authRequest);
        }

        chain.doFilter(request, response);
    }

    private void fail(HttpServletRequest request, HttpServletResponse response, AuthenticationException failed)
            throws IOException, ServletException {
        SecurityContextHolder.getContext().setAuthentication(null);

        if (logger.isDebugEnabled()) {
            logger.debug(failed);
        }

        authenticationEntryPoint.commence(request, response, failed);
    }

    public DigestProcessingFilterEntryPoint getAuthenticationEntryPoint() {
        return authenticationEntryPoint;
    }

    public UserCache getUserCache() {
        return userCache;
    }

    public UserDetailsService getUserDetailsService() {
        return userDetailsService;
    }

    public void setAuthenticationDetailsSource(AuthenticationDetailsSource authenticationDetailsSource) {
        Assert.notNull(authenticationDetailsSource, "AuthenticationDetailsSource required");
        this.authenticationDetailsSource = authenticationDetailsSource;
    }

    public void setAuthenticationEntryPoint(DigestProcessingFilterEntryPoint authenticationEntryPoint) {
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    public void setMessageSource(MessageSource messageSource) {
        this.messages = new MessageSourceAccessor(messageSource);
    }

    public void setPasswordAlreadyEncoded(boolean passwordAlreadyEncoded) {
        this.passwordAlreadyEncoded = passwordAlreadyEncoded;
    }

    public void setUserCache(UserCache userCache) {
        this.userCache = userCache;
    }

    public void setUserDetailsService(UserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    public int getOrder() {
        return FilterChainOrder.DIGEST_PROCESSING_FILTER;
    }
}